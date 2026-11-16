package com.ralek.backend.imaging.analysis;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.imaging.analysis.ImagingAnalysisDtos.ScopeRequest;
import com.ralek.backend.imaging.analysis.ImagingAnalysisDtos.AnalysisArtifactDto;
import com.ralek.backend.imaging.analysis.ImagingAnalysisDtos.AnalysisCreated;
import com.ralek.backend.imaging.analysis.ImagingAnalysisDtos.AnalysisDto;
import com.ralek.backend.imaging.analysis.ImagingAnalysisWorkerClient.WorkerArtifact;
import com.ralek.backend.imaging.analysis.ImagingAnalysisWorkerClient.WorkerOptions;
import com.ralek.backend.imaging.analysis.ImagingAnalysisWorkerClient.WorkerRequest;
import com.ralek.backend.imaging.analysis.ImagingAnalysisWorkerClient.WorkerResult;
import com.ralek.backend.imaging.analysis.ImagingAnalysisWorkerClient.WorkerSeries;
import com.ralek.backend.imaging.analysis.ImagingAnalysisWorkerClient.WorkerStudy;
import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudySummary;
import com.ralek.backend.imaging.application.ImagingStudyService;
import com.ralek.backend.imaging.application.ImagingAccessService;
import com.ralek.backend.storage.ObjectStorageService;
import com.ralek.backend.workspace.UserContextService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import io.minio.GetObjectResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ImagingAnalysisService {

    private final JdbcClient jdbc;
    private final UserContextService contexts;
    private final ImagingStudyService studies;
    private final ImagingAccessService access;
    private final ImagingAnalysisWorkerClient worker;
    private final ImagingAnalysisProperties properties;
    private final ObjectStorageService objectStorage;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;

    public ImagingAnalysisService(
            JdbcClient jdbc,
            UserContextService contexts,
            ImagingStudyService studies,
            ImagingAccessService access,
            ImagingAnalysisWorkerClient worker,
            ImagingAnalysisProperties properties,
            ObjectStorageService objectStorage,
            AuditService audit,
            ObjectMapper objectMapper,
            @Qualifier("analysisTaskExecutor") TaskExecutor executor,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.studies = studies;
        this.access = access;
        this.worker = worker;
        this.properties = properties;
        this.objectStorage = objectStorage;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AnalysisCreated start(Jwt jwt, String requestedView, UUID studyId) {
        return start(jwt, requestedView, studyId, new ScopeRequest(null, null, null));
    }

    public AnalysisCreated start(Jwt jwt, String requestedView, UUID studyId, ScopeRequest scope) {
        if (scope == null) scope = new ScopeRequest(null, null, null);
        final ScopeRequest requestedScope = scope;
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Automatyczna segmentacja jest wyłączona");
        }
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = studies.requireAccessible(actor.id(), studyId);
        requireClinician(actor);
        if (!List.of("ALL_ELIGIBLE", "SELECTED_SERIES", "REANALYZE").contains(scope.scopeMode()) || !"AUTO".equals(scope.pipeline())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Nieobsługiwany zakres lub pipeline analizy");
        }
        if ("SELECTED_SERIES".equals(scope.scopeMode())) {
            List<String> available = series(studyId).stream().map(WorkerSeries::seriesInstanceUid).toList();
            if (scope.seriesInstanceUids().isEmpty() || !available.containsAll(scope.seriesInstanceUids())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wybierz obrazy należące do tego badania");
            }
        } else if (!scope.seriesInstanceUids().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lista obrazów wymaga trybu SELECTED_SERIES");
        }
        if (!"AVAILABLE".equals(study.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Analizę można uruchomić dopiero po zakończeniu importu badania");
        }
        if (study.studyInstanceUid() == null || study.studyInstanceUid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Badanie nie ma StudyInstanceUID wymaganego do segmentacji");
        }
        Optional<AnalysisCreated> active = active(studyId);
        if (active.isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Analiza jest już w toku. Poczekaj przed zmianą zakresu.");

        UUID analysisId = UUID.randomUUID();
        try {
            transactions.executeWithoutResult(transaction -> {
            jdbc.sql("SELECT id FROM imaging_studies WHERE id = :id FOR UPDATE")
                    .param("id", studyId).query(UUID.class).single();
            if (active(studyId).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Analiza jest już w toku");
            jdbc.sql("""
                    INSERT INTO imaging_analyses (id, study_id, requested_by, status, progress, analysis_version, parent_analysis_id, analysis_scope)
                    SELECT :id, :study, :actor, 'QUEUED', 0, COALESCE(MAX(analysis_version), 0) + 1,
                      (SELECT id FROM imaging_analyses WHERE study_id = :study ORDER BY analysis_version DESC LIMIT 1), CAST(:scope AS jsonb)
                    FROM imaging_analyses WHERE study_id = :study
                    """)
                    .param("id", analysisId).param("study", studyId).param("actor", actor.id())
                    .param("scope", objectMapper.writeValueAsString(Map.of("selectionMode", requestedScope.scopeMode(),
                            "requestedSeries", requestedScope.seriesInstanceUids()))).update();
            jdbc.sql("""
                    INSERT INTO imaging_analysis_series (analysis_id, series_instance_uid, participation_status, role)
                    SELECT :id, series_instance_uid, 'DISCOVERED', 'UNKNOWN' FROM imaging_series WHERE study_id = :study
                    """).param("id", analysisId).param("study", studyId).update();
            });
        } catch (RuntimeException exception) {
            Optional<AnalysisCreated> concurrent = active(studyId);
            if (concurrent.isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Inne uruchomienie analizy jest już w toku.");
            throw exception;
        }
        try {
            executor.execute(() -> run(analysisId, study, requestedScope));
        } catch (RuntimeException rejected) {
            jdbc.sql("UPDATE imaging_analyses SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .param("id", analysisId).update();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kolejka analiz jest zajęta. Spróbuj ponownie.");
        }
        audit.record(actor.id(), study.patientId(), "IMAGING_ANALYSIS", analysisId, "CREATE", true,
                null, Map.of("studyId", studyId, "pipeline", "AUTO"));
        return new AnalysisCreated(analysisId, studyId, "QUEUED", 0);
    }

    public Optional<AnalysisDto> latest(Jwt jwt, String requestedView, UUID studyId) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = studies.requireAccessible(actor.id(), studyId);
        Optional<AnalysisDto> result = jdbc.sql(analysisSql() + " WHERE study_id = :study ORDER BY analysis_version DESC LIMIT 1")
                .param("study", studyId).query(this::analysisRow).optional().map(this::withArtifacts).map(value -> forAudience(value, actor));
        result.ifPresent(value -> audit.record(actor.id(), study.patientId(), "IMAGING_ANALYSIS",
                value.id(), "VIEW", true, null, Map.of("studyId", studyId)));
        return result;
    }

    public AnalysisDto get(Jwt jwt, String requestedView, UUID studyId, UUID analysisId) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = studies.requireAccessible(actor.id(), studyId);
        AnalysisDto result = jdbc.sql(analysisSql() + " WHERE id = :id AND study_id = :study")
                .param("id", analysisId).param("study", studyId)
                .query(this::analysisRow).optional().map(this::withArtifacts)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Nie znaleziono analizy obrazowej"));
        audit.record(actor.id(), study.patientId(), "IMAGING_ANALYSIS", analysisId, "VIEW", true,
                null, Map.of("studyId", studyId));
        return forAudience(result, actor);
    }

    public DownloadedArtifact downloadArtifact(Jwt jwt, String requestedView, UUID studyId,
                                                 UUID analysisId, UUID artifactId) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = studies.requireAccessible(actor.id(), studyId);
        if (!access.canViewArtifacts(actor.id(), studyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Zakres udostępnienia nie obejmuje plików segmentacji");
        }
        ArtifactRow artifact = jdbc.sql("""
                        SELECT artifact.id, object.bucket_name, object.object_key, object.original_filename,
                               object.mime_type, object.size_bytes
                        FROM imaging_analysis_artifacts artifact
                        JOIN imaging_analyses analysis ON analysis.id = artifact.analysis_id
                        JOIN storage_objects object ON object.id = artifact.storage_object_id
                        WHERE artifact.id = :artifact AND analysis.id = :analysis
                          AND analysis.study_id = :study AND object.storage_status = 'STORED'
                        """)
                .param("artifact", artifactId).param("analysis", analysisId).param("study", studyId)
                .query((rs, rowNum) -> new ArtifactRow(
                        rs.getObject("id", UUID.class), rs.getString("bucket_name"),
                        rs.getString("object_key"), rs.getString("original_filename"),
                        rs.getString("mime_type"), rs.getLong("size_bytes")))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Nie znaleziono artefaktu segmentacji"));
        GetObjectResponse stream = objectStorage.get(artifact.bucket(), artifact.objectKey());
        audit.record(actor.id(), study.patientId(), "IMAGING_ANALYSIS_ARTIFACT", artifact.id(),
                "DOWNLOAD", true, null, Map.of("analysisId", analysisId, "studyId", studyId));
        return new DownloadedArtifact(artifact.filename(), artifact.mimeType(), artifact.size(), stream);
    }

    private void run(UUID analysisId, ImagingStudySummary study, ScopeRequest scope) {
        updateRunning(analysisId);
        try {
            WorkerResult result = worker.analyze(new WorkerRequest(
                    analysisId.toString(),
                    new WorkerStudy(
                            study.studyInstanceUid(), study.storageProvider(), study.modality(),
                            study.bodyPart(), study.description(), series(study.id())),
                    new WorkerOptions(properties.isFast(), properties.getDevice(),
                            properties.isRunPathologyModels(), scope.scopeMode(), scope.seriesInstanceUids())
            ));
            boolean artifactsComplete = true;
            if (List.of("SUCCEEDED", "DEGRADED").contains(result.status())) {
                artifactsComplete = persistArtifacts(analysisId, study, result.artifacts());
            }
            persistResult(analysisId, result);
            if (!artifactsComplete) {
                jdbc.sql("""
                        UPDATE imaging_analyses SET status = 'DEGRADED',
                          warnings = warnings || CAST(:warning AS jsonb) WHERE id = :id
                        """).param("id", analysisId).param("warning", "[\"Część plików segmentacji jest niedostępna. Zachowano wynik analizy.\"]").update();
            }
            audit.record(null, study.patientId(), "IMAGING_ANALYSIS", analysisId,
                    "UPDATE", true, null, Map.of(
                            "studyId", study.id(), "pipeline", result.pipeline(), "status", artifactsComplete ? result.status() : "DEGRADED"));
        } catch (RuntimeException exception) {
            String message = "Nie udało się wykonać analizy. Obrazy pozostają dostępne do ręcznej oceny.";
            jdbc.sql("""
                    UPDATE imaging_analyses SET status = 'FAILED', progress = 100,
                      error_message = :error, completed_at = CURRENT_TIMESTAMP,
                      updated_at = CURRENT_TIMESTAMP WHERE id = :id
                    """).param("error", message).param("id", analysisId).update();
            jdbc.sql("UPDATE imaging_analysis_series SET participation_status = 'FAILED' WHERE analysis_id = :id AND participation_status IN ('DISCOVERED','ELIGIBLE')")
                    .param("id", analysisId).update();
            audit.record(null, study.patientId(), "IMAGING_ANALYSIS", analysisId, "UPDATE", false,
                    message.substring(0, Math.min(message.length(), 500)),
                    Map.of("studyId", study.id(), "status", "FAILED"));
        }
    }

    private List<WorkerSeries> series(UUID studyId) {
        return jdbc.sql("""
                        SELECT series_instance_uid, modality, series_description, sequence_name, image_count
                        FROM imaging_series WHERE study_id = :study
                        ORDER BY series_number NULLS LAST, series_instance_uid
                        """).param("study", studyId)
                .query((rs, rowNum) -> new WorkerSeries(
                        rs.getString("series_instance_uid"), rs.getString("modality"),
                        rs.getString("series_description"), rs.getString("sequence_name"),
                        rs.getInt("image_count"))).list();
    }

    private boolean persistArtifacts(UUID analysisId, ImagingStudySummary study, List<WorkerArtifact> artifacts) {
        if (artifacts == null) return true;
        boolean complete = true;
        for (WorkerArtifact artifact : artifacts) {
            try { persistArtifact(analysisId, study, artifact); }
            catch (RuntimeException unavailable) { complete = false; }
        }
        return complete;
    }

    private void persistArtifact(UUID analysisId, ImagingStudySummary study, WorkerArtifact artifact) {
        UUID artifactId;
        try { artifactId = UUID.fromString(artifact.id()); }
        catch (RuntimeException exception) { throw new IllegalStateException("Worker zwrócił nieprawidłowe ID artefaktu", exception); }
        String filename = safeFilename(artifact.filename());
        String mimeType = artifact.mimeType() == null || artifact.mimeType().isBlank()
                ? "application/octet-stream" : artifact.mimeType();
        Path temporary;
        try { temporary = Files.createTempFile("ralek-segmentation-", ".artifact"); }
        catch (IOException exception) { throw new IllegalStateException("Nie udało się utworzyć pliku roboczego", exception); }
        UUID storageId = UUID.randomUUID();
        try {
            long size = worker.downloadArtifact(analysisId.toString(), artifact, temporary);
            String objectKey = "patients/%s/imaging/%s/analyses/%s/%s-%s"
                    .formatted(study.patientId(), study.id(), analysisId, artifactId, filename);
            jdbc.sql("""
                    INSERT INTO storage_objects (
                      id, provider, bucket_name, object_key, original_filename, mime_type,
                      size_bytes, storage_status
                    ) VALUES (:id, 'MINIO', :bucket, :key, :filename, :mime, :size, 'PENDING')
                    """).param("id", storageId).param("bucket", objectStorage.derivedBucket())
                    .param("key", objectKey).param("filename", filename).param("mime", mimeType)
                    .param("size", size).update();
            ObjectStorageService.StoredObject stored;
            try (var input = Files.newInputStream(temporary)) {
                stored = objectStorage.putDerived(objectKey, mimeType, size, input);
            }
            UUID seriesId = seriesId(study.id(), artifact.seriesInstanceUid()).orElse(null);
            UUID finalArtifactId = artifactId;
            transactions.executeWithoutResult(status -> {
                jdbc.sql("""
                        UPDATE storage_objects SET bucket_name = :bucket, sha256 = :sha,
                          storage_status = 'STORED' WHERE id = :id
                        """).param("bucket", stored.bucket()).param("sha", stored.sha256())
                        .param("id", storageId).update();
                jdbc.sql("""
                        INSERT INTO imaging_analysis_artifacts (
                          id, analysis_id, series_id, storage_object_id, kind, label, slice_index
                        ) VALUES (:id, :analysis, :series, :storage, :kind, :label, :slice)
                        """).param("id", finalArtifactId).param("analysis", analysisId)
                        .param("series", seriesId, Types.OTHER).param("storage", storageId)
                        .param("kind", artifact.kind()).param("label", artifact.label())
                        .param("slice", artifact.sliceIndex(), Types.INTEGER).update();
            });
        } catch (RuntimeException | IOException exception) {
            jdbc.sql("UPDATE storage_objects SET storage_status = 'FAILED' WHERE id = :id")
                    .param("id", storageId).update();
            throw exception instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Nie udało się utrwalić artefaktu segmentacji", exception);
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private Optional<UUID> seriesId(UUID studyId, String seriesInstanceUid) {
        if (seriesInstanceUid == null || seriesInstanceUid.isBlank()) return Optional.empty();
        return jdbc.sql("SELECT id FROM imaging_series WHERE study_id = :study AND series_instance_uid = :uid")
                .param("study", studyId).param("uid", seriesInstanceUid).query(UUID.class).optional();
    }

    private void persistResult(UUID analysisId, WorkerResult result) {
        String status = List.of("SUCCEEDED", "DEGRADED", "FAILED", "UNSUPPORTED").contains(result.status())
                ? result.status() : "FAILED";
        transactions.executeWithoutResult(transaction -> {
        if (result.seriesResults() != null && result.seriesResults().isArray()) {
            for (JsonNode series : result.seriesResults()) {
                jdbc.sql("INSERT INTO imaging_analysis_series (analysis_id, series_instance_uid, participation_status, role) VALUES (:id, :uid, :status, :role) ON CONFLICT (analysis_id, series_instance_uid) DO UPDATE SET participation_status = EXCLUDED.participation_status, role = EXCLUDED.role")
                    .param("id", analysisId).param("uid", series.path("seriesInstanceUid").asText())
                    .param("status", series.path("analysisStatus").asText()).param("role", series.path("role").asText()).update();
            }
        }
        jdbc.sql("""
                UPDATE imaging_analyses SET pipeline = :pipeline, engine = :engine,
                  status = :status, progress = 100,
                  analysis_scope = CAST(:scope AS jsonb), canonical_evidence = CAST(:evidence AS jsonb),
                  evidence_hash = :hash, explanations = CAST(:explanations AS jsonb), prompt_version = :prompt,
                  classification = CAST(:classification AS jsonb),
                  quality = CAST(:quality AS jsonb),
                  measurements = CAST(:measurements AS jsonb),
                  observations = CAST(:observations AS jsonb),
                  candidate_findings = CAST(:findings AS jsonb),
                  model_versions = CAST(:versions AS jsonb), warnings = CAST(:warnings AS jsonb),
                  summary = :summary, disclaimer = COALESCE(:disclaimer, disclaimer),
                  error_message = NULL, completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("scope", json(result.scope(), "{}")).param("evidence", json(result.canonicalEvidence(), "{}"))
                .param("hash", result.evidenceHash(), Types.VARCHAR).param("explanations", json(result.explanations(), "{}"))
                .param("prompt", result.promptVersion(), Types.VARCHAR)
                .param("pipeline", result.pipeline()).param("engine", result.engine())
                .param("status", status).param("classification", json(result.classification(), "{}"))
                .param("quality", json(result.quality(), "{}"))
                .param("measurements", json(result.measurements(), "[]"))
                .param("observations", json(result.observations(), "[]"))
                .param("findings", json(result.candidateFindings(), "[]"))
                .param("versions", json(result.modelVersions(), "{}"))
                .param("warnings", json(result.warnings(), "[]"))
                .param("summary", result.summary(), Types.VARCHAR)
                .param("disclaimer", result.disclaimer(), Types.VARCHAR).param("id", analysisId).update();
        });
    }

    private void updateRunning(UUID analysisId) {
        jdbc.sql("""
                UPDATE imaging_analyses SET status = 'RUNNING', progress = 10,
                  started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = :id
                """).param("id", analysisId).update();
    }

    private Optional<AnalysisCreated> active(UUID studyId) {
        return jdbc.sql("""
                        SELECT id, study_id, status, progress FROM imaging_analyses
                        WHERE study_id = :study AND status IN ('QUEUED', 'RUNNING')
                        ORDER BY created_at DESC LIMIT 1
                        """).param("study", studyId)
                .query((rs, rowNum) -> new AnalysisCreated(
                        rs.getObject("id", UUID.class), rs.getObject("study_id", UUID.class),
                        rs.getString("status"), rs.getInt("progress"))).optional();
    }

    private String analysisSql() {
        return """
                SELECT id, study_id, pipeline, engine, status, progress, classification, quality,
                       measurements, observations, candidate_findings, model_versions, warnings,
                       summary, disclaimer, error_message, created_at, started_at, completed_at,
                       analysis_version, parent_analysis_id, analysis_scope, evidence_hash
                FROM imaging_analyses
                """;
    }

    private AnalysisDto analysisRow(ResultSet rs, int rowNum) throws SQLException {
        return new AnalysisDto(
                rs.getObject("id", UUID.class), rs.getObject("study_id", UUID.class),
                rs.getString("pipeline"), rs.getString("engine"), rs.getString("status"),
                rs.getInt("progress"), tree(rs.getString("classification"), "{}"),
                tree(rs.getString("quality"), "{}"), tree(rs.getString("measurements"), "[]"),
                tree(rs.getString("observations"), "[]"), tree(rs.getString("candidate_findings"), "[]"),
                tree(rs.getString("model_versions"), "{}"), tree(rs.getString("warnings"), "[]"),
                rs.getString("summary"), rs.getString("disclaimer"), rs.getString("error_message"),
                List.of(), instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "completed_at"),
                rs.getInt("analysis_version"), rs.getObject("parent_analysis_id", UUID.class),
                tree(rs.getString("analysis_scope"), "{}"), tree("[]", "[]"), rs.getString("evidence_hash"), null, tree("{}", "{}"));
    }

    private AnalysisDto withArtifacts(AnalysisDto value) {
        List<AnalysisArtifactDto> artifacts = jdbc.sql("""
                        SELECT artifact.id, artifact.kind, artifact.label, artifact.slice_index,
                               artifact.series_id, object.original_filename, object.mime_type, object.size_bytes
                        FROM imaging_analysis_artifacts artifact
                        JOIN storage_objects object ON object.id = artifact.storage_object_id
                        WHERE artifact.analysis_id = :analysis AND object.storage_status = 'STORED'
                        ORDER BY artifact.created_at, artifact.id
                        """).param("analysis", value.id())
                .query((rs, rowNum) -> new AnalysisArtifactDto(
                        rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("label"),
                        rs.getString("original_filename"), rs.getString("mime_type"),
                        rs.getLong("size_bytes"), (Integer) rs.getObject("slice_index"),
                        rs.getObject("series_id", UUID.class))).list();
        return new AnalysisDto(value.id(), value.studyId(), value.pipeline(), value.engine(), value.status(),
                value.progress(), value.classification(), value.quality(), value.measurements(), value.observations(),
                value.candidateFindings(), value.modelVersions(), value.warnings(), value.summary(), value.disclaimer(),
                value.errorMessage(), artifacts, value.createdAt(), value.startedAt(), value.completedAt(),
                value.analysisVersion(), value.parentAnalysisId(), value.scope(), value.seriesResults(),
                value.evidenceHash(), value.audience(), value.explanation());
    }

    public void validateAudience(Jwt jwt, String role, String audience) {
        if (audience == null) return;
        var actor = contexts.resolve(jwt, role);
        String expected = switch (actor.effectiveView()) {
            case "doctor" -> "CLINICIAN";
            case "therapy" -> "PHYSIOTHERAPY";
            default -> "PATIENT";
        };
        if (!expected.equals(audience)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Profil wyjaśnienia nie odpowiada uprawnieniom sesji");
    }

    private void requireClinician(UserContext actor) {
        if (!"doctor".equals(actor.effectiveView())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Zakres analizy może zmieniać wyłącznie lekarz");
        }
    }

    public Map<String, Object> capabilities(Jwt jwt, String view, UUID studyId) {
        UserContext actor = contexts.resolve(jwt, view);
        var details = studies.details(jwt, actor.effectiveView(), studyId);
        return Map.of("audience", "doctor".equals(actor.effectiveView()) ? "CLINICIAN" : "therapy".equals(actor.effectiveView()) ? "PHYSIOTHERAPY" : "PATIENT",
                "canChangeAnalysisScope", "doctor".equals(actor.effectiveView()),
                "canStartAnalysis", properties.isEnabled() && "doctor".equals(actor.effectiveView()),
                "series", details.series().stream().map(series -> Map.of("id", series.id(), "mprEligible", series.mprEligible(),
                        "volumeRenderingEligible", series.volumeRenderingEligible())).toList());
    }

    private AnalysisDto forAudience(AnalysisDto value, UserContext actor) {
        String audience = switch (actor.effectiveView()) {
            case "doctor" -> "CLINICIAN";
            case "therapy" -> "PHYSIOTHERAPY";
            default -> "PATIENT";
        };
        JsonNode explanation = jdbc.sql("SELECT explanations FROM imaging_analyses WHERE id = :id")
                .param("id", value.id()).query(String.class).optional()
                .map(raw -> tree(raw, "{}").path(audience)).orElse(tree("{}", "{}"));
        if (explanation.isMissingNode() || explanation.isNull() || explanation.isEmpty()) {
            String seen = List.of("SUCCEEDED", "DEGRADED").contains(value.status())
                    ? "Dostępny jest archiwalny wynik automatyczny. Omów go ze specjalistą."
                    : "Wynik analizy nie jest jeszcze dostępny. Obrazy można ocenić niezależnie.";
            List<String> limitations = List.of(value.disclaimer(), "Ta wersja nie zawiera pełnej ewidencji zakresu analizy.");
            explanation = switch (audience) {
                case "CLINICIAN" -> objectMapper.valueToTree(Map.of("technicalSummary", seen,
                        "limitations", limitations, "requiresHumanReview", true));
                case "PHYSIOTHERAPY" -> objectMapper.valueToTree(Map.of("clinicalContext", seen,
                        "functionalRelevance", "Sam obraz nie określa sprawności ani dopuszczalnego obciążenia.",
                        "limitations", limitations));
                default -> objectMapper.valueToTree(Map.of("headline", "Co pokazuje badanie?", "whatWasSeen", seen,
                        "whatItDoesNotMean", "Samo oznaczenie kolorem nie oznacza rozpoznania choroby.",
                        "questionsForDoctor", List.of("Czy wynik wyjaśnia moje objawy?"), "limitations", limitations));
            };
        }
        List<Map<String, String>> participation = jdbc.sql("SELECT series_instance_uid, participation_status, role FROM imaging_analysis_series WHERE analysis_id = :id ORDER BY series_instance_uid")
                .param("id", value.id()).query((rs, row) -> Map.of("seriesInstanceUid", rs.getString(1),
                        "analysisStatus", rs.getString(2), "role", rs.getString(3))).list();
        boolean clinical = "CLINICIAN".equals(audience);
        var audienceQuality = objectMapper.createObjectNode();
        for (String field : List.of("status", "seriesCount", "assessedSlices", "totalSlices")) {
            if (value.quality().has(field)) audienceQuality.set(field, value.quality().get(field));
        }
        return new AnalysisDto(value.id(), value.studyId(), value.pipeline(), value.engine(), value.status(), value.progress(),
                clinical ? value.classification() : tree("{}", "{}"), clinical ? value.quality() : audienceQuality,
                "PATIENT".equals(audience) ? tree("[]", "[]") : value.measurements(),
                clinical ? value.observations() : tree("[]", "[]"), clinical ? value.candidateFindings() : tree("[]", "[]"),
                clinical ? value.modelVersions() : tree("{}", "{}"), clinical ? value.warnings() : tree("[]", "[]"),
                clinical ? value.summary() : null, value.disclaimer(), value.errorMessage() == null ? null : "Nie udało się zakończyć analizy. Obrazy pozostają dostępne.",
                clinical ? value.artifacts() : List.of(), value.createdAt(), value.startedAt(), value.completedAt(),
                value.analysisVersion(), value.parentAnalysisId(), value.scope(), objectMapper.valueToTree(participation),
                value.evidenceHash(), audience, explanation);
    }

    private String json(JsonNode value, String fallback) {
        return value == null || value.isNull() ? fallback : objectMapper.writeValueAsString(value);
    }

    private JsonNode tree(String value, String fallback) {
        return objectMapper.readTree(value == null ? fallback : value);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private String errorMessage(RuntimeException exception) {
        String value = exception instanceof ResponseStatusException response ? response.getReason() : exception.getMessage();
        if (value == null || value.isBlank()) value = "Nie udało się wykonać automatycznej segmentacji";
        return value.substring(0, Math.min(value.length(), 1000));
    }

    private String safeFilename(String value) {
        String normalized = value == null ? "segmentation.bin" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")) return "segmentation.bin";
        return normalized.substring(0, Math.min(normalized.length(), 200));
    }

    public record DownloadedArtifact(String filename, String mimeType, long size, GetObjectResponse stream) { }
    private record ArtifactRow(UUID id, String bucket, String objectKey, String filename,
                               String mimeType, long size) { }
}
