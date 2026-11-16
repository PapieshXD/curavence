package com.ralek.backend.imaging.analysis;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.imaging.application.ImagingStudyService;
import com.ralek.backend.workspace.UserContextService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imaging-studies/{studyId}")
public class StudyWorkspaceController {
    private final ImagingAnalysisService analyses;
    private final ImagingStudyService studies;
    private final UserContextService contexts;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public StudyWorkspaceController(ImagingAnalysisService analyses, ImagingStudyService studies,
            UserContextService contexts, JdbcClient jdbc, ObjectMapper mapper, AuditService audit) {
        this.analyses = analyses; this.studies = studies; this.contexts = contexts;
        this.jdbc = jdbc; this.mapper = mapper; this.audit = audit;
    }

    @GetMapping("/viewer-capabilities")
    public Map<String, Object> capabilities(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID studyId) {
        return analyses.capabilities(jwt, role, studyId);
    }

    @GetMapping("/view-preferences")
    public Preferences preferences(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID studyId) {
        var actor = contexts.resolve(jwt, role);
        studies.requireAccessible(actor.id(), studyId);
        return jdbc.sql("SELECT hidden_series_uids, last_layout FROM imaging_view_preferences WHERE user_id = :user AND study_id = :study")
                .param("user", actor.id()).param("study", studyId)
                .query((rs, row) -> new Preferences(mapper.readValue(rs.getString(1), String[].class), rs.getString(2)))
                .optional().orElse(new Preferences(null, "study"));
    }

    @PutMapping("/view-preferences")
    public Preferences preferences(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID studyId,
            @RequestBody Preferences body) {
        var actor = contexts.resolve(jwt, role);
        var details = studies.details(jwt, actor.effectiveView(), studyId);
        var available = details.series().stream().map(s -> s.seriesInstanceUid()).toList();
        if (body.hiddenSeriesUids() == null || body.hiddenSeriesUids().length > available.size()
                || !available.containsAll(List.of(body.hiddenSeriesUids()))
                || !List.of("study", "sheet", "slice", "advanced", "report").contains(body.lastLayout())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowe preferencje widoku");
        }
        jdbc.sql("""
                INSERT INTO imaging_view_preferences (user_id, study_id, hidden_series_uids, last_layout)
                VALUES (:user, :study, CAST(:hidden AS jsonb), :layout)
                ON CONFLICT (user_id, study_id) DO UPDATE SET hidden_series_uids = EXCLUDED.hidden_series_uids,
                  last_layout = EXCLUDED.last_layout
                """).param("user", actor.id()).param("study", studyId)
                .param("hidden", mapper.writeValueAsString(body.hiddenSeriesUids())).param("layout", body.lastLayout()).update();
        return body;
    }

    @GetMapping("/analysis-history")
    public List<Map<String, Object>> history(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID studyId) {
        var actor = contexts.resolve(jwt, role);
        studies.requireAccessible(actor.id(), studyId);
        return jdbc.sql("SELECT id, analysis_version, status FROM imaging_analyses WHERE study_id = :study ORDER BY analysis_version DESC")
                .param("study", studyId).query((rs, row) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class),
                        "version", rs.getInt(2), "status", rs.getString(3))).list();
    }

    @PatchMapping("/analysis/{analysisId}/scope")
    public ImagingAnalysisDtos.AnalysisCreated scope(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID studyId,
            @PathVariable UUID analysisId, @RequestBody ImagingAnalysisDtos.ScopeRequest body) {
        analyses.get(jwt, role, studyId, analysisId);
        // Scope edits branch only from the current immutable version.
        var latest = analyses.latest(jwt, role, studyId);
        if (latest.isEmpty() || !latest.get().id().equals(analysisId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zakres można zmienić na podstawie najnowszej analizy");
        }
        return analyses.start(jwt, role, studyId, body);
    }

    @PostMapping("/analysis/{analysisId}/feedback")
    public Map<String, Object> feedback(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID studyId,
            @PathVariable UUID analysisId, @RequestBody Feedback body) {
        var actor = contexts.resolve(jwt, role);
        if (!"doctor".equals(actor.effectiveView())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wymagana jest rola lekarza");
        var analysis = analyses.get(jwt, role, studyId, analysisId);
        if (!List.of("SUCCEEDED", "DEGRADED").contains(analysis.status()) || body.action() == null
                || !List.of("CONFIRMED", "REJECTED", "NEEDS_REVIEW", "CORRECTED").contains(body.action())
                || body.comment() != null && body.comment().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowa decyzja lub stan analizy");
        }
        boolean exists = false;
        for (JsonNode finding : analysis.candidateFindings()) if (finding.path("id").asText().equals(body.findingId())) exists = true;
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wskazanie nie należy do tej analizy");
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO imaging_analysis_feedback (id, analysis_id, author_id, finding_id, action, comment) VALUES (:id, :analysis, :author, :finding, :action, :comment)")
                .param("id", id).param("analysis", analysisId).param("author", actor.id()).param("finding", body.findingId())
                .param("action", body.action()).param("comment", body.comment(), java.sql.Types.VARCHAR).update();
        var study = studies.requireAccessible(actor.id(), studyId);
        audit.record(actor.id(), study.patientId(), "IMAGING_ANALYSIS_FEEDBACK", id, "CREATE", true, null,
                Map.of("analysisId", analysisId, "action", body.action()));
        return Map.of("id", id, "findingId", body.findingId(), "action", body.action());
    }

    @GetMapping("/analysis/{analysisId}/feedback")
    public List<Map<String, Object>> feedback(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID studyId,
            @PathVariable UUID analysisId) {
        var actor = contexts.resolve(jwt, role);
        if (!"doctor".equals(actor.effectiveView())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wymagana jest rola lekarza");
        analyses.get(jwt, role, studyId, analysisId);
        return jdbc.sql("SELECT id, finding_id, action, COALESCE(comment, '') AS comment FROM imaging_analysis_feedback WHERE analysis_id = :id ORDER BY created_at DESC, id")
                .param("id", analysisId).query((rs, row) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class),
                        "findingId", rs.getString(2), "action", rs.getString(3), "comment", rs.getString(4))).list();
    }

    public record Preferences(String[] hiddenSeriesUids, String lastLayout) { }
    public record Feedback(String findingId, String action, String comment) { }
}
