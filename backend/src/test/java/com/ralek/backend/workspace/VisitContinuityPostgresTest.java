package com.ralek.backend.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the same authorization contract against PostgreSQL in an isolated disposable schema. */
@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL", matches=".+")
class VisitContinuityPostgresTest extends VisitServiceTest {
    final String schema="visit_test_"+UUID.randomUUID().toString().replace("-", "");
    javax.sql.DataSource source;
    String url() { return System.getenv("TEST_POSTGRES_URL"); }
    String username() { return System.getenv().getOrDefault("TEST_POSTGRES_USER","med_node"); }
    String password() { return System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD","med_node_dev"); }
    @Override javax.sql.DataSource dataSource() throws Exception {
        try(var c=DriverManager.getConnection(url(),username(),password())) { c.createStatement().execute("CREATE SCHEMA "+schema); }
        var pool=new com.zaxxer.hikari.HikariDataSource();
        pool.setJdbcUrl(url()+(url().contains("?")?"&":"?")+"currentSchema="+schema);
        pool.setUsername(username());pool.setPassword(password());pool.setMaximumPoolSize(5);pool.setMinimumIdle(1);
        source=pool;
        return source;
    }
    @AfterEach void cleanup() throws Exception {
        if(source instanceof com.zaxxer.hikari.HikariDataSource pool) pool.close();
        try(var c=DriverManager.getConnection(url(),username(),password())) { c.createStatement().execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE"); }
    }
    @Test void concurrentProgressHasOneWinnerAndOneNotification() throws Exception {
        UUID id=approvedVisit();var action=action(id);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var input=new VisitController.ProgressInput("DONE",null,"",null,List.of(),0);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Boolean> write=()->{start.await();try {tx.executeWithoutResult(s->service.progress(jwt,"patient",id,action.id(),input));return true;}
                catch(org.springframework.web.server.ResponseStatusException conflict) {assertEquals(409,conflict.getStatusCode().value());return false;}};
            var first=pool.submit(write);var second=pool.submit(write);start.countDown();
            assertNotEquals(first.get(),second.get());
        }
        assertEquals(1,service.notifications(jwt,"doctor").size());
    }
    @Test void postgresTriggersCarryOnlyInvalidation() throws Exception {
        jdbc.sql("CREATE TABLE realtime_events(recipient UUID,kind TEXT,resource TEXT,resource_id UUID)").update();
        jdbc.sql("""
            CREATE FUNCTION curavence_emit(recipient UUID,kind TEXT,resource TEXT,resource_id UUID)
            RETURNS VOID LANGUAGE SQL AS 'INSERT INTO realtime_events VALUES ($1,$2,$3,$4)';
            CREATE FUNCTION curavence_patient_event(patient UUID,kind TEXT,resource TEXT,resource_id UUID)
            RETURNS VOID LANGUAGE SQL AS 'SELECT curavence_emit(user_id,$2,$3,$4) FROM patients WHERE id=$1';
            """).update();
        try(var c=source.getConnection()) { c.createStatement().execute(Files.readString(Path.of("src/main/resources/db/migration/V36__visit_continuity_events.sql"))); }
        UUID id=approvedVisit();
        service.progress(jwt,"patient",id,action(id).id(),new VisitController.ProgressInput("DONE",null,"Private medical text",null,List.of(),0));
        assertTrue(jdbc.sql("SELECT count(*) FROM realtime_events").query(Integer.class).single()>0);
        assertEquals(0,jdbc.sql("SELECT count(*) FROM realtime_events WHERE kind<>'visits.changed' OR resource<>'workspace'").query(Integer.class).single());
    }
    @Test void fullMigrationChainPreservesLegacyCompletionAndEmitsRealEvents() throws Exception {
        String full=schema+"_full";
        try {
            org.flywaydb.core.Flyway.configure().dataSource(url(),username(),password()).schemas(full)
                .defaultSchema(full).target("34").load().migrate();
            var fullSource=new DriverManagerDataSource(url()+(url().contains("?")?"&":"?")+"currentSchema="+full,username(),password());
            var db=org.springframework.jdbc.core.simple.JdbcClient.create(fullSource);
            UUID patient=db.sql("SELECT id FROM patients LIMIT 1").query(UUID.class).single();
            UUID visit=UUID.randomUUID(), action=UUID.randomUUID();
            db.sql("INSERT INTO visit_packages(id,patient_id,reason) VALUES (:id,:patient,'Legacy')").param("id",visit).param("patient",patient).update();
            db.sql("INSERT INTO visit_actions(id,visit_id,position,description,due_date,responsible,completed) VALUES (:id,:visit,0,'Legacy',CURRENT_DATE,'Pacjent',TRUE)")
                .param("id",action).param("visit",visit).update();
            org.flywaydb.core.Flyway.configure().dataSource(url(),username(),password()).schemas(full).defaultSchema(full).load().migrate();
            assertEquals("DONE",db.sql("SELECT patient_status FROM visit_actions WHERE id=:id").param("id",action).query(String.class).single());
            assertEquals(1,db.sql("SELECT count(*) FROM visit_actions WHERE id=:id AND completed=TRUE AND performed_on IS NULL AND reported_at IS NULL AND discussed_at IS NULL")
                .param("id",action).query(Integer.class).single());
            db.sql("UPDATE visit_actions SET patient_note='Private text' WHERE id=:id").param("id",action).update();
            assertTrue(db.sql("SELECT count(*) FROM realtime_events WHERE event_type='visits.changed'").query(Integer.class).single()>0);
        } finally {
            try(var c=DriverManager.getConnection(url(),username(),password())) { c.createStatement().execute("DROP SCHEMA IF EXISTS "+full+" CASCADE"); }
        }
    }
}
