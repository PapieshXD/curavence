package com.ralek.backend.workspace;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.storage.ObjectStorageService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class DocumentLibraryService {
    private final JdbcClient jdbc; private final UserContextService contexts;
    private final WorkspaceService workspace; private final ObjectStorageService storage; private final AuditService audit;
    @Value("${ralek.search.enabled:true}") boolean enabled=true;
    @Value("${ralek.search.worker-enabled:true}") boolean workerEnabled=true;
    public DocumentLibraryService(JdbcClient jdbc,UserContextService contexts,WorkspaceService workspace,ObjectStorageService storage,AuditService audit) {
        this.jdbc=jdbc;this.contexts=contexts;this.workspace=workspace;this.storage=storage;this.audit=audit;
    }
    public boolean isEnabled(){return enabled;}
    public record Item(UUID id,String title,Instant performedAt,String typeCode,String typeName,String sourceName,
        String originalFilename,boolean hasExtractedText,String indexStatus,String snippet,Instant archivedAt,long metadataRevision,
        UUID primaryAssetId,UUID imagingStudyId,String ingestionStatus) {}
    public record Page(List<Item> items,long total,int page,int pageSize) {}
    public record Reader(UUID grantId,String name,String email,String scope) {}
    public record RestorePreview(long revision,List<Reader> readers) {}
    public record History(String author,Instant createdAt,String oldTitle,String newTitle,Instant oldPerformedAt,Instant newPerformedAt,String oldTypeCode,String newTypeCode) {}
    static Instant instant(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        return rs.getTimestamp(column)==null?null:rs.getTimestamp(column).toInstant();
    }
    public Page search(Jwt jwt,String role,String query,String category,LocalDate from,LocalDate to,String text,int page,int size,boolean archived) {
        if(!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Wyszukiwanie jest wyłączone");
        var actor=contexts.resolve(jwt,role);
        if(page<0 || size<1 || size>100 || query.length()>200 || (from!=null && to!=null && from.isAfter(to)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Sprawdź filtry wyszukiwania");
        if(archived && (!actor.patientView() || actor.patientId()==null)) forbidden();
        String categorySql=switch(category) {
            case "all" -> "TRUE";
            case "laboratory" -> "st.code='LABORATORY'";
            case "imaging" -> "(i.id IS NOT NULL OR st.code IN ('MRI','CT','XRAY','ULTRASOUND'))";
            case "ecg" -> "st.code IN ('EKG','ECG','EEG')";
            case "documents" -> "(i.id IS NULL AND st.code NOT IN ('LABORATORY','MRI','CT','XRAY','ULTRASOUND','EKG','ECG','EEG'))";
            default -> "st.code=:category";
        };
        if(!Set.of("all","yes","no").contains(text)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Nieprawidłowy filtr tekstu");
        String joins="""
            FROM studies s JOIN study_types st ON st.id=s.study_type_id
            LEFT JOIN imaging_studies i ON i.id=s.id LEFT JOIN document_search_text dt ON dt.study_id=s.id
            LEFT JOIN LATERAL (SELECT sa.id,so.original_filename FROM study_assets sa JOIN storage_objects so ON so.id=sa.storage_object_id
                WHERE sa.study_id=s.id AND sa.asset_type='ORIGINAL' AND so.storage_status='STORED' ORDER BY sa.is_primary DESC,sa.created_at DESC,sa.id LIMIT 1) a ON TRUE
            WHERE
            """+(archived?"s.archived_at IS NOT NULL AND s.patient_id=:patient":"s.archived_at IS NULL AND "+workspace.studyVisibility(actor,"s"))+
            " AND "+categorySql+" AND (CAST(:fromDate AS timestamptz) IS NULL OR s.performed_at>=CAST(:fromDate AS timestamptz)) AND (CAST(:toDate AS timestamptz) IS NULL OR s.performed_at<CAST(:toDate AS timestamptz)) "+
            " AND (:textFilter='all' OR (:textFilter='yes' AND dt.status='READY' AND dt.content<>'') OR (:textFilter='no' AND COALESCE(dt.content,'')='')) "+
            " AND (:q='' OR lower(concat_ws(' ',s.title,s.source_name,a.original_filename,CASE WHEN dt.status='READY' THEN dt.content ELSE '' END)) LIKE :likeQuery ESCAPE '!')";
        String q=query.trim().toLowerCase(Locale.ROOT);
        java.util.function.Function<String,JdbcClient.StatementSpec> statement=sql->jdbc.sql(sql).param("actorId",actor.id())
            .param("patientId",actor.patientId(),java.sql.Types.OTHER).param("patient",actor.patientId(),java.sql.Types.OTHER)
            .param("category",category).param("q",q).param("textFilter",text)
            .param("likeQuery","%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%")
            .param("fromDate",from==null?null:from.atStartOfDay(ZoneId.of("Europe/Warsaw")).toOffsetDateTime(),java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            .param("toDate",to==null?null:to.plusDays(1).atStartOfDay(ZoneId.of("Europe/Warsaw")).toOffsetDateTime(),java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        long total=statement.apply("SELECT count(*) "+joins).query(Long.class).single();
        var items=statement.apply("""
            SELECT s.*,st.code,st.name AS type_name,a.id AS asset_id,a.original_filename,i.id AS imaging_id,
              COALESCE(dt.status,'NONE') AS index_status,COALESCE(dt.content,'') AS search_content
            """+joins+" ORDER BY s.performed_at DESC NULLS LAST,s.created_at DESC,s.id LIMIT :limit OFFSET :offset")
            .param("limit",size).param("offset",(long)page*size).query((rs,n)->new Item(rs.getObject("id",UUID.class),rs.getString("title"),instant(rs,"performed_at"),
                rs.getString("code"),rs.getString("type_name"),rs.getString("source_name"),rs.getString("original_filename"),!rs.getString("search_content").isBlank(),
                rs.getString("index_status"),snippet(rs.getString("search_content"),q),instant(rs,"archived_at"),rs.getLong("metadata_revision"),
                archived?null:rs.getObject("asset_id",UUID.class),rs.getObject("imaging_id",UUID.class),rs.getString("ingestion_status"))).list();
        return new Page(items,total,page,size);
    }
    static String snippet(String content,String query) {
        if(content.isBlank() || query.isBlank()) return "";
        int index=content.toLowerCase(Locale.ROOT).indexOf(query);
        if(index<0)return "";
        int from=Math.max(0,index-70),to=Math.min(content.length(),index+query.length()+120);
        if(from>0 && Character.isLowSurrogate(content.charAt(from)))from--;
        if(to<content.length() && Character.isLowSurrogate(content.charAt(to)))to++;
        return (from>0?"…":"")+content.substring(from,to)+(to<content.length()?"…":"");
    }
    public void index(UUID study,String content) {
        jdbc.sql("""
            INSERT INTO document_search_text(study_id,source_asset_id,content,status)
            VALUES (:id,(SELECT id FROM study_assets WHERE study_id=:id AND asset_type='EXTRACTED_TEXT' ORDER BY created_at DESC,id LIMIT 1),:text,'READY')
            ON CONFLICT (study_id) DO UPDATE SET source_asset_id=EXCLUDED.source_asset_id,content=EXCLUDED.content,status='READY',updated_at=CURRENT_TIMESTAMP
            """).param("id",study).param("text",content).update();
    }
    @Scheduled(fixedDelay=60000,initialDelay=15000)
    public void backfill() {
        if(!enabled || !workerEnabled)return;
        var pending=jdbc.sql("""
            SELECT d.study_id,so.bucket_name,so.object_key FROM document_search_text d
            JOIN study_assets sa ON sa.id=d.source_asset_id JOIN storage_objects so ON so.id=sa.storage_object_id
            JOIN studies s ON s.id=d.study_id WHERE (d.status='PENDING' OR (d.status='FAILED' AND d.updated_at<CURRENT_TIMESTAMP-INTERVAL '15 minutes')) AND s.archived_at IS NULL AND so.storage_status='STORED'
            ORDER BY d.study_id LIMIT 20
            """).query((rs,n)->new String[]{rs.getString("study_id"),rs.getString("bucket_name"),rs.getString("object_key")}).list();
        for(var row:pending)try(var stream=storage.get(row[1],row[2])) {
            byte[] bytes=stream.readNBytes(400001);if(bytes.length>400000)throw new java.io.IOException("Text limit");
            index(UUID.fromString(row[0]),new String(bytes,StandardCharsets.UTF_8));
        } catch(Exception error) {
            jdbc.sql("UPDATE document_search_text SET status='FAILED',updated_at=CURRENT_TIMESTAMP WHERE study_id=:id AND status IN ('PENDING','FAILED')")
                .param("id",UUID.fromString(row[0])).update();
        }
    }
    public List<UUID> duplicates(UUID study) {
        return jdbc.sql("""
            SELECT DISTINCT other.id FROM studies s JOIN study_assets sa ON sa.study_id=s.id AND sa.asset_type='ORIGINAL'
            JOIN storage_objects so ON so.id=sa.storage_object_id AND so.storage_status='STORED'
            JOIN storage_objects duplicate ON duplicate.sha256=so.sha256 AND duplicate.storage_status='STORED'
            JOIN study_assets da ON da.storage_object_id=duplicate.id AND da.asset_type='ORIGINAL'
            JOIN studies other ON other.id=da.study_id AND other.patient_id=s.patient_id
            WHERE s.id=:id AND other.id<>s.id AND other.archived_at IS NULL AND so.sha256 IS NOT NULL ORDER BY other.id
            """).param("id",study).query(UUID.class).list();
    }
    private void forbidden() {throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Brak dostępu do dokumentu");}
    private void requireOwner(UserContext actor,UUID id,boolean archived) {
        if(!actor.patientView() || actor.patientId()==null || jdbc.sql("SELECT count(*) FROM studies WHERE id=:id AND patient_id=:patient AND archived_at IS "+(archived?"NOT NULL":"NULL"))
            .param("id",id).param("patient",actor.patientId()).query(Integer.class).single()!=1)forbidden();
    }
    public List<History> history(Jwt jwt,String role,UUID id) {
        var actor=contexts.resolve(jwt,role);requireOwner(actor,id,false);
        return jdbc.sql("""
            SELECT h.*,u.first_name || ' ' || u.last_name AS author FROM document_metadata_history h JOIN users u ON u.id=h.actor_id
            WHERE h.study_id=:id ORDER BY h.created_at DESC,h.id
            """).param("id",id).query((rs,n)->new History(rs.getString("author"),instant(rs,"created_at"),rs.getString("old_title"),rs.getString("new_title"),
                instant(rs,"old_performed_at"),instant(rs,"new_performed_at"),rs.getString("old_type_code"),rs.getString("new_type_code"))).list();
    }
    public RestorePreview preview(Jwt jwt,String role,UUID id) {
        var actor=contexts.resolve(jwt,role);requireOwner(actor,id,true);
        var readers=jdbc.sql("""
            SELECT ag.id,u.first_name || ' ' || u.last_name AS name,u.email,ag.scope FROM studies s
            JOIN access_grants ag ON ag.patient_id=s.patient_id JOIN users u ON u.id=ag.recipient_user_id
            WHERE s.id=:id AND ag.status='ACTIVE' AND (ag.expires_at IS NULL OR ag.expires_at>CURRENT_TIMESTAMP) AND
            """+scope("s")).param("id",id).query((rs,n)->new Reader(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("email"),rs.getString("scope"))).list();
        long revision=jdbc.sql("SELECT metadata_revision FROM studies WHERE id=:id").param("id",id).query(Long.class).single();
        return new RestorePreview(revision,readers);
    }
    // Same scope categories as WorkspaceService; archived state is intentionally handled by the owner-only caller.
    private String scope(String s) {
        return "(ag.scope='FULL_STUDY' OR (ag.scope IN ('SELECTED_STUDIES','REHABILITATION_SUMMARY') AND EXISTS (SELECT 1 FROM access_grant_studies gs WHERE gs.grant_id=ag.id AND gs.study_id="+s+".id))"
            +" OR (ag.scope='LAB_RESULTS' AND (EXISTS(SELECT 1 FROM study_types st WHERE st.id="+s+".study_type_id AND st.code='LABORATORY') OR (jsonb_exists("+s+".metadata,'clinicalResult') AND COALESCE("+s+".metadata->'clinicalResult'->>'presentation','parameters')='parameters')))"
            +" OR (ag.scope='DIAGNOSTIC_REPORT' AND ("+s+".metadata->'clinicalResult'->>'presentation'='report' OR EXISTS(SELECT 1 FROM clinical_reviews cr WHERE cr.study_id="+s+".id))))";
    }
    @Transactional
    public void restore(Jwt jwt,String role,UUID id,long revision,List<UUID> grantIds,boolean confirmed) {
        var actor=contexts.resolve(jwt,role);
        jdbc.sql("SELECT id FROM studies WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireOwner(actor,id,true);var preview=preview(jwt,role,id);
        if(!confirmed || preview.revision()!=revision || !new HashSet<>(preview.readers().stream().map(Reader::grantId).toList()).equals(new HashSet<>(grantIds)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Zakres dostępu zmienił się. Sprawdź go ponownie.");
        int original=jdbc.sql("SELECT count(*) FROM study_assets sa JOIN storage_objects so ON so.id=sa.storage_object_id WHERE sa.study_id=:id AND sa.asset_type='ORIGINAL' AND so.storage_status='STORED'")
            .param("id",id).query(Integer.class).single();
        if(original==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Brak zachowanego oryginału. Nie można przywrócić dokumentu.");
        jdbc.sql("UPDATE studies SET ingestion_status=COALESCE(archived_ingestion_status,'READY'),archived_at=NULL,archived_ingestion_status=NULL,metadata_revision=metadata_revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("id",id).update();
        audit.record(actor.id(),actor.patientId(),"STUDY",id,"UPDATE",true,null,Map.of("operation","RESTORE"));
    }
}
