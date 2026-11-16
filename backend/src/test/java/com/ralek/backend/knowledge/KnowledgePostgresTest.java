package com.ralek.backend.knowledge;
import com.ralek.backend.knowledge.KnowledgeDtos.*;
import com.ralek.backend.workspace.UserContextService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import com.ralek.backend.storage.ObjectStorageService;
import com.ralek.backend.audit.AuditService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgePostgresTest {
    final String schema="knowledge_test_"+UUID.randomUUID().toString().replace("-","");
    String url=System.getenv("TEST_POSTGRES_URL"),user=System.getenv().getOrDefault("TEST_POSTGRES_USER","med_node"),pass=System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","med_node_dev");
    JdbcClient db;KnowledgeService service;KnowledgeGenerator generator;UserContextService contexts;Jwt jwt=mock(Jwt.class);
    UUID patient,owner,doctor,visit,grant;
    @BeforeAll void database() throws Exception {
        org.flywaydb.core.Flyway.configure().dataSource(url,user,pass).schemas(schema).defaultSchema(schema).load().migrate();
        db=JdbcClient.create(new DriverManagerDataSource(url+"?currentSchema="+schema,user,pass));
        patient=db.sql("SELECT id FROM patients ORDER BY id LIMIT 1").query(UUID.class).single();
        owner=db.sql("SELECT user_id FROM patients WHERE id=:id").param("id",patient).query(UUID.class).single();
        doctor=db.sql("SELECT id FROM users WHERE email='lekarz@med-node.test'").query(UUID.class).single();
    }
    @BeforeEach void setup(){
        db.sql("DELETE FROM patient_knowledge").update();db.sql("DELETE FROM visit_packages").update();
        contexts=mock(UserContextService.class);generator=mock(KnowledgeGenerator.class);
        when(contexts.resolve(jwt,"patient")).thenReturn(actor(owner,patient,"patient"));
        when(contexts.resolve(jwt,"doctor")).thenReturn(actor(doctor,null,"doctor"));
        when(generator.generate(anyList(),anyString())).thenAnswer(i->{List<Source> sources=i.getArgument(0);Source s=sources.stream().filter(x->!x.text().isBlank()).findFirst().orElseThrow();return new Reply(List.of(new Point("FACT",s.id(),s.text())),"","test",false);});
        service=new KnowledgeService(db,contexts,mock(ObjectStorageService.class),generator,new ObjectMapper(),mock(AuditService.class));service.enabled=true;
        visit=UUID.randomUUID();grant=UUID.randomUUID();
        db.sql("INSERT INTO access_grants(id,patient_id,recipient_user_id,granted_by,scope,status) VALUES(:id,:p,:d,:o,'SELECTED_STUDIES','ACTIVE')")
            .param("id",grant).param("p",patient).param("d",doctor).param("o",owner).update();
        db.sql("INSERT INTO visit_packages(id,patient_id,reason,summary,approved_at,grant_id) VALUES(:id,:p,'Kontrola','Plan zatwierdzony.',CURRENT_TIMESTAMP,:grant)")
            .param("id",visit).param("p",patient).param("grant",grant).update();
    }
    UserContext actor(UUID id,UUID p,String view){return new UserContext(id,"test","Jan","Test",Set.of(),List.of(view),false,view,p);}
    @AfterAll void cleanup() throws Exception {if(url!=null)try(var c=DriverManager.getConnection(url,user,pass)){c.createStatement().execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}}
    @Test void generatesPersistsAndReusesSourcePages(){
        assertEquals("QUEUED",service.view(jwt,"patient",visit).status());service.tick();
        var ready=service.view(jwt,"patient",visit);assertEquals("READY",ready.status());assertEquals(2,ready.pages().size());
        assertEquals("",ready.sources().getFirst().text());
        service.rebuild(jwt,"patient",visit);service.tick();verify(generator,times(1)).generate(anyList(),anyString());
        db.sql("UPDATE visit_packages SET summary='Zmieniony zatwierdzony plan.' WHERE id=:id").param("id",visit).update();
        var changed=service.view(jwt,"patient",visit);assertEquals("QUEUED",changed.status());assertTrue(changed.pages().isEmpty());
        service.tick();assertEquals("READY",service.view(jwt,"patient",visit).status());
        verify(generator,times(2)).generate(anyList(),anyString());
    }
    @Test void doctorGetsOnlySelectedPackageAndLosesCachedAccessAfterRevocation(){
        UUID other=UUID.randomUUID();db.sql("INSERT INTO visit_packages(id,patient_id,reason,summary,approved_at) VALUES(:id,:p,'Private','Ukryty plan.',CURRENT_TIMESTAMP)").param("id",other).param("p",patient).update();
        var view=service.view(jwt,"doctor",visit);assertEquals(1,view.sources().size());service.tick();assertEquals("READY",service.view(jwt,"doctor",visit).status());
        db.sql("UPDATE access_grants SET status='REVOKED' WHERE id=:id").param("id",grant).update();
        assertThrows(ResponseStatusException.class,()->service.view(jwt,"doctor",visit));
        assertThrows(ResponseStatusException.class,()->service.ask(jwt,"doctor",new Question("Jaki plan?",visit)));
        service.tick();assertEquals(0,db.sql("SELECT count(*) FROM patient_knowledge_pages").query(Integer.class).single());
    }
    @Test void selectedDocumentsAndArchivalAreRechecked(){
        UUID visible=UUID.randomUUID(),hidden=UUID.randomUUID();
        for(UUID id:List.of(visible,hidden)){
            db.sql("INSERT INTO studies(id,patient_id,study_type_id,created_by,title,ingestion_status,clinical_status) SELECT :id,:p,id,:o,'Document','READY','DRAFT' FROM study_types ORDER BY id LIMIT 1")
                .param("id",id).param("p",patient).param("o",owner).update();
            db.sql("INSERT INTO visit_studies VALUES(:v,:s)").param("v",visit).param("s",id).update();
        }
        db.sql("INSERT INTO access_grant_studies VALUES(:g,:s)").param("g",grant).param("s",visible).update();
        var view=service.view(jwt,"doctor",visit);
        assertTrue(view.sources().stream().anyMatch(x->x.targetId().equals(visible)));
        assertFalse(view.sources().stream().anyMatch(x->x.targetId().equals(hidden)));
        db.sql("UPDATE studies SET archived_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",visible).update();
        assertFalse(service.view(jwt,"doctor",visit).sources().stream().anyMatch(x->x.targetId().equals(visible)));
        db.sql("UPDATE access_grants SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id").param("id",grant).update();
        assertThrows(ResponseStatusException.class,()->service.view(jwt,"doctor",visit));
    }
    @Test void unapprovedPlansAreNeverSentToModel(){
        db.sql("UPDATE visit_packages SET approved_at=NULL,summary='Sekretny szkic' WHERE id=:id").param("id",visit).update();
        assertTrue(service.view(jwt,"doctor",visit).sources().isEmpty());service.tick();verifyNoInteractions(generator);
    }
    @Test void scopeChangeDuringQuestionDiscardsAnswer(){
        reset(generator);when(generator.generate(anyList(),anyString())).thenAnswer(i->{db.sql("UPDATE access_grants SET status='REVOKED' WHERE id=:id").param("id",grant).update();return new Reply(List.of(),"","test",false);});
        assertThrows(ResponseStatusException.class,()->service.ask(jwt,"doctor",new Question("Co zapisano?",visit)));
    }
    @Test void interruptedFinalAttemptBecomesFailedRatherThanStuckQueued(){
        service.view(jwt,"patient",visit);
        db.sql("UPDATE patient_knowledge SET status='GENERATING',attempts=3,started_at=CURRENT_TIMESTAMP-INTERVAL '16 minutes'").update();
        service.tick();assertEquals("FAILED",service.view(jwt,"patient",visit).status());verifyNoInteractions(generator);
    }
    @Test void failedGenerationRetriesAtMostThreeTimes(){
        reset(generator);when(generator.generate(anyList(),anyString())).thenThrow(new IllegalStateException("LLM unavailable"));
        service.view(jwt,"patient",visit);service.tick();service.tick();service.tick();service.tick();
        assertEquals("FAILED",service.view(jwt,"patient",visit).status());verify(generator,times(3)).generate(anyList(),anyString());
    }
}
