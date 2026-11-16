package com.ralek.backend.document;

import com.ralek.backend.workspace.UserContextService;
import com.ralek.backend.workspace.WorkspaceService;
import com.ralek.backend.audit.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ReadingActionsController {
    private final JdbcClient jdbc;
    private final UserContextService contexts;
    private final WorkspaceService workspace;
    private final ReadingGuideService guides;
    private final AuditService audit;
    public ReadingActionsController(JdbcClient jdbc, UserContextService contexts, WorkspaceService workspace,
        ReadingGuideService guides, AuditService audit) {
        this.jdbc = jdbc; this.contexts = contexts; this.workspace = workspace; this.guides = guides; this.audit = audit;
    }
    public record Reminder(UUID id, UUID documentId, String recommendationId, String description,
        String sourceQuote, LocalDate dueDate, boolean completed) {}
    public record ReminderInput(@NotNull UUID documentId, @NotBlank @Size(max=64) String recommendationId,
        @NotBlank @Size(max=1000) String description, @NotBlank @Size(max=3000) String sourceQuote,
        @NotNull LocalDate dueDate, boolean completed) {}
    public record ReportInput(UUID documentId) {}
    public record Report(UUID documentId, String title) {}
    private UserContextService.UserContext owner(Jwt jwt, String role) {
        guides.requireEnabled();
        var actor = contexts.resolve(jwt, role);
        if (!actor.patientView() || actor.patientId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }
    @GetMapping("/personal-reminders")
    public List<Reminder> reminders(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="X-Workspace-Role", required=false) String role) {
        var actor = owner(jwt, role);
        var result = jdbc.sql("""
            SELECT r.* FROM personal_reminders r JOIN studies s ON s.id = r.document_id
            WHERE r.patient_id = :patient AND s.patient_id = :patient AND s.archived_at IS NULL
            ORDER BY r.completed, r.due_date, r.id
            """).param("patient", actor.patientId()).query((rs, n) -> new Reminder(rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("recommendation_id"), rs.getString("description"),
                rs.getString("source_quote"), rs.getObject("due_date", LocalDate.class), rs.getBoolean("completed"))).list();
        for (var reminder : result) workspace.requireStudyReadAccess(jwt, role, reminder.documentId());
        return result;
    }
    @PostMapping("/personal-reminders")
    @Transactional
    public Reminder create(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="X-Workspace-Role", required=false) String role, @Valid @RequestBody ReminderInput input) {
        var actor = owner(jwt, role);
        verifyEvidence(jwt, role, input);
        UUID id = jdbc.sql("""
            INSERT INTO personal_reminders(id, patient_id, document_id, recommendation_id, description, source_quote, due_date)
            VALUES (:id, :patient, :document, :recommendation, :description, :quote, :due)
            ON CONFLICT (patient_id, document_id, recommendation_id) DO UPDATE SET id = personal_reminders.id
            RETURNING id
            """).param("id", UUID.randomUUID()).param("patient", actor.patientId()).param("document", input.documentId())
            .param("recommendation", input.recommendationId()).param("description", input.description().trim())
            .param("quote", input.sourceQuote()).param("due", input.dueDate()).query(UUID.class).single();
        audit.record(actor.id(), actor.patientId(), "STUDY", input.documentId(), "UPDATE", true, null,
            Map.of("purpose", "PERSONAL_REMINDER", "reminderId", id.toString()));
        return reminders(jwt, role).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }
    private void verifyEvidence(Jwt jwt, String role, ReminderInput input) {
        var guide = guides.guide(jwt, role, input.documentId());
        boolean valid = guide.blocks().stream().anyMatch(b -> b.text().contains(input.sourceQuote()) &&
            ReadingGuideService.version("recommendation" + b.id() + input.sourceQuote()).substring(0, 24).equals(input.recommendationId()));
        if (!valid) throw new ResponseStatusException(HttpStatus.CONFLICT, "Źródło zalecenia zmieniło się. Przygotuj podsumowanie ponownie.");
    }
    @PutMapping("/personal-reminders/{id}")
    @Transactional
    public void update(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="X-Workspace-Role", required=false) String role, @PathVariable UUID id,
        @Valid @RequestBody ReminderInput input) {
        var actor = owner(jwt, role);
        var existing = reminders(jwt, role).stream().filter(r -> r.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!existing.documentId().equals(input.documentId()) || !existing.recommendationId().equals(input.recommendationId())
            || !existing.sourceQuote().equals(input.sourceQuote())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        jdbc.sql("""
            UPDATE personal_reminders SET description=:description, due_date=:due, completed=:completed,
            updated_at=CURRENT_TIMESTAMP WHERE id=:id AND patient_id=:patient
            """).param("description", input.description().trim()).param("due", input.dueDate())
            .param("completed", input.completed()).param("id", id).param("patient", actor.patientId()).update();
        audit.record(actor.id(), actor.patientId(), "STUDY", existing.documentId(), "UPDATE", true, null,
            Map.of("purpose", "PERSONAL_REMINDER", "reminderId", id.toString()));
    }
    @GetMapping("/imaging-studies/{id}/report-document")
    public Report report(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="X-Workspace-Role", required=false) String role, @PathVariable UUID id) {
        guides.requireEnabled();
        workspace.requireStudyReadAccess(jwt, role, id);
        var report = jdbc.sql("""
            SELECT s.id, s.title FROM imaging_studies i JOIN studies s ON s.id=i.report_document_id
            WHERE i.id=:id AND s.patient_id=i.patient_id AND s.archived_at IS NULL
            """).param("id", id).query((rs, n) -> new Report(rs.getObject("id", UUID.class), rs.getString("title")))
            .optional().orElse(new Report(null, null));
        if (report.documentId() != null) workspace.requireStudyReadAccess(jwt, role, report.documentId());
        return report;
    }
    @PutMapping("/imaging-studies/{id}/report-document")
    @Transactional
    public void link(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="X-Workspace-Role", required=false) String role, @PathVariable UUID id,
        @RequestBody ReportInput input) {
        var actor = owner(jwt, role);
        workspace.requireStudyReadAccess(jwt, role, id);
        if (input.documentId() != null) {
            workspace.requireStudyReadAccess(jwt, role, input.documentId());
            if (id.equals(input.documentId()) || jdbc.sql("""
                SELECT count(*) FROM studies s WHERE s.id=:document AND s.patient_id=:patient
                AND s.archived_at IS NULL AND NOT EXISTS (SELECT 1 FROM imaging_studies i WHERE i.id=s.id)
                """).param("document", input.documentId()).param("patient", actor.patientId()).query(Integer.class).single() != 1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wybierz dokument tego samego pacjenta");
        }
        if (jdbc.sql("UPDATE imaging_studies SET report_document_id=:document WHERE id=:id AND patient_id=:patient")
            .param("document", input.documentId(), java.sql.Types.OTHER).param("id", id).param("patient", actor.patientId()).update() != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit.record(actor.id(), actor.patientId(), "STUDY", id, "UPDATE", true, null, Map.of("purpose", "LINK_REPORT"));
    }
}
