package com.ralek.backend.document;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.storage.ObjectStorageService;
import com.ralek.backend.workspace.*;
import com.ralek.backend.document.ReadingGuideDtos.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL", matches=".+")
class ReadingActionsPostgresTest {
    String schema = "reading_test_" + UUID.randomUUID().toString().replace("-", "");
    JdbcClient jdbc; DriverManagerDataSource source; ReadingActionsController controller;
    WorkspaceService workspace; UserContextService contexts;
    UUID patient = UUID.randomUUID(), other = UUID.randomUUID(), user = UUID.randomUUID(), doctor = UUID.randomUUID();
    UUID document = UUID.randomUUID(), image = UUID.randomUUID(), foreign = UUID.randomUUID();
    Jwt jwt = mock(Jwt.class);
    String url() { return System.getenv("TEST_POSTGRES_URL"); }
    String username() { return System.getenv().getOrDefault("TEST_POSTGRES_USER", "med_node"); }
    String password() { return System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "med_node_dev"); }
    UserContextService.UserContext context(UUID userId, UUID patientId, String role) {
        return new UserContextService.UserContext(userId,"test@example.test","Test","User", Set.of(), List.of(role),false,role,patientId);
    }
    @BeforeEach void setup() throws Exception {
        try (var connection = DriverManager.getConnection(url(), username(), password())) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        source = new DriverManagerDataSource(url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + schema, username(), password());
        jdbc = JdbcClient.create(source);
        jdbc.sql("""
            CREATE TABLE patients(id UUID PRIMARY KEY);
            CREATE TABLE studies(id UUID PRIMARY KEY, patient_id UUID, title TEXT, archived_at TIMESTAMPTZ, metadata JSONB, study_type_id UUID);
            CREATE TABLE imaging_studies(id UUID PRIMARY KEY, patient_id UUID);
            CREATE TABLE access_grants(id UUID, patient_id UUID, recipient_user_id UUID, status TEXT, expires_at TIMESTAMPTZ, scope TEXT);
            CREATE TABLE access_grant_studies(grant_id UUID, study_id UUID);
            CREATE TABLE study_types(id UUID, code TEXT);
            CREATE TABLE clinical_reviews(study_id UUID);
            """).update();
        try (var connection = source.getConnection()) {
            connection.createStatement().execute(Files.readString(Path.of("src/main/resources/db/migration/V34__reading_guide.sql")));
        }
        for (UUID id : List.of(patient, other)) jdbc.sql("INSERT INTO patients VALUES(:id)").param("id", id).update();
        for (UUID id : List.of(document, image, foreign)) jdbc.sql("INSERT INTO studies VALUES(:id,:patient,'Opis',NULL,'{}',NULL)")
            .param("id", id).param("patient", id.equals(foreign) ? other : patient).update();
        jdbc.sql("INSERT INTO imaging_studies(id,patient_id) VALUES(:id,:patient)").param("id",image).param("patient",patient).update();
        contexts = mock(UserContextService.class);
        when(contexts.resolve(jwt,"patient")).thenReturn(context(user,patient,"patient"));
        when(contexts.resolve(jwt,"doctor")).thenReturn(context(doctor,null,"doctor"));
        var audit = mock(AuditService.class);
        workspace = new WorkspaceService(jdbc,contexts,audit,mock(ObjectStorageService.class));
        var guides = mock(ReadingGuideService.class);
        when(guides.guide(eq(jwt),anyString(),any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(2); workspace.requireStudyReadAccess(jwt, inv.getArgument(1), id);
            return new Guide(true,id.toString(),"v","d",List.of(new Block("b0","Kontrola za rok.",List.of())),List.of());
        });
        controller = new ReadingActionsController(jdbc,contexts,workspace,guides,audit);
    }
    @AfterEach void cleanup() throws Exception {
        try (var connection = DriverManager.getConnection(url(), username(), password())) {
            connection.createStatement().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
    ReadingActionsController.ReminderInput input(String description, boolean completed) {
        return new ReadingActionsController.ReminderInput(document,
            ReadingGuideService.version("recommendationb0Kontrola za rok.").substring(0,24), description,
            "Kontrola za rok.", LocalDate.of(2027,11,1),completed);
    }
    @Test void duplicateRequestsReturnExistingReminderWithoutOverwritingIt() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> controller.create(jwt,"patient",input("Pierwsza treść",false)));
            var second = pool.submit(() -> controller.create(jwt,"patient",input("Druga treść",false)));
            assertThat(first.get().id()).isEqualTo(second.get().id());
        }
        assertThat(controller.reminders(jwt,"patient")).hasSize(1);
        var existing = controller.reminders(jwt,"patient").getFirst();
        controller.update(jwt,"patient",existing.id(),input("Zmienione",true));
        assertThat(controller.reminders(jwt,"patient").getFirst().completed()).isTrue();
    }
    @Test void remindersArePrivateAndArchivedSourcesDisappear() {
        controller.create(jwt,"patient",input("Moje",false));
        assertThatThrownBy(() -> controller.reminders(jwt,"doctor")).isInstanceOf(ResponseStatusException.class);
        when(contexts.resolve(jwt,"patient")).thenReturn(context(UUID.randomUUID(),other,"patient"));
        assertThat(controller.reminders(jwt,"patient")).isEmpty();
        when(contexts.resolve(jwt,"patient")).thenReturn(context(user,patient,"patient"));
        jdbc.sql("UPDATE studies SET archived_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",document).update();
        assertThat(controller.reminders(jwt,"patient")).isEmpty();
        assertThatThrownBy(() -> controller.create(jwt,"patient",input("Moje",false))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void cannotLinkForeignPatientOrImagingStudyAsDocument() {
        assertThatThrownBy(() -> controller.link(jwt,"patient",image,new ReadingActionsController.ReportInput(foreign))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.link(jwt,"patient",image,new ReadingActionsController.ReportInput(image))).isInstanceOf(ResponseStatusException.class);
        controller.link(jwt,"patient",image,new ReadingActionsController.ReportInput(document));
        assertThat(controller.report(jwt,"patient",image).documentId()).isEqualTo(document);
        controller.link(jwt,"patient",image,new ReadingActionsController.ReportInput(null));
        assertThat(controller.report(jwt,"patient",image).documentId()).isNull();
    }
    @Test void imageGrantDoesNotImplicitlyGrantAccessToLinkedDocumentAndRevocationIsImmediate() {
        controller.link(jwt,"patient",image,new ReadingActionsController.ReportInput(document));
        UUID grant = UUID.randomUUID();
        jdbc.sql("INSERT INTO access_grants VALUES(:id,:patient,:doctor,'ACTIVE',NULL,'SELECTED_STUDIES')")
            .param("id",grant).param("patient",patient).param("doctor",doctor).update();
        jdbc.sql("INSERT INTO access_grant_studies VALUES(:grant,:image)").param("grant",grant).param("image",image).update();
        assertThatThrownBy(() -> controller.report(jwt,"doctor",image)).isInstanceOf(ResponseStatusException.class);
        jdbc.sql("INSERT INTO access_grant_studies VALUES(:grant,:document)").param("grant",grant).param("document",document).update();
        assertThat(controller.report(jwt,"doctor",image).documentId()).isEqualTo(document);
        jdbc.sql("UPDATE access_grants SET status='REVOKED'").update();
        assertThatThrownBy(() -> controller.report(jwt,"doctor",image)).isInstanceOf(ResponseStatusException.class);
    }
}
