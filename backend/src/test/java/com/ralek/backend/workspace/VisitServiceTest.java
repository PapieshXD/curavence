package com.ralek.backend.workspace;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import com.ralek.backend.workspace.VisitController.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisitServiceTest {
    JdbcClient jdbc;
    UserContextService contexts;
    VisitService service;
    Jwt jwt = mock(Jwt.class);
    UUID patient = UUID.randomUUID(), patientUser = UUID.randomUUID(), doctor = UUID.randomUUID(), study = UUID.randomUUID();
    javax.sql.DataSource dataSource() throws Exception {
        return new SingleConnectionDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "", true);
    }
    @BeforeEach void setup() throws Exception {
        jdbc = JdbcClient.create(dataSource());
        jdbc.sql("CREATE TABLE users(id UUID PRIMARY KEY, first_name VARCHAR, last_name VARCHAR, email VARCHAR, status VARCHAR)").update();
        jdbc.sql("CREATE TABLE patients(id UUID PRIMARY KEY, user_id UUID)").update();
        jdbc.sql("CREATE TABLE studies(id UUID PRIMARY KEY, patient_id UUID, archived_at TIMESTAMP)").update();
        jdbc.sql("CREATE TABLE practitioners(user_id UUID, professional_type VARCHAR)").update();
        jdbc.sql("CREATE TABLE access_grants(id UUID PRIMARY KEY, recipient_user_id UUID, status VARCHAR, expires_at TIMESTAMP WITH TIME ZONE)").update();
        for (String sql : Files.readString(Path.of("src/main/resources/db/migration/V32__visit_packages.sql")).split(";")) if (!sql.isBlank()) jdbc.sql(sql.replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")).update();
        jdbc.sql("INSERT INTO users VALUES (:id, 'Jan', 'Pacjent', 'patient@test.local', 'ACTIVE')").param("id", patientUser).update();
        jdbc.sql("INSERT INTO users VALUES (:id, 'Anna', 'Lekarz', 'doctor@test.local', 'ACTIVE')").param("id", doctor).update();
        jdbc.sql("INSERT INTO practitioners VALUES (:id, 'DOCTOR')").param("id", doctor).update();
        jdbc.sql("INSERT INTO patients VALUES (:id, :user)").param("id", patient).param("user", patientUser).update();
        jdbc.sql("INSERT INTO studies(id,patient_id) VALUES (:id, :patient)").param("id", study).param("patient", patient).update();
        jdbc.sql("CREATE TABLE access_grant_studies(grant_id UUID, study_id UUID, PRIMARY KEY(grant_id,study_id))").update();
        for (String sql : Files.readString(Path.of("src/main/resources/db/migration/V35__visit_continuity.sql")).split(";")) if (!sql.isBlank()) jdbc.sql(sql.replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")).update();
        contexts = mock(UserContextService.class);
        when(contexts.resolve(jwt,"patient")).thenReturn(context(patientUser, patient, "patient", "PATIENT"));
        when(contexts.resolve(jwt,"doctor")).thenReturn(context(doctor, null, "doctor", "DOCTOR"));
        WorkspaceService workspace = mock(WorkspaceService.class);
        when(workspace.createVisitGrant(eq(jwt), eq("patient"), any())).thenAnswer(inv -> {
            UUID id=UUID.randomUUID();
            jdbc.sql("INSERT INTO access_grants VALUES (:id, :doctor, 'ACTIVE', :expires)").param("id",id).param("doctor",doctor)
                .param("expires",OffsetDateTime.now().plusDays(7)).update();
            jdbc.sql("INSERT INTO access_grant_studies VALUES (:grant,:study)").param("grant",id).param("study",study).update();
            return id;
        });
        service = new VisitService(jdbc, contexts, workspace, mock(AuditService.class));
    }
    UserContext context(UUID user, UUID patientId, String view, String role) {
        return new UserContext(user,"test@test.local","Test","User",Set.of(role),List.of(view),false,view,patientId);
    }
    UUID create() { return service.save(jwt,"patient",null,new PackageInput("Konsultacja", "Objawy", "Pytanie",null,List.of(study))); }
    void share(UUID id) { service.share(jwt,"patient",id,new ShareInput("doctor@test.local",Instant.now().plusSeconds(86400),true)); }
    PlanInput plan(boolean approved) { return new PlanInput("Ustalenia lekarza",List.of(new ActionInput("Umów kontrolę",LocalDate.now().plusDays(7),"Pacjent")),approved); }

    @Test void fullWorkflowHidesDraftAndPreservesApprovedPlanAfterRevocation() {
        UUID id=create();
        assertTrue(service.list(jwt,"doctor").isEmpty());
        share(id);
        assertEquals(1,service.list(jwt,"doctor").size());
        service.plan(jwt,"doctor",id,plan(false));
        assertEquals("",service.list(jwt,"patient").getFirst().summary());
        assertTrue(service.list(jwt,"patient").getFirst().actions().isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.complete(jwt,"patient",id,UUID.randomUUID(),true));
        service.plan(jwt,"doctor",id,plan(true));
        var visit=service.list(jwt,"patient").getFirst();
        assertNotNull(visit.approvedAt());
        assertEquals("Ustalenia lekarza",visit.summary());
        service.complete(jwt,"patient",id,visit.actions().getFirst().id(),true);
        assertTrue(service.list(jwt,"patient").getFirst().actions().getFirst().completed());
        jdbc.sql("UPDATE access_grants SET status='REVOKED'").update();
        assertTrue(service.list(jwt,"doctor").isEmpty());
        assertEquals("Ustalenia lekarza",service.list(jwt,"patient").getFirst().summary());
        assertThrows(ResponseStatusException.class, () -> service.plan(jwt,"doctor",id,plan(true)));
    }
    @Test void expiredGrantBlocksReadingAndWriting() {
        UUID id=create(); share(id);
        jdbc.sql("UPDATE access_grants SET expires_at=:expired").param("expired",OffsetDateTime.now().minusDays(1)).update();
        assertTrue(service.list(jwt,"doctor").isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.plan(jwt,"doctor",id,plan(false)));
    }
    @Test void foreignStudiesAndOtherPatientsAreRejected() {
        assertThrows(ResponseStatusException.class, () -> service.save(jwt,"patient",null,new PackageInput("Test","","",null,List.of(UUID.randomUUID()))));
        UUID id=create();
        when(contexts.resolve(jwt,"patient")).thenReturn(context(UUID.randomUUID(),UUID.randomUUID(),"patient","PATIENT"));
        assertTrue(service.list(jwt,"patient").isEmpty());
        assertThrows(ResponseStatusException.class, () -> share(id));
        assertThrows(ResponseStatusException.class, () -> service.complete(jwt,"patient",id,UUID.randomUUID(),true));
    }
    @Test void sharedPackageAndApprovedPlanCannotBeRewritten() {
        UUID id=create(); share(id);
        assertThrows(ResponseStatusException.class, () -> service.save(jwt,"patient",id,new PackageInput("Zmieniono","","",null,List.of())));
        assertThrows(ResponseStatusException.class, () -> share(id));
        service.plan(jwt,"doctor",id,plan(true));
        assertThrows(ResponseStatusException.class, () -> service.plan(jwt,"doctor",id,plan(false)));
    }
    @Test void diagnosticianAndUnrelatedDoctorCannotApprove() {
        UUID id=create(); share(id);
        when(contexts.resolve(jwt,"doctor")).thenReturn(context(doctor,null,"doctor","DIAGNOSTICIAN"));
        assertThrows(ResponseStatusException.class, () -> service.plan(jwt,"doctor",id,plan(true)));
        when(contexts.resolve(jwt,"doctor")).thenReturn(context(UUID.randomUUID(),null,"doctor","DOCTOR"));
        assertTrue(service.list(jwt,"doctor").isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.plan(jwt,"doctor",id,plan(true)));
    }
    @Test void contextOnlyPackageCanBeShared() {
        UUID id=service.save(jwt,"patient",null,new PackageInput("Pierwsza wizyta","","",null,List.of()));
        share(id); assertTrue(service.list(jwt,"doctor").getFirst().studyIds().isEmpty());
    }

    UUID approvedVisit() { UUID id=create(); share(id); service.plan(jwt,"doctor",id,plan(true)); return id; }
    UUID followUp(UUID previous, boolean include) {
        return service.save(jwt,"patient",null,new PackageInput("Kontrola","Zmiana opisana przez pacjenta","Pytania",null,List.of(),previous,include));
    }
    VisitService.Action action(UUID id) { return service.list(jwt,"patient").stream().filter(v->v.id().equals(id)).findFirst().orElseThrow().actions().getFirst(); }
    @Test void explicitPreviousPlanDoesNotGrantAccessToDocumentsOrOtherVisits() {
        UUID previous=approvedVisit(); UUID next=followUp(previous,true); share(next);
        jdbc.sql("UPDATE access_grants SET status='REVOKED' WHERE id=(SELECT grant_id FROM visit_packages WHERE id=:id)").param("id",previous).update();
        var visits=service.list(jwt,"doctor"); assertEquals(1,visits.size());
        assertEquals(previous,visits.getFirst().previousPlan().id());
        assertTrue(visits.getFirst().studyIds().isEmpty());
        assertTrue(visits.getFirst().previousPlan().actions().getFirst().studyIds().isEmpty());
        UUID hidden=followUp(previous,false); share(hidden);
        var noContext=service.list(jwt,"doctor").stream().filter(v->v.id().equals(hidden)).findFirst().orElseThrow();
        assertNull(noContext.previousVisitId()); assertNull(noContext.previousPlan());
        assertThrows(ResponseStatusException.class,()->service.discuss(jwt,"doctor",hidden,action(previous).id(),new DiscussionInput(true,0)));
    }
    @Test void foreignAndUnapprovedPreviousVisitsAreRejected() {
        assertThrows(ResponseStatusException.class,()->followUp(create(),true));
        assertThrows(ResponseStatusException.class,()->followUp(UUID.randomUUID(),true));
        UUID own=approvedVisit();
        when(contexts.resolve(jwt,"patient")).thenReturn(context(UUID.randomUUID(),UUID.randomUUID(),"patient","PATIENT"));
        assertThrows(ResponseStatusException.class,()->followUp(own,true));
    }
    @Test void evidenceNeedsExplicitScopeAndDiscussionIsIndependent() {
        UUID previous=approvedVisit(); UUID next=followUp(previous,true); share(next);
        var a=action(previous);
        service.progress(jwt,"patient",previous,a.id(),new ProgressInput("DONE",null,"Gotowe",null,List.of(study),a.revision()));
        var context=service.list(jwt,"doctor").stream().filter(v->v.id().equals(next)).findFirst().orElseThrow();
        assertTrue(context.previousPlan().actions().getFirst().studyIds().isEmpty());
        assertNull(context.previousPlan().actions().getFirst().discussedAt());
        service.extend(jwt,"patient",next,new ExtendInput(List.of(study),true));
        context=service.list(jwt,"doctor").stream().filter(v->v.id().equals(next)).findFirst().orElseThrow();
        assertEquals(List.of(study),context.previousPlan().actions().getFirst().studyIds());
        service.discuss(jwt,"doctor",next,a.id(),new DiscussionInput(true,1));
        assertNotNull(action(previous).discussedAt());
        service.progress(jwt,"patient",previous,a.id(),new ProgressInput("DIFFICULTY",null,"Brak terminu","NO_APPOINTMENT",List.of(),2));
        assertNull(action(previous).discussedAt()); assertFalse(action(previous).completed());
        assertTrue(action(previous).studyIds().isEmpty());
    }
    @Test void staleChangesAndUnconfirmedScopeAreRejected() {
        UUID id=approvedVisit(); var a=action(id);
        var update=new ProgressInput("DONE",null,"",null,List.of(),0);
        service.progress(jwt,"patient",id,a.id(),update);
        assertThrows(ResponseStatusException.class,()->service.progress(jwt,"patient",id,a.id(),update));
        assertThrows(ResponseStatusException.class,()->service.extend(jwt,"patient",id,new ExtendInput(List.of(study),false)));
        assertThrows(ResponseStatusException.class,()->service.extend(jwt,"patient",id,new ExtendInput(List.of(UUID.randomUUID()),true)));
        assertThrows(ResponseStatusException.class,()->service.progress(jwt,"patient",id,a.id(),new ProgressInput("DIFFICULTY",null,"",null,List.of(),1)));
        assertThrows(ResponseStatusException.class,()->service.progress(jwt,"patient",id,a.id(),new ProgressInput("DONE",null,"",null,List.of(UUID.randomUUID()),1)));
    }
    @Test void revocationBlocksContextDiscussionScopeAndDoctorNotifications() {
        UUID id=approvedVisit();var a=action(id);
        service.progress(jwt,"patient",id,a.id(),new ProgressInput("DONE",null,"",null,List.of(),0));
        assertFalse(service.notifications(jwt,"doctor").isEmpty());
        jdbc.sql("UPDATE access_grants SET status='REVOKED'").update();
        assertTrue(service.notifications(jwt,"doctor").isEmpty());
        assertThrows(ResponseStatusException.class,()->service.discuss(jwt,"doctor",id,a.id(),new DiscussionInput(true,1)));
        assertThrows(ResponseStatusException.class,()->service.extend(jwt,"patient",id,new ExtendInput(List.of(study),true)));
        assertEquals(1,service.list(jwt,"patient").size());
    }
    @Test void notificationsAreIdempotentPrivateAndCanBeRead() {
        UUID id=approvedVisit(); var a=action(id);
        jdbc.sql("UPDATE visit_actions SET due_date=:date WHERE id=:id").param("date",LocalDate.now(ZoneId.of("Europe/Warsaw"))).param("id",a.id()).update();
        service.dueReminders();service.dueReminders();
        assertEquals(2,service.notifications(jwt,"patient").size());
        var notification=service.notifications(jwt,"patient").getFirst();
        service.readNotification(jwt,"patient",notification.id());service.readNotification(jwt,"patient",notification.id());
        assertNotNull(service.notifications(jwt,"patient").stream().filter(n->n.id().equals(notification.id())).findFirst().orElseThrow().readAt());
        assertThrows(ResponseStatusException.class,()->service.readNotification(jwt,"doctor",notification.id()));
        jdbc.sql("UPDATE visit_actions SET due_date=:date").param("date",LocalDate.now(ZoneId.of("Europe/Warsaw")).minusDays(1)).update();
        service.dueReminders();assertEquals(2,service.notifications(jwt,"patient").size());
    }
    @Test void warsawDatesRemainCorrectAroundDaylightSavingChanges() {
        assertEquals(LocalDate.of(2026,3,29),VisitService.warsawDate(Instant.parse("2026-03-28T23:30:00Z")));
        assertEquals(LocalDate.of(2026,3,30),VisitService.warsawDate(Instant.parse("2026-03-29T22:30:00Z")));
        assertEquals(LocalDate.of(2026,10,25),VisitService.warsawDate(Instant.parse("2026-10-25T22:30:00Z")));
        assertEquals(LocalDate.of(2026,10,26),VisitService.warsawDate(Instant.parse("2026-10-25T23:30:00Z")));
    }
    @Test void archiveHidesDocumentAndPreventsReattachingIt() {
        UUID id=approvedVisit();var a=action(id);
        service.progress(jwt,"patient",id,a.id(),new ProgressInput("DONE",null,"",null,List.of(study),0));
        jdbc.sql("UPDATE studies SET archived_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",study).update();
        assertTrue(service.list(jwt,"doctor").getFirst().studyIds().isEmpty());
        assertTrue(service.list(jwt,"doctor").getFirst().actions().getFirst().studyIds().isEmpty());
        assertThrows(ResponseStatusException.class,()->service.progress(jwt,"patient",id,a.id(),new ProgressInput("DONE",null,"",null,List.of(study),1)));
    }
}
