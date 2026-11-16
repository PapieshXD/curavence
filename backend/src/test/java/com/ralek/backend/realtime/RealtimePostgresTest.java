package com.ralek.backend.realtime;

import com.ralek.backend.workspace.UserContextService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL only: H2 cannot prove trigger/commit-order/replay behavior. */
@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
class RealtimePostgresTest {
    String schema="realtime_test_"+UUID.randomUUID().toString().replace("-","");
    DriverManagerDataSource source; JdbcClient jdbc; RealtimeService service;
    UUID owner=UUID.randomUUID(),recipient=UUID.randomUUID(),stranger=UUID.randomUUID(),patient=UUID.randomUUID(),study=UUID.randomUUID(),grant=UUID.randomUUID();
    String url(){return System.getenv("TEST_POSTGRES_URL");}
    String user(){return System.getenv().getOrDefault("TEST_POSTGRES_USER","med_node");}
    String password(){return System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","med_node_dev");}
    @BeforeEach void setup() throws Exception {
        try(var connection=DriverManager.getConnection(url(),user(),password())) {connection.createStatement().execute("CREATE SCHEMA "+schema);}
        source=new DriverManagerDataSource(url()+(url().contains("?")?"&":"?")+"currentSchema="+schema,user(),password());
        jdbc=JdbcClient.create(source);
        jdbc.sql("CREATE TABLE users(id UUID PRIMARY KEY,status TEXT); CREATE TABLE patients(id UUID PRIMARY KEY,user_id UUID)").update();
        jdbc.sql("CREATE TABLE studies(id UUID PRIMARY KEY,patient_id UUID,ingestion_status TEXT,archived_at TIMESTAMPTZ)").update();
        jdbc.sql("CREATE TABLE access_grants(id UUID PRIMARY KEY,patient_id UUID,recipient_user_id UUID,status TEXT,expires_at TIMESTAMPTZ)").update();
        jdbc.sql("CREATE TABLE access_grant_studies(grant_id UUID,study_id UUID)").update();
        jdbc.sql("CREATE TABLE imaging_analyses(id UUID,study_id UUID,status TEXT)").update();
        jdbc.sql("CREATE TABLE imaging_studies(id UUID,patient_id UUID,status TEXT)").update();
        jdbc.sql("CREATE TABLE clinical_reviews(id UUID,study_id UUID)").update();
        try(var connection=source.getConnection()) {connection.createStatement().execute(Files.readString(Path.of("src/main/resources/db/migration/V33__realtime_outbox.sql")));}
        for(UUID id:List.of(owner,recipient,stranger))jdbc.sql("INSERT INTO users VALUES(:id,'ACTIVE')").param("id",id).update();
        jdbc.sql("INSERT INTO patients VALUES(:id,:user)").param("id",patient).param("user",owner).update();
        service=new RealtimeService(jdbc,mock(UserContextService.class));
    }
    @AfterEach void cleanup() throws Exception {
        try(var connection=DriverManager.getConnection(url(),user(),password())) {connection.createStatement().execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }
    void study(){jdbc.sql("INSERT INTO studies VALUES(:id,:patient,'READY',NULL)").param("id",study).param("patient",patient).update();}
    void grant(){jdbc.sql("INSERT INTO access_grants VALUES(:id,:patient,:recipient,'ACTIVE',CURRENT_TIMESTAMP+INTERVAL '1 hour')")
        .param("id",grant).param("patient",patient).param("recipient",recipient).update();}
    @Test void scopesEventsAndReplaysAfterRestartWithoutLeakingStudyToRecipient() {
        grant();study();
        var owned=service.read(owner,0,100);assertEquals("study.created",owned.events().getLast().type());
        var shared=service.read(recipient,0,100);assertEquals("workspace.changed",shared.events().getLast().type());
        assertEquals(recipient,shared.events().getLast().resourceId());assertTrue(service.read(stranger,0,100).events().isEmpty());
        var restarted=new RealtimeService(jdbc,mock(UserContextService.class));
        assertEquals(shared,restarted.read(recipient,0,100));assertTrue(restarted.read(recipient,shared.cursor(),100).events().isEmpty());
    }
    @Test void rollbackDoesNotPublishAndPaginationHasNoGaps() throws Exception {
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT curavence_emit(?, 'study.created','study',?)")) {
                statement.setObject(1,owner);statement.setObject(2,study);statement.execute();
            }
            assertTrue(service.read(owner,0,100).events().isEmpty());connection.rollback();
        }
        assertTrue(service.read(owner,0,100).events().isEmpty());
        study();jdbc.sql("UPDATE studies SET ingestion_status='PROCESSING' WHERE id=:id").param("id",study).update();
        var first=service.read(owner,0,1);assertTrue(first.hasMore());
        var second=service.read(owner,first.cursor(),1);assertFalse(second.hasMore());assertEquals("study.processing.started",second.events().getFirst().type());
    }
    @Test void revocationAndExpiryReachRecipientAndStopFutureInvalidations() {
        grant();study();long cursor=service.read(recipient,0,100).cursor();
        jdbc.sql("UPDATE access_grants SET status='REVOKED' WHERE id=:id").param("id",grant).update();
        var revoked=service.read(recipient,cursor,100);assertEquals("share.revoked",revoked.events().getFirst().type());
        jdbc.sql("UPDATE studies SET ingestion_status='FAILED' WHERE id=:id").param("id",study).update();
        assertTrue(service.read(recipient,revoked.cursor(),100).events().isEmpty());
        jdbc.sql("UPDATE access_grants SET status='ACTIVE',expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=:id").param("id",grant).update();
        cursor=service.read(recipient,0,100).cursor();service.dispatch();
        assertEquals("share.revoked",service.read(recipient,cursor,100).events().getFirst().type());
    }
    @Test void concurrentCommitCannotOvertakeCursor() throws Exception {
        try(var first=source.getConnection();var executor=Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            try(var statement=first.prepareStatement("SELECT curavence_emit(?, 'first','study',?)")) {
                statement.setObject(1,owner);statement.setObject(2,study);statement.execute();
            }
            var started=new CountDownLatch(1);
            var second=executor.submit(()->{started.countDown();jdbc.sql("SELECT curavence_emit(:user,'second','study',:study)").param("user",owner).param("study",study).query().listOfRows();return true;});
            assertTrue(started.await(2,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->second.get(150,TimeUnit.MILLISECONDS));
            assertTrue(service.read(owner,0,100).events().isEmpty());first.commit();assertTrue(second.get(5,TimeUnit.SECONDS));
            assertEquals(List.of("first","second"),service.read(owner,0,100).events().stream().map(RealtimeService.Event::type).toList());
        }
    }
}
