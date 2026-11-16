package com.ralek.backend.knowledge;

import com.ralek.backend.knowledge.KnowledgeDtos.*;
import com.ralek.backend.workspace.UserContextService;
import com.ralek.backend.storage.ObjectStorageService;
import com.ralek.backend.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.springframework.http.HttpStatus.*;

@Service
public class KnowledgeService {
    private final JdbcClient db; private final UserContextService users; private final ObjectStorageService storage;
    private final KnowledgeGenerator generator; private final ObjectMapper mapper; private final AuditService audit;
    @Value("${ralek.knowledge.enabled:false}") boolean enabled;
    public KnowledgeService(JdbcClient db,UserContextService users,ObjectStorageService storage,KnowledgeGenerator generator,ObjectMapper mapper,AuditService audit){
        this.db=db;this.users=users;this.storage=storage;this.generator=generator;this.mapper=mapper;this.audit=audit;
    }
    record Scope(UUID id,UUID actor,UUID patient,UUID visit,boolean owner){}
    private Scope scope(Jwt jwt,String role,UUID visit){
        if(!enabled)throw new ResponseStatusException(NOT_FOUND,"Podsumowanie dokumentacji jest wyłączone");
        var actor=users.resolve(jwt,role); UUID patient=actor.patientId();
        if(!actor.patientView()&&!actor.effectiveView().equals("doctor"))throw new ResponseStatusException(FORBIDDEN);
        if(!actor.patientView()&&visit==null)throw new ResponseStatusException(BAD_REQUEST,"Wybierz pakiet wizyty");
        if(visit!=null){
            patient=db.sql("SELECT patient_id FROM visit_packages WHERE id=:id").param("id",visit).query(UUID.class).optional().orElseThrow(()->new ResponseStatusException(NOT_FOUND));
            if(actor.patientView()&&!patient.equals(actor.patientId()))throw new ResponseStatusException(NOT_FOUND);
        }
        if(patient==null)throw new ResponseStatusException(FORBIDDEN);
        Scope provisional=new Scope(UUID.randomUUID(),actor.id(),patient,visit,actor.patientView()); authorize(provisional);
        db.sql("INSERT INTO patient_knowledge(id,actor_id,patient_id,visit_id,owner_view) VALUES(:id,:actor,:patient,:visit,:owner) ON CONFLICT DO NOTHING")
            .param("id",provisional.id()).param("actor",actor.id()).param("patient",patient).param("visit",visit,java.sql.Types.OTHER).param("owner",actor.patientView()).update();
        UUID id=db.sql("SELECT id FROM patient_knowledge WHERE actor_id=:actor AND patient_id=:patient AND visit_id IS NOT DISTINCT FROM :visit AND owner_view=:owner")
            .param("actor",actor.id()).param("patient",patient).param("visit",visit,java.sql.Types.OTHER).param("owner",actor.patientView()).query(UUID.class).single();
        return new Scope(id,actor.id(),patient,visit,actor.patientView());
    }
    private void authorize(Scope s){
        String sql=s.owner()?"SELECT count(*) FROM patients p JOIN users u ON u.id=p.user_id WHERE p.id=:patient AND p.user_id=:actor AND u.status='ACTIVE'":
            "SELECT count(*) FROM visit_packages v JOIN access_grants ag ON ag.id=v.grant_id JOIN users u ON u.id=ag.recipient_user_id WHERE v.id=:visit AND v.patient_id=:patient AND ag.patient_id=v.patient_id AND ag.recipient_user_id=:actor AND u.status='ACTIVE' AND ag.status='ACTIVE' AND (ag.expires_at IS NULL OR ag.expires_at>CURRENT_TIMESTAMP)";
        if(db.sql(sql).param("patient",s.patient()).param("actor",s.actor()).param("visit",s.visit(),java.sql.Types.OTHER).query(Integer.class).single()!=1)throw new ResponseStatusException(NOT_FOUND,"Ten zakres dokumentacji nie jest dostępny");
    }
    public View view(Jwt jwt,String role,UUID visit){
        Scope s=scope(jwt,role,visit);refresh(s);authorize(s);
        var row=db.sql("SELECT status,updated_at,error_message,content_json FROM patient_knowledge WHERE id=:id").param("id",s.id()).query((rs,n)->{
            Content c=mapper.readValue(rs.getString("content_json"),Content.class);
            // Raw source text stays on the server; citations are the only exposed fragments.
            return new View(s.id(),rs.getString("status"),rs.getTimestamp("updated_at").toInstant().toString(),rs.getString("error_message"),
                c.sources().stream().map(x->new Source(x.id(),x.kind(),x.targetId(),x.title(),"",x.version(),x.limitation())).toList(),c.pages());
        }).single();
        audit.record(s.actor(),s.patient(),"KNOWLEDGE",s.id(),"VIEW",true,null,Map.of());return row;
    }
    public void rebuild(Jwt jwt,String role,UUID visit){Scope s=scope(jwt,role,visit);refresh(s);
        db.sql("UPDATE patient_knowledge SET status='QUEUED',attempts=0,error_message=NULL WHERE id=:id AND status<>'GENERATING'").param("id",s.id()).update();
    }
    public Reply ask(Jwt jwt,String role,Question input){
        if(input.question()==null||input.question().isBlank()||input.question().length()>1000)throw new ResponseStatusException(BAD_REQUEST,"Pytanie musi mieć od 1 do 1000 znaków");
        Scope s=scope(jwt,role,input.visitId());String fingerprint=refresh(s);Content content=content(s);
        List<Source> selected=content.sources().stream().filter(x->!x.text().isBlank()).toList();
        if(selected.isEmpty())return new Reply(List.of(),"Brak dostępnych źródeł tekstowych.","",false);
        Reply reply=generator.generate(selected,mapper.writeValueAsString(Map.of("question",input.question(),"wikiPages",content.pages())));
        if(!fingerprint.equals(refresh(s)))throw new ResponseStatusException(CONFLICT,"Dokumentacja zmieniła się. Zadaj pytanie ponownie.");
        authorize(s);audit.record(s.actor(),s.patient(),"KNOWLEDGE",s.id(),"VIEW",true,null,Map.of("purpose","QUESTION"));return reply;
    }
    private Content content(Scope s){return mapper.readValue(db.sql("SELECT content_json FROM patient_knowledge WHERE id=:id").param("id",s.id()).query(String.class).single(),Content.class);}
    private String refresh(Scope s){
        authorize(s);List<Source> sources=sources(s);String fingerprint=hash(mapper.writeValueAsString(sources)+"knowledge-v1");
        int changed=db.sql("UPDATE patient_knowledge SET fingerprint=:fp,content_json=:content,status='QUEUED',attempts=0,error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND fingerprint<>:fp")
            .param("id",s.id()).param("fp",fingerprint).param("content",mapper.writeValueAsString(new Content(sources,List.of()))).update();
        if(changed>0){
            // Purge removed/revised source material, including previously generated citations.
            for(var row:db.sql("SELECT source_id,version FROM patient_knowledge_pages WHERE scope_id=:id").param("id",s.id()).query((rs,n)->new String[]{rs.getString(1),rs.getString(2)}).list()){
                if(sources.stream().noneMatch(x->x.id().equals(row[0])&&x.version().equals(row[1])))db.sql("DELETE FROM patient_knowledge_pages WHERE scope_id=:id AND source_id=:source").param("id",s.id()).param("source",row[0]).update();
            }
        }return fingerprint;
    }
    private List<Source> sources(Scope s){
        List<Source> result=new ArrayList<>();
        String condition=s.visit()==null?"s.patient_id=:patient":"EXISTS(SELECT 1 FROM visit_studies vs WHERE vs.study_id=s.id AND vs.visit_id=:visit) AND s.patient_id=:patient";
        if(!s.owner())condition+=" AND EXISTS(SELECT 1 FROM visit_packages v JOIN access_grant_studies gs ON gs.grant_id=v.grant_id WHERE v.id=:visit AND gs.study_id=s.id)";
        var rows=db.sql("""
            SELECT s.id,s.title,s.performed_at,s.updated_at,s.metadata_revision,t.source_revision,t.bucket_name,t.object_key FROM studies s
            LEFT JOIN LATERAL (SELECT so.bucket_name,so.object_key,so.id::text || COALESCE(so.sha256,'') AS source_revision FROM study_assets sa JOIN storage_objects so ON so.id=sa.storage_object_id
            WHERE sa.study_id=s.id AND sa.asset_type='EXTRACTED_TEXT' AND so.storage_status='STORED' ORDER BY sa.created_at DESC,sa.id DESC LIMIT 1) t ON TRUE
            WHERE s.archived_at IS NULL AND
            """+condition+" ORDER BY s.performed_at DESC NULLS LAST,s.id")
            .param("patient",s.patient()).param("visit",s.visit(),java.sql.Types.OTHER).query((rs,n)->new String[]{rs.getString("id"),rs.getString("title"),rs.getString("bucket_name"),rs.getString("object_key"),rs.getString("performed_at"),rs.getString("updated_at")+rs.getString("metadata_revision")+rs.getString("source_revision")}).list();
        int budget=60000;
        for(var row:rows){
            String text="",limitation="";
            if(row[2]==null)limitation="Brak odczytanego tekstu; dokument pominięty. Może wymagać OCR.";
            else if(budget<=0)limitation="Pominięto treść z powodu limitu kontekstu.";
            else try(var in=storage.get(row[2],row[3])){
                byte[] bytes=in.readNBytes(240001);String full=new String(bytes,StandardCharsets.UTF_8).trim();
                int max=Math.min(12000,budget);text=full;
                if(full.length()>max){int end=full.lastIndexOf('\n',max);text=end>0?full.substring(0,end):"";limitation="Analizowano tylko początek tekstu z powodu limitu kontekstu.";}
                if(text.isBlank()&&limitation.isBlank())limitation="Brak odczytanej treści.";
                budget-=text.length();
            }catch(Exception e){throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Nie udało się odczytać źródeł. Spróbuj ponownie.");}
            result.add(new Source("document:"+row[0],"DOCUMENT",UUID.fromString(row[0]),row[1],text,hash(row[1]+row[4]+row[5]+text+limitation),limitation));
        }
        var visits=db.sql("SELECT id,reason,summary,visit_date,previous_visit_id,include_previous_plan FROM visit_packages WHERE patient_id=:patient AND approved_at IS NOT NULL"+(s.visit()==null?"":" AND id=:visit")+" ORDER BY visit_date DESC NULLS LAST,id")
            .param("patient",s.patient()).param("visit",s.visit(),java.sql.Types.OTHER).query((rs,n)->new String[]{rs.getString("id"),rs.getString("reason"),rs.getString("summary"),rs.getString("visit_date"),rs.getString("previous_visit_id"),rs.getString("include_previous_plan")}).list();
        for(var row:visits){addVisit(result,UUID.fromString(row[0]),UUID.fromString(row[0]),row[1],row[2],row[3]);}
        // A previous approved plan is available through the current package only when explicitly included.
        if(s.visit()!=null){
            var previous=db.sql("SELECT p.id,p.reason,p.summary,p.visit_date FROM visit_packages v JOIN visit_packages p ON p.id=v.previous_visit_id AND p.patient_id=v.patient_id WHERE v.id=:visit AND v.include_previous_plan=TRUE AND p.approved_at IS NOT NULL")
                .param("visit",s.visit()).query((rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)}).list();
            for(var row:previous)addVisit(result,UUID.fromString(row[0]),s.visit(),"Poprzedni zatwierdzony plan",row[2],row[3]);
        }
        // Bound the combined visit/document context too; omitted sources remain visible in the coverage list.
        int total=0;List<Source> bounded=new ArrayList<>();
        for(Source x:result){if(total+x.text().length()>80000)bounded.add(new Source(x.id(),x.kind(),x.targetId(),x.title(),"",x.version(),"Pominięto treść z powodu limitu kontekstu."));else{bounded.add(x);total+=x.text().length();}}
        return bounded;
    }
    private void addVisit(List<Source> result,UUID id,UUID target,String title,String summary,String date){
        StringBuilder text=new StringBuilder("Data wizyty: "+Objects.toString(date,"brak")+"\n"+summary+"\n");
        db.sql("SELECT description,due_date,responsible,patient_status,patient_note,performed_on FROM visit_actions WHERE visit_id=:id ORDER BY position,id").param("id",id)
            .query((rs,n)->"Zalecenie lekarza: "+rs.getString(1)+"; termin: "+rs.getString(2)+"; odpowiedzialny: "+rs.getString(3)+"\nDeklaracja pacjenta: "+rs.getString(4)+"; data wykonania: "+Objects.toString(rs.getString(6),"brak")+"; uwagi: "+rs.getString(5)+"\n").list().forEach(text::append);
        result.add(new Source("visit:"+id,"VISIT",target,title,text.toString(),hash(text.toString()),""));
    }
    public synchronized void tick(){
        if(!enabled)return;
        var scopes=db.sql("SELECT id,actor_id,patient_id,visit_id,owner_view FROM patient_knowledge ORDER BY checked_at LIMIT 100")
            .query((rs,n)->new Scope(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getBoolean(5))).list();
        for(Scope s:scopes){
            try{refresh(s);}catch(ResponseStatusException e){if(e.getStatusCode().value()==404)invalidate(s);}
            finally{db.sql("UPDATE patient_knowledge SET checked_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",s.id()).update();}
        }
        db.sql("UPDATE patient_knowledge SET status=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,error_message='Przerwano przygotowanie. Możesz spróbować ponownie.' WHERE status='GENERATING' AND started_at<CURRENT_TIMESTAMP-INTERVAL '15 minutes'").update();
        for(Scope s:scopes){
            if(db.sql("UPDATE patient_knowledge SET status='GENERATING',attempts=attempts+1,started_at=CURRENT_TIMESTAMP WHERE id=:id AND status='QUEUED' AND attempts<3").param("id",s.id()).update()==0)continue;
            String fp=db.sql("SELECT fingerprint FROM patient_knowledge WHERE id=:id").param("id",s.id()).query(String.class).single();
            try{
                Content c=content(s);List<Page> pages=new ArrayList<>();List<Point> all=new ArrayList<>();
                for(Source source:c.sources()){
                    if(source.text().isBlank())continue;
                    var cached=db.sql("SELECT points_json FROM patient_knowledge_pages WHERE scope_id=:id AND source_id=:source AND version=:version")
                        .param("id",s.id()).param("source",source.id()).param("version",source.version()).query(String.class).optional();
                    List<Point> points=cached.isPresent()?Arrays.asList(mapper.readValue(cached.get(),Point[].class)):generator.generate(List.of(source),"Wybierz najważniejsze fakty, zapisane zalecenia i deklaracje pacjenta.").points();
                    if(cached.isEmpty())db.sql("INSERT INTO patient_knowledge_pages(scope_id,source_id,version,points_json) VALUES(:id,:source,:version,:points) ON CONFLICT(scope_id,source_id) DO UPDATE SET version=EXCLUDED.version,points_json=EXCLUDED.points_json")
                        .param("id",s.id()).param("source",source.id()).param("version",source.version()).param("points",mapper.writeValueAsString(points)).update();
                    pages.add(new Page(source.id(),source.title(),points));all.addAll(points);
                }
                // Persist a cross-source synthesis as well as source pages; no unsupported paraphrases.
                if(c.sources().stream().filter(x->!x.text().isBlank()).count()>1)all=new ArrayList<>(generator.generate(c.sources(),"Wybierz najważniejsze informacje z całej dokumentacji; pokaż obie wersje ewentualnych rozbieżności.").points());
                pages.addFirst(new Page("overview","Najważniejsze informacje",all));
                for(String kind:List.of("RECOMMENDATION","DECLARATION","DISCREPANCY")){
                    var related=pages.stream().filter(p->!p.id().equals("overview")).flatMap(p->p.points().stream()).filter(p->p.kind().equals(kind)).distinct().toList();
                    if(!related.isEmpty())pages.add(new Page("topic:"+kind,switch(kind){case "RECOMMENDATION"->"Zapisane zalecenia";case "DECLARATION"->"Deklaracje pacjenta";default->"Możliwe rozbieżności";},related));
                }
                if(!fp.equals(refresh(s)))continue;authorize(s);
                db.sql("UPDATE patient_knowledge SET status='READY',content_json=:content,updated_at=CURRENT_TIMESTAMP,error_message=NULL WHERE id=:id AND fingerprint=:fp AND status='GENERATING'")
                    .param("id",s.id()).param("fp",fp).param("content",mapper.writeValueAsString(new Content(c.sources(),pages))).update();
            }catch(Exception e){
                db.sql("UPDATE patient_knowledge SET status=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,error_message='Nie udało się przygotować zweryfikowanego podsumowania.' WHERE id=:id AND fingerprint=:fp AND status='GENERATING'")
                    .param("id",s.id()).param("fp",fp).update();
            }
            break;
        }
    }
    private void invalidate(Scope s){
        db.sql("UPDATE patient_knowledge SET status='INVALIDATED',content_json=:empty,fingerprint='' WHERE id=:id").param("id",s.id()).param("empty",mapper.writeValueAsString(new Content(List.of(),List.of()))).update();
        db.sql("DELETE FROM patient_knowledge_pages WHERE scope_id=:id").param("id",s.id()).update();
    }
    static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
