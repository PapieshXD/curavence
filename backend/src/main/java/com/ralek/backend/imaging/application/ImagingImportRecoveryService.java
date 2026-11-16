package com.ralek.backend.imaging.application;

import com.ralek.backend.imaging.infrastructure.ImagingUploadStager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class ImagingImportRecoveryService {

    private final JdbcClient jdbc;
    private final ImagingUploadStager stager;
    private final DicomImportService importer;
    private final TaskExecutor executor;

    public ImagingImportRecoveryService(JdbcClient jdbc, ImagingUploadStager stager,
                                        DicomImportService importer,
                                        @Qualifier("imagingTaskExecutor") TaskExecutor executor) {
        this.jdbc = jdbc;
        this.stager = stager;
        this.importer = importer;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterruptedImports() {
        jdbc.sql("""
                        SELECT upload.id upload_id, upload.study_id, upload.requested_by,
                               upload.staging_directory, imaging.patient_id, imaging.storage_provider,
                               study.title, study.performed_at
                        FROM imaging_uploads upload
                        JOIN imaging_studies imaging ON imaging.id = upload.study_id
                        JOIN studies study ON study.id = upload.study_id
                        WHERE upload.status IN ('UPLOADING', 'VALIDATING', 'STORING', 'VERIFYING')
                          AND upload.staging_directory IS NOT NULL
                        """)
                .query(this::job).list().forEach(this::resume);
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void discardExpiredStaging() {
        jdbc.sql("""
                        SELECT id, staging_directory FROM imaging_uploads
                        WHERE staging_directory IS NOT NULL AND expires_at < CURRENT_TIMESTAMP
                          AND status IN ('FAILED', 'CANCELLED', 'AVAILABLE')
                        """)
                .query((rs, rowNum) -> new ExpiredUpload(rs.getObject("id", UUID.class),
                        rs.getString("staging_directory"))).list().forEach(upload -> {
                    try {
                        stager.discard(Path.of(upload.stagingDirectory()));
                    } catch (RuntimeException ignored) { }
                    jdbc.sql("UPDATE imaging_uploads SET staging_directory = NULL WHERE id = :id")
                            .param("id", upload.id()).update();
                });
    }

    private void resume(RecoveryJob job) {
        try {
            var staged = stager.resume(Path.of(job.stagingDirectory()));
            jdbc.sql("""
                            UPDATE imaging_uploads SET retry_count = retry_count + 1,
                              status = 'UPLOADING', progress = 5, updated_at = CURRENT_TIMESTAMP
                            WHERE id = :id
                            """).param("id", job.uploadId()).update();
            executor.execute(() -> importer.importStudy(job.studyId(), job.uploadId(), job.patientId(),
                    job.requestedBy(), staged, job.title(), job.performedAt(), job.provider()));
        } catch (RuntimeException exception) {
            String message = "Nie można wznowić przerwanego importu";
            jdbc.sql("""
                            UPDATE imaging_uploads SET status = 'FAILED', progress = 100,
                              error_message = :error, completed_at = CURRENT_TIMESTAMP,
                              updated_at = CURRENT_TIMESTAMP, staging_directory = NULL
                            WHERE id = :id
                            """).param("error", message).param("id", job.uploadId()).update();
            jdbc.sql("""
                            UPDATE imaging_studies SET status = 'FAILED', error_message = :error,
                              updated_at = CURRENT_TIMESTAMP WHERE id = :id
                            """).param("error", message).param("id", job.studyId()).update();
        }
    }

    private RecoveryJob job(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime performedAt = rs.getObject("performed_at", OffsetDateTime.class);
        return new RecoveryJob(rs.getObject("upload_id", UUID.class), rs.getObject("study_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getObject("requested_by", UUID.class),
                rs.getString("staging_directory"), rs.getString("storage_provider"), rs.getString("title"),
                performedAt == null ? null : performedAt.toInstant());
    }

    private record RecoveryJob(UUID uploadId, UUID studyId, UUID patientId, UUID requestedBy,
                               String stagingDirectory, String provider, String title,
                               java.time.Instant performedAt) { }
    private record ExpiredUpload(UUID id, String stagingDirectory) { }
}
