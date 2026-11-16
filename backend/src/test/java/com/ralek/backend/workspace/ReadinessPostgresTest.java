package com.ralek.backend.workspace;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.storage.ObjectStorageService;
import io.minio.GetObjectResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadinessPostgresTest {
    String schema="readiness_test_"+UUID.randomUUID().toString().replace("-","");
    com.zaxxer.hikari.HikariDataSource source;JdbcClient jdbc;TransactionTemplate tx;
    UserContextService contexts;ObjectStorageService storage;AuditService audit;WorkspaceService workspace;
    DocumentLibraryService library;PatientExportService exports;Jwt jwt=mock(Jwt.class);
    UUID patientUser=UUID.fromString("10000000-0000-0000-0000-000000000002"),doctor=UUID.fromString("10000000-0000-0000-0000-000000000003"),patient,other;
    Map<String,byte[]> files;String token;
    String url(){return System.getenv("TEST_POSTGRES_URL");}
    String username(){return System.getenv().getOrDefault("TEST_POSTGRES_USER","med_node");}
    String password(){return System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","med_node_dev");}
    @BeforeAll void database() {
        org.flywaydb.core.Flyway.configure().dataSource(url(),username(),password()).schemas(schema).defaultSchema(schema).load().migrate();
        source=new com.zaxxer.hikari.HikariDataSource();source.setJdbcUrl(url()+(url().contains("?")?"&":"?")+"currentSchema="+schema);source.setUsername(username());source.setPassword(password());source.setMaximumPoolSize(5);
        jdbc=JdbcClient.create(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        patient=jdbc.sql("SELECT id FROM patients WHERE user_id=:id").param("id",patientUser).query(UUID.class).single();
        other=jdbc.sql("SELECT id FROM patients WHERE id<>:id LIMIT 1").param("id",patient).query(UUID.class).single();
    }
    @AfterAll void cleanup() throws Exception {if(source!=null)source.close();try(var c=DriverManager.getConnection(url(),username(),password())){c.createStatement().execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}}
    @BeforeEach void setup() throws Exception {
        jdbc.sql("UPDATE access_grants SET status='REVOKED'").update();
        token=UUID.randomUUID().toString();files=new ConcurrentHashMap<>();contexts=mock(UserContextService.class);storage=mock(ObjectStorageService.class);audit=mock(AuditService.class);
        when(contexts.resolve(jwt,"patient")).thenReturn(context(patientUser,patient,"patient"));when(contexts.resolve(jwt,"doctor")).thenReturn(context(doctor,null,"doctor"));
        when(storage.get(anyString(),anyString())).thenAnswer(inv->{byte[] bytes=files.get(inv.getArgument(1));if(bytes==null)throw new IllegalStateException("Missing object");return new GetObjectResponse(new okhttp3.Headers.Builder().build(),inv.getArgument(0),null,inv.getArgument(1),new ByteArrayInputStream(bytes));});
        when(storage.putDerived(anyString(),anyString(),anyLong(),any())).thenAnswer(inv->{files.put(inv.getArgument(0),((InputStream)inv.getArgument(3)).readAllBytes());return new ObjectStorageService.StoredObject("derived","sha");});
        when(storage.derivedBucket()).thenReturn("derived");
        doAnswer(inv->{files.remove(inv.getArgument(1));return null;}).when(storage).remove(anyString(),anyString());
        workspace=new WorkspaceService(jdbc,contexts,audit,storage);library=new DocumentLibraryService(jdbc,contexts,workspace,storage,audit);exports=new PatientExportService(jdbc,contexts,storage,audit,new ObjectMapper());
    }
    @AfterEach void stop(){exports.close();}
    UserContextService.UserContext context(UUID user,UUID p,String role){return new UserContextService.UserContext(user,"test@example.test","Jan","Testowy",Set.of(role.equals("patient")?"PATIENT":"DOCTOR"),List.of(role),false,role,p);}
    UUID document(UUID owner,String text,String filename) {
        UUID id=UUID.randomUUID(),object=UUID.randomUUID(),asset=UUID.randomUUID();byte[] bytes=("Original "+token).getBytes(StandardCharsets.UTF_8);String key="test/"+id;files.put(key,bytes);
        jdbc.sql("INSERT INTO studies(id,patient_id,study_type_id,created_by,title,ingestion_status,clinical_status,performed_at) VALUES (:id,:patient,(SELECT id FROM study_types WHERE code='MEDICAL_DOCUMENT'),:actor,:title,'READY','DRAFT',CURRENT_TIMESTAMP)")
            .param("id",id).param("patient",owner).param("actor",patientUser).param("title","Dokument "+token).update();
        jdbc.sql("INSERT INTO storage_objects(id,provider,bucket_name,object_key,original_filename,mime_type,size_bytes,sha256,storage_status) VALUES (:id,'MINIO','original',:key,:filename,'application/pdf',:size,:sha,'STORED')")
            .param("id",object).param("key",key).param("filename",filename).param("size",bytes.length).param("sha",sha(bytes)).update();
        jdbc.sql("INSERT INTO study_assets(id,study_id,storage_object_id,asset_type,is_primary) VALUES (:id,:study,:object,'ORIGINAL',TRUE)")
            .param("id",asset).param("study",id).param("object",object).update();
        if(text!=null)library.index(id,text);
        return id;
    }
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new RuntimeException(e);}}
    UUID grant(UUID study){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO access_grants(id,patient_id,recipient_user_id,granted_by,scope,status,expires_at) VALUES (:id,:patient,:doctor,:actor,'SELECTED_STUDIES','ACTIVE',CURRENT_TIMESTAMP+INTERVAL '1 day')")
        .param("id",id).param("patient",patient).param("doctor",doctor).param("actor",patientUser).update();jdbc.sql("INSERT INTO access_grant_studies VALUES (:id,:study)").param("id",id).param("study",study).update();return id;}
    DocumentLibraryService.Page search(String role,String query){return library.search(jwt,role,query,"all",null,null,"all",0,25,false);}
    PatientExportService.Request request(List<UUID> ids,String format){return new PatientExportService.Request(UUID.randomUUID(),null,ids,false,false,false,format,true);}
    @Test void searchFindsBodyAndDoesNotLeakOtherPatientsOrRevokedDocuments() {
        UUID own=document(patient,"Unikalny fragment "+token,"own.pdf"),foreign=document(other,"Unikalny fragment "+token,"foreign.pdf");
        assertEquals(1,search("patient","Unikalny fragment "+token).total());assertEquals(own,search("patient",token).items().getFirst().id());
        assertEquals(0,search("doctor",token).total());UUID grant=grant(own);assertEquals(1,search("doctor",token).total());
        jdbc.sql("UPDATE access_grants SET status='REVOKED' WHERE id=:id").param("id",grant).update();assertEquals(0,search("doctor",token).total());
        assertThrows(ResponseStatusException.class,()->exports.create(jwt,"patient",request(List.of(foreign),"PDF")));
    }
    @Test void searchEscapesWildcardsFiltersDatesAndPaginates() {
        UUID id=document(patient,"Próba 100% "+token,"source.pdf");
        assertEquals(1,search("patient","100% "+token).total());assertEquals(0,search("patient","100_ "+token).total());
        assertEquals(0,library.search(jwt,"patient",token,"all",LocalDate.now().plusDays(2),null,"all",0,25,false).total());
        document(patient,null,"no-text.pdf");assertEquals(1,library.search(jwt,"patient",token,"all",null,null,"yes",0,25,false).total());
        assertEquals(1,library.search(jwt,"patient",token,"all",null,null,"no",0,25,false).total());
        assertEquals(1,library.search(jwt,"patient",token,"all",null,null,"all",1,1,false).items().size());
    }
    @Test void duplicateCheckIsLimitedToTheSamePatient() {
        UUID first=document(patient,null,"a.pdf"),second=document(patient,null,"b.pdf"),foreign=document(other,null,"a.pdf");
        assertEquals(List.of(second),library.duplicates(first));assertFalse(library.duplicates(first).contains(foreign));
    }
    @Test void metadataChangesHaveHistoryAndProtectAgainstStaleWriters() {
        UUID id=document(patient,"Treść","source.pdf");var input=new WorkspaceDtos.UpdateDocumentRequest("Nowa nazwa",null,"LABORATORY",0L);
        tx.executeWithoutResult(t->workspace.updateDocument(jwt,"patient",id,input));
        assertEquals(1,library.history(jwt,"patient",id).size());assertEquals("MEDICAL_DOCUMENT",library.history(jwt,"patient",id).getFirst().oldTypeCode());
        assertThrows(ResponseStatusException.class,()->tx.executeWithoutResult(t->workspace.updateDocument(jwt,"patient",id,input)));
        assertThrows(ResponseStatusException.class,()->tx.executeWithoutResult(t->workspace.updateDocument(jwt,"patient",id,new WorkspaceDtos.UpdateDocumentRequest("Test",null,"EKG",1L))));
        verify(storage,never()).putOriginal(anyString(),anyString(),anyLong(),any());
    }
    @Test void restorationRequiresCurrentGrantPreviewAndDoesNotReprocess() {
        UUID id=document(patient,"Treść","source.pdf");workspace.archiveDocument(jwt,"patient",id);
        var preview=library.preview(jwt,"patient",id);UUID grant=grant(id);
        assertThrows(ResponseStatusException.class,()->tx.executeWithoutResult(t->library.restore(jwt,"patient",id,preview.revision(),List.of(),true)));
        var updated=library.preview(jwt,"patient",id);assertEquals(grant,updated.readers().getFirst().grantId());
        tx.executeWithoutResult(t->library.restore(jwt,"patient",id,updated.revision(),List.of(grant),true));
        assertEquals("READY",jdbc.sql("SELECT ingestion_status FROM studies WHERE id=:id").param("id",id).query(String.class).single());
        assertEquals(1,search("doctor",token).total());verify(storage,never()).get(anyString(),anyString());verify(storage,never()).putDerived(anyString(),anyString(),anyLong(),any());
    }
    @Test void exportsAreIdempotentPatientOnlyAndInvalidateAfterSourceChanges() throws Exception {
        UUID id=document(patient,"Treść","source.pdf");var input=request(List.of(id),"PDF");
        var job=tx.execute(t->exports.create(jwt,"patient",input));assertEquals(job.id(),exports.create(jwt,"patient",input).id());
        assertThrows(ResponseStatusException.class,()->exports.create(jwt,"doctor",input));
        exports.runNext();assertEquals("READY",exports.status(jwt,"patient",job.id()).status());
        try(var download=exports.download(jwt,"patient",job.id()).stream()){assertTrue(download.readAllBytes().length>1000);}
        tx.executeWithoutResult(t->workspace.updateDocument(jwt,"patient",id,new WorkspaceDtos.UpdateDocumentRequest("Zmieniony",null,"MEDICAL_DOCUMENT",0L)));
        assertThrows(ResponseStatusException.class,()->exports.download(jwt,"patient",job.id()));assertEquals("INVALIDATED",exports.status(jwt,"patient",job.id()).status());
    }
    @Test void zipPreservesBytesAndUsesUniqueSafeNames() throws Exception {
        UUID first=document(patient,null,"../../wynik.pdf"),second=document(patient,null,"../../wynik.pdf");
        var job=exports.create(jwt,"patient",request(List.of(first,second),"ZIP"));exports.runNext();
        var names=new HashSet<String>();int originals=0;
        try(var zip=new ZipInputStream(exports.download(jwt,"patient",job.id()).stream(),StandardCharsets.UTF_8)){
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertTrue(names.add(entry.getName()));assertFalse(entry.getName().contains(".."));byte[] bytes=zip.readAllBytes();if(entry.getName().startsWith("oryginaly/")){originals++;assertArrayEquals(("Original "+token).getBytes(StandardCharsets.UTF_8),bytes);}}
        }
        assertEquals(2,originals);assertTrue(names.contains("podsumowanie.pdf"));assertTrue(names.contains("spis.txt"));
    }
    @Test void missingOriginalAndExcessiveSelectionNeverProduceACompleteArchive() {
        UUID id=document(patient,null,"source.pdf");var job=exports.create(jwt,"patient",request(List.of(id),"ZIP"));files.clear();exports.runNext();
        assertEquals("FAILED",exports.status(jwt,"patient",job.id()).status());assertThrows(ResponseStatusException.class,()->exports.download(jwt,"patient",job.id()));
        assertThrows(ResponseStatusException.class,()->exports.create(jwt,"patient",request(Collections.nCopies(51,id),"PDF")));
        jdbc.sql("UPDATE storage_objects SET size_bytes=:size WHERE object_key=:key").param("size",PatientExportService.MAX_BYTES+1).param("key","test/"+id).update();
        assertThrows(ResponseStatusException.class,()->exports.create(jwt,"patient",request(List.of(id),"ZIP")));
    }
    @Test void expiredExportIsDeletedAndCannotBeDownloaded() {
        UUID id=document(patient,null,"source.pdf");var job=exports.create(jwt,"patient",request(List.of(id),"PDF"));exports.runNext();
        jdbc.sql("UPDATE patient_exports SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=:id").param("id",job.id()).update();exports.cleanup();
        assertEquals("EXPIRED",exports.status(jwt,"patient",job.id()).status());assertThrows(ResponseStatusException.class,()->exports.download(jwt,"patient",job.id()));verify(storage).remove(eq("derived"),contains(job.id().toString()));
    }
    @Test void ambiguousStorageFailureDoesNotLeaveAnUntrackedPrivateCopy() throws Exception {
        UUID id=document(patient,null,"source.pdf");var job=exports.create(jwt,"patient",request(List.of(id),"PDF"));
        doAnswer(inv->{files.put(inv.getArgument(0),((InputStream)inv.getArgument(3)).readAllBytes());throw new ObjectStorageService.StorageException("Simulated response loss",null);}).when(storage).putDerived(anyString(),anyString(),anyLong(),any());
        exports.runNext();assertEquals("FAILED",exports.status(jwt,"patient",job.id()).status());
        assertTrue(files.keySet().stream().noneMatch(key->key.startsWith("exports/")));
        verify(storage).remove(eq("derived"),contains(job.id().toString()));
    }
    @Test void pdfWrapsPolishTextAcrossPagesWithoutIncludingDraftPlan() throws Exception {
        UUID visit=UUID.randomUUID();jdbc.sql("INSERT INTO visit_packages(id,patient_id,reason,symptoms,questions,summary) VALUES (:id,:patient,'Kontrola',:text,'Czy dobrze rozumiem plan?','TAJNY SZKIC LEKARZA')")
            .param("id",visit).param("patient",patient).param("text","Żółć, źródło, gęślą jaźń. ".repeat(350)).update();
        var input=new PatientExportService.Request(UUID.randomUUID(),visit,List.of(),true,true,false,"PDF",true);
        var snapshot=exports.snapshot(patient,input);byte[] bytes=PatientExportPdf.render(snapshot);
        try(var pdf=org.apache.pdfbox.Loader.loadPDF(bytes)){
            String text=new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);assertTrue(text.contains("Żółć"));assertTrue(pdf.getNumberOfPages()>1);assertFalse(text.contains("TAJNY SZKIC LEKARZA"));
            if(System.getenv("READINESS_QA_PDF")!=null)Files.write(Path.of(System.getenv("READINESS_QA_PDF")),bytes);
        }
    }
}
