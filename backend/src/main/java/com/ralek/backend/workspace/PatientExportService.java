package com.ralek.backend.workspace;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

@Service
public class PatientExportService {
    public static final long MAX_BYTES=250L*1024*1024;
    private final JdbcClient jdbc; private final UserContextService contexts;
    private final ObjectStorageService storage; private final AuditService audit; private final ObjectMapper mapper;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"patient-export");t.setDaemon(true);return t;});
    private final AtomicBoolean working=new AtomicBoolean();
    @Value("${ralek.exports.enabled:true}") boolean enabled=true;
    @Value("${ralek.exports.worker-enabled:true}") boolean workerEnabled=true;
    public PatientExportService(JdbcClient jdbc,UserContextService contexts,ObjectStorageService storage,AuditService audit,ObjectMapper mapper) {
        this.jdbc=jdbc;this.contexts=contexts;this.storage=storage;this.audit=audit;this.mapper=mapper;
    }
    public record Request(UUID requestKey,UUID visitId,List<UUID> studyIds,boolean includePlan,boolean includeProgress,boolean includePreviousPlan,String format,boolean confirmed) {}
    public record Job(UUID id,String status,String errorMessage,Instant expiresAt,String format) {}
    public record Document(UUID id,String title,String performedAt,String originalFilename,UUID assetId,String bucket,String key,String sha256,long size,String version) {}
    public record Snapshot(String patientName,List<String> blocks,List<Document> documents,List<String> versions) {}
    private record Work(UUID id,UUID actor,UUID patient,String request,String snapshot,String fingerprint) {}
    private void feature() {if(!enabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Eksport jest wyłączony");}
    private UserContextService.UserContext patient(Jwt jwt,String role) {
        var actor=contexts.resolve(jwt,role);
        if(!actor.patientView() || actor.patientId()==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Możesz eksportować wyłącznie własne dane w widoku pacjenta");
        return actor;
    }
    private String json(Object value) {return mapper.writeValueAsString(value);}
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private void validate(Request input) {
        if(input==null || input.format()==null || input.requestKey()==null || input.studyIds()==null || input.studyIds().size()>50 || !Set.of("PDF","ZIP").contains(input.format()) || !input.confirmed()
            || input.studyIds().stream().anyMatch(Objects::isNull) || (input.includeProgress() && !input.includePlan()) || (input.includePreviousPlan() && !input.includePlan()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Sprawdź i potwierdź zakres pakietu (maksymalnie 50 dokumentów)");
        if(input.visitId()==null && input.studyIds().isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Wybierz wizytę lub dokumenty");
    }
    @Transactional
    public Job create(Jwt jwt,String role,Request input) {
        feature();var actor=patient(jwt,role);validate(input);
        // Serialize duplicate creation for this patient without relying on an in-memory lock.
        jdbc.sql("SELECT id FROM patients WHERE id=:id FOR UPDATE").param("id",actor.patientId()).query(UUID.class).single();
        var existing=jdbc.sql("SELECT id,request_json FROM patient_exports WHERE actor_id=:actor AND request_key=:key")
            .param("actor",actor.id()).param("key",input.requestKey()).query((rs,n)->new String[]{rs.getString("id"),rs.getString("request_json")}).optional();
        if(existing.isPresent()) {
            if(!existing.get()[1].equals(json(input)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Zmieniono zakres ponawianego żądania");
            return status(jwt,role,UUID.fromString(existing.get()[0]));
        }
        var snapshot=snapshot(actor.patientId(),input);
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO patient_exports(id,actor_id,patient_id,request_key,request_json,snapshot_json,fingerprint) VALUES (:id,:actor,:patient,:key,:request,:snapshot,:fingerprint)")
            .param("id",id).param("actor",actor.id()).param("patient",actor.patientId()).param("key",input.requestKey())
            .param("request",json(input)).param("snapshot",json(snapshot)).param("fingerprint",hash(json(snapshot))).update();
        audit.record(actor.id(),actor.patientId(),"EXPORT",id,"CREATE",true,null,Map.of("format",input.format(),"documentCount",input.studyIds().size()));
        return status(jwt,role,id);
    }
    Snapshot snapshot(UUID patient,Request input) {
        String patientName=jdbc.sql("SELECT u.first_name || ' ' || u.last_name FROM patients p JOIN users u ON u.id=p.user_id WHERE p.id=:id")
            .param("id",patient).query(String.class).single();
        List<String> blocks=new ArrayList<>(),versions=new ArrayList<>();List<Document> documents=new ArrayList<>();
        if(input.visitId()!=null) {
            var row=jdbc.sql("SELECT * FROM visit_packages WHERE id=:id AND patient_id=:patient")
                .param("id",input.visitId()).param("patient",patient).query((rs,n)->new String[]{rs.getString("reason"),rs.getString("symptoms"),rs.getString("questions"),
                    rs.getString("visit_date"),display(rs.getTimestamp("created_at").toInstant()),rs.getString("updated_at"),rs.getString("previous_visit_id")}).optional()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Brak dostępu do wybranej wizyty"));
            blocks.add("WIZYTA: "+row[0]);blocks.add("Termin wizyty: "+fallback(row[3]));
            blocks.add("PACJENT - "+patientName+"; pakiet utworzony: "+row[4]);
            blocks.add("Powód wizyty: "+row[0]);blocks.add("Opis pacjenta: "+fallback(row[1]));blocks.add("Pytania pacjenta: "+fallback(row[2]));
            versions.add("visit:"+input.visitId()+":"+row[5]);
            if(input.includePlan())addPlan(patient,input.visitId(),input.includeProgress(),"ZATWIERDZONY PLAN LEKARZA",blocks,versions);
            if(input.includePreviousPlan() && row[6]!=null)addPlan(patient,UUID.fromString(row[6]),input.includeProgress(),"POPRZEDNI ZATWIERDZONY PLAN",blocks,versions);
        }
        long total=0;
        for(UUID id:new TreeSet<>(input.studyIds())) {
            var document=jdbc.sql("""
                SELECT s.*,a.asset_id,a.original_filename,a.bucket_name,a.object_key,a.sha256,COALESCE(a.size_bytes,0) AS bytes
                FROM studies s LEFT JOIN LATERAL (
                    SELECT sa.id AS asset_id,so.original_filename,so.bucket_name,so.object_key,so.sha256,so.size_bytes FROM study_assets sa JOIN storage_objects so ON so.id=sa.storage_object_id
                    WHERE sa.study_id=s.id AND sa.asset_type='ORIGINAL' AND so.storage_status='STORED'
                    ORDER BY sa.is_primary DESC,sa.created_at DESC,sa.id LIMIT 1
                ) a ON TRUE WHERE s.id=:id AND s.patient_id=:patient AND s.archived_at IS NULL
                AND NOT EXISTS(SELECT 1 FROM imaging_studies i WHERE i.id=s.id)
                """).param("id",id).param("patient",patient).query((rs,n)->new Document(id,rs.getString("title"),rs.getString("performed_at"),rs.getString("original_filename"),
                    rs.getObject("asset_id",UUID.class),rs.getString("bucket_name"),rs.getString("object_key"),rs.getString("sha256"),rs.getLong("bytes"),rs.getString("updated_at")+":"+rs.getLong("metadata_revision")))
                .optional().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Wybrany dokument jest niedostępny lub jest pełną serią obrazową"));
            if(input.format().equals("ZIP") && document.assetId()==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Brakuje oryginału wybranego dokumentu");
            documents.add(document);total+=document.size();
        }
        if(input.format().equals("ZIP") && total>MAX_BYTES)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Wybrane oryginały przekraczają 250 MB. Zmniejsz wybór lub pobierz je osobno.");
        return new Snapshot(patientName,blocks,documents,versions);
    }
    private void addPlan(UUID patient,UUID visit,boolean progress,String heading,List<String> blocks,List<String> versions) {
        var plans=jdbc.sql("SELECT v.*,u.first_name || ' ' || u.last_name AS author FROM visit_packages v JOIN users u ON u.id=v.approved_by WHERE v.id=:id AND v.patient_id=:patient AND v.approved_at IS NOT NULL")
            .param("id",visit).param("patient",patient).query((rs,n)->new String[]{rs.getString("summary"),rs.getString("author"),display(rs.getTimestamp("approved_at").toInstant())}).list();
        if(plans.isEmpty()){blocks.add(heading+": brak zatwierdzonego planu.");return;}
        blocks.add(heading+" - "+plans.getFirst()[1]+"; zatwierdzono: "+plans.getFirst()[2]);blocks.add(plans.getFirst()[0]);
        var actions=jdbc.sql("""
            SELECT a.*,r.first_name || ' ' || r.last_name AS patient_author,d.first_name || ' ' || d.last_name AS doctor_author
            FROM visit_actions a LEFT JOIN users r ON r.id=a.reported_by LEFT JOIN users d ON d.id=a.discussed_by WHERE a.visit_id=:id ORDER BY a.position,a.id
            """).param("id",visit).query((rs,n)->{
                var lines=new ArrayList<String>();lines.add("Krok: "+rs.getString("description")+"; do: "+rs.getString("due_date")+"; odpowiada: "+rs.getString("responsible"));
                if(progress) {
                    String label=switch(rs.getString("patient_status")){case "DONE"->"Wykonane według pacjenta";case "DIFFICULTY"->"Pacjent zgłasza trudność";default->"Do wykonania";};
                    lines.add("ZGŁOSZENIE PACJENTA: "+label+"; autor: "+fallback(rs.getString("patient_author"))+"; zgłoszono: "+fallback(rs.getString("reported_at")));
                    if(rs.getString("performed_on")!=null)lines.add("Data wykonania według pacjenta: "+rs.getString("performed_on"));
                    if(!rs.getString("patient_note").isBlank())lines.add("Notatka pacjenta: "+rs.getString("patient_note"));
                    if(rs.getString("obstacle")!=null)lines.add("Trudność: "+switch(rs.getString("obstacle")){case "NO_APPOINTMENT"->"Brak terminu";case "COST"->"Koszt";case "UNCLEAR"->"Niezrozumiałe zalecenie";default->"Inna przyczyna";});
                    lines.add(rs.getString("discussed_at")==null?"Do omówienia przez lekarza":"Omówione przez: "+rs.getString("doctor_author")+"; data: "+rs.getString("discussed_at"));
                }
                return lines;
            }).list();
        actions.forEach(blocks::addAll);
    }
    private static String display(Instant value){return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(value.atZone(ZoneId.of("Europe/Warsaw")));}
    private static String fallback(String value){return value==null || value.isBlank()?"Nie podano":value;}
    public Job status(Jwt jwt,String role,UUID id) {
        feature();var actor=patient(jwt,role);
        return jdbc.sql("SELECT * FROM patient_exports WHERE id=:id AND actor_id=:actor AND patient_id=:patient")
            .param("id",id).param("actor",actor.id()).param("patient",actor.patientId()).query((rs,n)->new Job(id,
                rs.getTimestamp("expires_at").toInstant().isBefore(Instant.now())?"EXPIRED":rs.getString("status"),rs.getString("error_message"),rs.getTimestamp("expires_at").toInstant(),
                mapper.readValue(rs.getString("request_json"),Request.class).format())).optional().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Nie znaleziono pakietu"));
    }
    private Work work(UUID id) {
        return jdbc.sql("SELECT * FROM patient_exports WHERE id=:id").param("id",id).query((rs,n)->new Work(id,rs.getObject("actor_id",UUID.class),rs.getObject("patient_id",UUID.class),
            rs.getString("request_json"),rs.getString("snapshot_json"),rs.getString("fingerprint"))).single();
    }
    private boolean unchanged(Work work) {
        try {return work.fingerprint().equals(hash(json(snapshot(work.patient(),mapper.readValue(work.request(),Request.class)))));}
        catch(ResponseStatusException error){return false;}
    }
    public record Download(String filename,String contentType,long size,InputStream stream) {}
    public Download download(Jwt jwt,String role,UUID id) {
        var job=status(jwt,role,id);var actor=patient(jwt,role);
        if(!job.status().equals("READY"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Pakiet nie jest gotowy lub wygasł. Przygotuj go ponownie.");
        if(!unchanged(work(id))) {
            jdbc.sql("UPDATE patient_exports SET status='INVALIDATED',error_message='Źródła zmieniły się. Przygotuj nowy pakiet.' WHERE id=:id").param("id",id).update();
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Źródła zmieniły się. Przygotuj nowy pakiet.");
        }
        var row=jdbc.sql("SELECT bucket_name,object_key,byte_size FROM patient_exports WHERE id=:id").param("id",id)
            .query((rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getLong(3)}).single();
        var stream=storage.get((String)row[0],(String)row[1]);
        try {audit.record(actor.id(),actor.patientId(),"EXPORT",id,"DOWNLOAD",true,null,Map.of());}
        catch(RuntimeException error){try{stream.close();}catch(IOException ignored){}throw error;}
        return new Download("curavence-pakiet."+job.format().toLowerCase(Locale.ROOT),job.format().equals("ZIP")?"application/zip":"application/pdf",(long)row[2],stream);
    }
    @Scheduled(fixedDelay=3000,initialDelay=5000)
    public void schedule() {
        if(!workerEnabled || !working.compareAndSet(false,true))return;
        executor.submit(()->{try{cleanup();if(enabled)runNext();}catch(Exception error){org.slf4j.LoggerFactory.getLogger(PatientExportService.class).warn("Export worker could not complete its maintenance cycle ({})",error.getClass().getSimpleName());}finally{working.set(false);}});
    }
    void runNext() {
        var claimed=jdbc.sql("""
            UPDATE patient_exports SET status='GENERATING',started_at=CURRENT_TIMESTAMP
            WHERE id=(SELECT id FROM patient_exports WHERE status='QUEUED' AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
            AND status='QUEUED' RETURNING id
            """).query(UUID.class).optional();
        if(claimed.isEmpty())return;
        UUID id=claimed.get();Path output=null;String storedBucket=null,storedKey=null;
        try {
            var work=work(id);if(!unchanged(work))throw new SourceChanged();
            var input=mapper.readValue(work.request(),Request.class);var snapshot=mapper.readValue(work.snapshot(),Snapshot.class);
            output=Files.createTempFile("curavence-export-",input.format().equals("ZIP")?".zip":".pdf");
            byte[] pdf=PatientExportPdf.render(snapshot);
            if(input.format().equals("PDF"))Files.write(output,pdf);
            else try(var zip=new ZipOutputStream(Files.newOutputStream(output),StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("podsumowanie.pdf"));zip.write(pdf);zip.closeEntry();
                zip.putNextEntry(new ZipEntry("spis.txt"));zip.write(manifest(snapshot).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
                long total=0;int index=0;
                for(var document:snapshot.documents()) {
                    zip.putNextEntry(new ZipEntry(entryName(document,++index)));
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");long read=0;
                    try(var stream=storage.get(document.bucket(),document.key())) {
                        byte[] buffer=new byte[65536];int count;
                        while((count=stream.read(buffer))!=-1) {read+=count;total+=count;if(total>MAX_BYTES || read>document.size())throw new IOException("File size mismatch");digest.update(buffer,0,count);zip.write(buffer,0,count);}
                    }
                    if(read!=document.size() || (document.sha256()!=null && !document.sha256().equals(HexFormat.of().formatHex(digest.digest()))))throw new IOException("File integrity mismatch");
                    zip.closeEntry();
                }
            }
            if(!unchanged(work))throw new SourceChanged();
            String key="exports/"+work.actor()+"/"+id+"."+input.format().toLowerCase(Locale.ROOT);
            // Persist the destination before uploading: an ambiguous network failure must not orphan a private copy.
            storedBucket=storage.derivedBucket();storedKey=key;
            jdbc.sql("UPDATE patient_exports SET bucket_name=:bucket,object_key=:key WHERE id=:id AND status='GENERATING'")
                .param("bucket",storedBucket).param("key",key).param("id",id).update();
            try(var stream=Files.newInputStream(output)) {
                var stored=storage.putDerived(key,input.format().equals("PDF")?"application/pdf":"application/zip",Files.size(output),stream);
                storedBucket=stored.bucket();storedKey=key;
                int updated=jdbc.sql("UPDATE patient_exports SET status='READY',bucket_name=:bucket,object_key=:key,byte_size=:size WHERE id=:id AND status='GENERATING' AND expires_at>CURRENT_TIMESTAMP")
                    .param("bucket",stored.bucket()).param("key",key).param("size",Files.size(output)).param("id",id).update();
                if(updated!=1)storage.remove(stored.bucket(),key);
            }
        } catch(Exception error) {
            if(storedKey!=null)try{storage.remove(storedBucket,storedKey);}catch(Exception ignored){}
            jdbc.sql("UPDATE patient_exports SET status=:status,error_message=:message WHERE id=:id AND status='GENERATING'")
                .param("id",id).param("status",error instanceof SourceChanged?"INVALIDATED":"FAILED")
                .param("message",error instanceof SourceChanged?"Źródła zmieniły się. Przygotuj nowy pakiet.":"Nie udało się przygotować kompletnego pakietu. Sprawdź dostępność oryginałów i spróbuj ponownie.").update();
        } finally {if(output!=null)try{Files.deleteIfExists(output);}catch(IOException ignored){}}
    }
    private static class SourceChanged extends RuntimeException {}
    static String entryName(Document document,int index) {
        String date=document.performedAt()==null?"bez-daty":document.performedAt().substring(0,10);
        String name=document.title().replaceAll("[^\\p{L}\\p{N}._ -]","_").replaceAll("[. ]+$","");
        if(name.length()>90)name=name.substring(0,90);
        String original=document.originalFilename()==null?"":document.originalFilename();
        String extension=original.matches("(?s).*\\.[a-zA-Z0-9]{1,10}$")?original.substring(original.lastIndexOf('.')):".bin";
        return String.format(Locale.ROOT,"oryginaly/%02d_%s_%s%s",index,date,name,extension);
    }
    static String manifest(Snapshot snapshot) {
        var text=new StringBuilder("Curavence - wybrane oryginały\n");int index=0;
        for(var d:snapshot.documents())text.append(entryName(d,++index)).append(" | Oryginalna nazwa: ").append(d.originalFilename()).append(" | SHA-256: ").append(d.sha256()).append('\n');
        return text.toString();
    }
    void cleanup() {
        jdbc.sql("UPDATE patient_exports SET status='EXPIRED',snapshot_json='' WHERE expires_at<=CURRENT_TIMESTAMP AND status<>'EXPIRED'").update();
        jdbc.sql("UPDATE patient_exports SET status='FAILED',error_message='Przygotowanie zostało przerwane. Utwórz nowy pakiet.' WHERE status='GENERATING' AND started_at<CURRENT_TIMESTAMP-INTERVAL '15 minutes'").update();
        var expired=jdbc.sql("SELECT id,bucket_name,object_key FROM patient_exports WHERE status IN ('EXPIRED','INVALIDATED','FAILED') AND object_key IS NOT NULL")
            .query((rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)}).list();
        for(var row:expired)try{storage.remove(row[1],row[2]);jdbc.sql("UPDATE patient_exports SET bucket_name=NULL,object_key=NULL WHERE id=:id").param("id",UUID.fromString(row[0])).update();}catch(Exception ignored){}
    }
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
}
