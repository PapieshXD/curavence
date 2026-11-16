package com.ralek.backend.imaging.application;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.imaging.api.ImagingDtos.*;
import com.ralek.backend.workspace.UserContextService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import com.ralek.backend.imaging.infrastructure.DicomStorageRegistry;
import com.ralek.backend.imaging.infrastructure.ImagingUploadStager;
import com.ralek.backend.imaging.infrastructure.ImagingUploadStager.StagedUpload;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ImagingStudyService {

    private final JdbcClient jdbc;
    private final UserContextService contexts;
    private final ImagingAccessService access;
    private final DicomImportService importer;
    private final AuditService audit;
    private final ImagingUploadStager stager;
    private final DicomStorageRegistry storages;
    private final TaskExecutor taskExecutor;
    private final TransactionTemplate transactions;

    public ImagingStudyService(JdbcClient jdbc, UserContextService contexts,
                               ImagingAccessService access, DicomImportService importer,
                               AuditService audit, ImagingUploadStager stager,
                               DicomStorageRegistry storages,
                               @Qualifier("imagingTaskExecutor") TaskExecutor taskExecutor,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.access = access;
        this.importer = importer;
        this.audit = audit;
        this.stager = stager;
        this.storages = storages;
        this.taskExecutor = taskExecutor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ImagingStudyCreated create(Jwt jwt, String requestedView, UUID patientId,
                                      MultipartFile[] files, String description, Instant performedAt) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        if (actor.patientId() == null || !actor.patientId().equals(patientId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Badanie obrazowe może dodać wyłącznie pacjent będący jego właścicielem");
        }
        if (description == null || description.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nazwa badania jest wymagana");
        }
        if (files == null || files.length == 0 || Arrays.stream(files).allMatch(MultipartFile::isEmpty)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Wybierz plik NIfTI, ZIP lub pliki DICOM");
        }

        StagedUpload staged = stager.stage(files);
        String provider = storages.defaultProvider().name();
        UUID studyId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        OffsetDateTime databaseDate = databaseTime(performedAt == null ? Instant.now() : performedAt);
        String originalFilename = originalFilename(files);
        String sourceName = sourceName(files);

        try {
            transactions.executeWithoutResult(status -> {
                jdbc.sql("""
                        INSERT INTO studies (
                          id, patient_id, study_type_id, created_by, title, description,
                          source_name, performed_at, ingestion_status, clinical_status, metadata
                        ) VALUES (
                          :id, :patient, (SELECT id FROM study_types WHERE code = 'MRI'), :actor, :title, :description,
                          :sourceName, :performedAt, 'UPLOADING', 'AWAITING_REVIEW', '{}'::jsonb
                        )
                        """)
                .param("id", studyId).param("patient", patientId)
                .param("actor", actor.id()).param("title", description.trim())
                .param("description", description.trim())
                .param("sourceName", sourceName)
                        .param("performedAt", databaseDate, Types.TIMESTAMP_WITH_TIMEZONE).update();
                jdbc.sql("""
                        INSERT INTO imaging_studies (
                          id, patient_id, storage_provider, performed_at, status, created_by
                        ) VALUES (:id, :patient, :provider, :performedAt, 'UPLOADING', :actor)
                        """)
                .param("id", studyId).param("patient", patientId)
                .param("provider", provider)
                .param("performedAt", databaseDate, Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("actor", actor.id()).update();
                jdbc.sql("""
                        INSERT INTO imaging_uploads (
                          id, study_id, original_filename, status, progress, requested_by,
                          staging_directory, received_files, received_bytes, expires_at
                        ) VALUES (:id, :study, :filename, 'UPLOADING', 5, :actor,
                                  :staging, :files, :bytes, CURRENT_TIMESTAMP + INTERVAL '24 hours')
                        """)
                .param("id", uploadId).param("study", studyId).param("filename", originalFilename)
                .param("actor", actor.id()).param("staging", staged.root().toString())
                        .param("files", staged.files().length).param("bytes", staged.totalBytes()).update();
            });
            taskExecutor.execute(() -> importer.importStudy(studyId, uploadId, patientId, actor.id(), staged,
                    description.trim(), performedAt, provider));
            audit.record(actor.id(), patientId, "IMAGING_STUDY", studyId, "UPLOAD", true,
                    null, Map.of("provider", provider, "source", sourceName, "status", "ACCEPTED"));
            return new ImagingStudyCreated(studyId, "UPLOADING", 5);
        } catch (RuntimeException exception) {
            staged.close();
            audit.record(actor.id(), patientId, "IMAGING_STUDY", studyId, "UPLOAD", false,
                    exception.getMessage(), Map.of("provider", provider, "source", sourceName));
            throw exception;
        }
    }

    public List<ImagingStudySummary> list(Jwt jwt, String requestedView, UUID patientId) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        if (!access.canAccessPatient(actor.id(), patientId)) {
            throw denied(actor, patientId, null);
        }
        return jdbc.sql(summarySql() + " WHERE imaging.patient_id = :patient ORDER BY imaging.performed_at DESC, imaging.created_at DESC")
                .param("patient", patientId)
                .query(this::summary)
                .list().stream()
                .filter(item -> access.canView(actor.id(), item.id()))
                .toList();
    }

    public ImagingStudyDetails details(Jwt jwt, String requestedView, UUID studyId) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = requireStudy(studyId);
        requireAccess(actor, study);
        List<ImagingSeriesDto> series = jdbc.sql("""
                        SELECT id, series_instance_uid, series_number, series_description,
                               sequence_name, image_count, modality, rows_count, columns_count,
                               pixel_spacing_x, pixel_spacing_y, slice_thickness, calculated_spacing,
                               stack_eligible, mpr_eligible, volume_rendering_eligible, eligibility_reason
                        FROM imaging_series WHERE study_id = :study
                        ORDER BY series_number NULLS LAST, series_description, series_instance_uid
                        """)
                .param("study", studyId)
                .query((rs, rowNum) -> new ImagingSeriesDto(
                        rs.getObject("id", UUID.class), rs.getString("series_instance_uid"),
                        (Integer) rs.getObject("series_number"), rs.getString("series_description"),
                        rs.getString("sequence_name"), rs.getInt("image_count"), rs.getString("modality"),
                        (Integer) rs.getObject("rows_count"), (Integer) rs.getObject("columns_count"),
                        nullableDouble(rs, "pixel_spacing_x"), nullableDouble(rs, "pixel_spacing_y"),
                        nullableDouble(rs, "slice_thickness"), nullableDouble(rs, "calculated_spacing"),
                        rs.getBoolean("stack_eligible"), rs.getBoolean("mpr_eligible"),
                        rs.getBoolean("volume_rendering_eligible"), rs.getString("eligibility_reason")))
                .list();
        audit.record(actor.id(), study.patientId(), "IMAGING_STUDY", studyId, "VIEW", true,
                null, Map.of());
        return new ImagingStudyDetails(study, series);
    }

    public ImagingStudyStatus status(Jwt jwt, String requestedView, UUID studyId) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = requireStudy(studyId);
        requireAccess(actor, study);
        return jdbc.sql("""
                        SELECT imaging.id, imaging.status,
                               COALESCE(upload.progress, CASE WHEN imaging.status IN ('AVAILABLE', 'FAILED') THEN 100 ELSE 0 END) progress,
                               COALESCE(upload.error_message, imaging.error_message) error_message
                        FROM imaging_studies imaging
                        LEFT JOIN LATERAL (
                          SELECT progress, error_message FROM imaging_uploads
                          WHERE study_id = imaging.id ORDER BY created_at DESC LIMIT 1
                        ) upload ON TRUE
                        WHERE imaging.id = :id
                        """)
                .param("id", studyId)
                .query((rs, rowNum) -> new ImagingStudyStatus(
                        rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getInt("progress"), rs.getString("error_message")))
                .single();
    }

    public ImagingStudyCreated retry(Jwt jwt, String requestedView, UUID studyId) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = requireStudy(studyId);
        if (actor.patientId() == null || !actor.patientId().equals(study.patientId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ponowić import może wyłącznie właściciel badania");
        }
        RetryUpload upload = jdbc.sql("""
                        SELECT id, staging_directory FROM imaging_uploads
                        WHERE study_id = :study AND status = 'FAILED' AND staging_directory IS NOT NULL
                        ORDER BY created_at DESC LIMIT 1
                        """).param("study", studyId)
                .query((rs, rowNum) -> new RetryUpload(rs.getObject("id", UUID.class),
                        rs.getString("staging_directory"))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Brak danych roboczych umożliwiających ponowienie importu"));
        StagedUpload staged = stager.resume(java.nio.file.Path.of(upload.stagingDirectory()));
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            UPDATE imaging_uploads SET status = 'UPLOADING', progress = 5,
                              error_message = NULL, completed_at = NULL, retry_count = retry_count + 1,
                              updated_at = CURRENT_TIMESTAMP WHERE id = :id
                            """).param("id", upload.id()).update();
            jdbc.sql("""
                            UPDATE imaging_studies SET status = 'UPLOADING', error_message = NULL,
                              updated_at = CURRENT_TIMESTAMP WHERE id = :id
                            """).param("id", studyId).update();
            jdbc.sql("UPDATE studies SET ingestion_status = 'UPLOADING', updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .param("id", studyId).update();
        });
        taskExecutor.execute(() -> importer.importStudy(studyId, upload.id(), study.patientId(), actor.id(),
                staged, study.description(), study.performedAt(), study.storageProvider()));
        audit.record(actor.id(), study.patientId(), "IMAGING_STUDY", studyId, "UPLOAD", true,
                null, Map.of("provider", study.storageProvider(), "status", "RETRY"));
        return new ImagingStudyCreated(studyId, "UPLOADING", 5);
    }

    public ImagingStudySummary requireAccessible(UUID userId, UUID studyId) {
        ImagingStudySummary study = requireStudy(studyId);
        if (!access.canView(userId, studyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do badania obrazowego");
        }
        return study;
    }

    private ImagingStudySummary requireStudy(UUID studyId) {
        return jdbc.sql(summarySql() + " WHERE imaging.id = :id")
                .param("id", studyId).query(this::summary).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Nie znaleziono badania obrazowego"));
    }

    private void requireAccess(UserContext actor, ImagingStudySummary study) {
        if (!access.canView(actor.id(), study.id())) throw denied(actor, study.patientId(), study.id());
    }

    private ResponseStatusException denied(UserContext actor, UUID patientId, UUID studyId) {
        audit.record(actor.id(), patientId, "IMAGING_STUDY", studyId, "ACCESS_DENIED", false,
                "Brak aktywnego udostępnienia", Map.of());
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do badania obrazowego");
    }

    private String summarySql() {
        return """
                SELECT imaging.id, imaging.patient_id,
                       concat(owner_user.first_name, ' ', owner_user.last_name) patient_name,
                       imaging.study_instance_uid, imaging.modality,
                       COALESCE(imaging.study_description, study.title) description,
                       imaging.body_part, imaging.performed_at, imaging.series_count,
                       imaging.instance_count, imaging.storage_provider, imaging.source_format,
                       imaging.status, imaging.error_message
                FROM imaging_studies imaging
                JOIN studies study ON study.id = imaging.id
                JOIN patients patient ON patient.id = imaging.patient_id
                JOIN users owner_user ON owner_user.id = patient.user_id
                """;
    }

    private ImagingStudySummary summary(ResultSet rs, int rowNum) throws SQLException {
        return new ImagingStudySummary(
                rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getString("patient_name"), rs.getString("study_instance_uid"),
                rs.getString("modality"), rs.getString("description"), rs.getString("body_part"),
                instant(rs, "performed_at"), rs.getInt("series_count"), rs.getInt("instance_count"),
                rs.getString("storage_provider"), rs.getString("source_format"),
                rs.getString("status"), rs.getString("error_message"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private OffsetDateTime databaseTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private String originalFilename(MultipartFile[] files) {
        if (files.length == 1) {
            String name = files[0].getOriginalFilename();
            if (name != null && !name.isBlank()) return name.substring(0, Math.min(name.length(), 255));
        }
        return files.length + " plików obrazowych";
    }

    private String sourceName(MultipartFile[] files) {
        if (files.length == 1) {
            String name = files[0].getOriginalFilename();
            if (name != null) {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".nii") || lower.endsWith(".nii.gz")) return "Import NIfTI";
            }
        }
        return "Import DICOM";
    }

    private record RetryUpload(UUID id, String stagingDirectory) { }
}
