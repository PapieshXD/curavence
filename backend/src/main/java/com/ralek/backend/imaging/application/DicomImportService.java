package com.ralek.backend.imaging.application;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.imaging.domain.DicomInstanceMetadata;
import com.ralek.backend.imaging.domain.DicomSeriesGeometry;
import com.ralek.backend.imaging.domain.DicomSeriesMetadata;
import com.ralek.backend.imaging.domain.DicomStudyMetadata;
import com.ralek.backend.imaging.domain.ImagingStudyAvailable;
import com.ralek.backend.imaging.infrastructure.DicomGeometryValidator;
import com.ralek.backend.imaging.infrastructure.DicomMetadataReader;
import com.ralek.backend.imaging.infrastructure.DicomStoragePort;
import com.ralek.backend.imaging.infrastructure.DicomStorageRegistry;
import com.ralek.backend.imaging.infrastructure.DicomUploadPreparer;
import com.ralek.backend.imaging.infrastructure.ImagingUploadStager.StagedUpload;
import com.ralek.backend.storage.ObjectStorageService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DicomImportService {

    private static final Set<String> SUPPORTED_MODALITIES = Set.of("MR", "CT", "US");

    private final JdbcClient jdbc;
    private final DicomUploadPreparer uploadPreparer;
    private final DicomMetadataReader metadataReader;
    private final DicomStorageRegistry storages;
    private final DicomGeometryValidator geometryValidator;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final ObjectStorageService objectStorage;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public DicomImportService(JdbcClient jdbc, DicomUploadPreparer uploadPreparer,
                              DicomMetadataReader metadataReader, DicomStorageRegistry storages,
                              DicomGeometryValidator geometryValidator, ObjectMapper objectMapper,
                              ApplicationEventPublisher events, ObjectStorageService objectStorage,
                              AuditService audit, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.uploadPreparer = uploadPreparer;
        this.metadataReader = metadataReader;
        this.storages = storages;
        this.geometryValidator = geometryValidator;
        this.objectMapper = objectMapper;
        this.events = events;
        this.objectStorage = objectStorage;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void importStudy(UUID studyId, UUID uploadId, UUID patientId, UUID actorId,
                            StagedUpload staged, String requestedDescription,
                            Instant requestedPerformedAt, String providerValue) {
        boolean completed = false;
        try {
            updateStatus(studyId, uploadId, "VALIDATING", 15, null, false);
            var niftiRequest = new com.ralek.backend.imaging.infrastructure.NiftiToDicomConverter.ConversionRequest(
                    patientId, requestedDescription, originalFilename(staged.files()), requestedPerformedAt);
            try (DicomUploadPreparer.PreparedUpload prepared = uploadPreparer.prepare(staged.files(), niftiRequest)) {
                List<DicomInstanceMetadata> instances = prepared.dicomFiles().stream()
                        .map(metadataReader::read).toList();
                ValidatedStudy validated = validate(instances, studyId, providerValue);
                updateValidatedCount(uploadId, instances.size());

                DicomStoragePort storage = storages.require(providerValue);
                updateStatus(studyId, uploadId, "STORING", 55, null, false);
                var stored = storage.store(prepared.dicomDirectory());
                if (stored.storedInstances() != instances.size()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Magazyn DICOM nie potwierdził zapisu wszystkich instancji");
                }
                updateStoredCount(uploadId, stored.storedInstances());
                UUID sourceAssetId = storeOriginalNifti(studyId, patientId, prepared);

                updateStatus(studyId, uploadId, "VERIFYING", 85, null, false);
                DicomStudyMetadata remoteStudy = verifyStoredStudy(storage, validated.studyInstanceUid(), instances.size());
                List<DicomSeriesMetadata> remoteSeries = storage.getSeries(validated.studyInstanceUid());
                Instant performedAt = firstNonNull(requestedPerformedAt,
                        firstNonNull(validated.performedAt(), remoteStudy.performedAt()));
                String description = firstNonBlank(validated.studyDescription(), remoteStudy.studyDescription());
                String bodyPart = firstNonBlank(validated.bodyPart(), remoteStudy.bodyPart());
                persistAvailable(studyId, uploadId, validated, description, bodyPart, performedAt,
                        remoteSeries, prepared.sourceFormat(), providerValue, sourceAssetId);
                events.publishEvent(new ImagingStudyAvailable(studyId, patientId, validated.studyInstanceUid()));
                completed = true;
            }
        } catch (RuntimeException exception) {
            String message = errorMessage(exception);
            updateStatus(studyId, uploadId, "FAILED", 100, message, true);
            audit.record(actorId, patientId, "IMAGING_STUDY", studyId, "UPLOAD", false,
                    message, Map.of("provider", providerValue));
        } finally {
            if (completed) {
                staged.close();
                jdbc.sql("UPDATE imaging_uploads SET staging_directory = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                        .param("id", uploadId).update();
            }
        }
    }

    private ValidatedStudy validate(List<DicomInstanceMetadata> instances, UUID currentStudyId,
                                    String provider) {
        if (instances.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie znaleziono instancji DICOM");
        }
        Set<String> studyUids = instances.stream().map(DicomInstanceMetadata::studyInstanceUid)
                .collect(Collectors.toSet());
        if (studyUids.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Przesłane pliki zawierają więcej niż jedno badanie DICOM");
        }
        Set<String> modalities = instances.stream().map(DicomInstanceMetadata::modality)
                .map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        if (modalities.size() != 1 || !SUPPORTED_MODALITIES.containsAll(modalities)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Import obsługuje jedno badanie DICOM o modalności MR, CT albo US (USG)");
        }
        Set<String> sopUids = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (DicomInstanceMetadata instance : instances) {
            if (!sopUids.add(instance.sopInstanceUid())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Przesłane pliki zawierają zduplikowany SOPInstanceUID");
            }
            if (!hashes.add(instance.sha256())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ten sam plik DICOM został przesłany więcej niż raz");
            }
        }
        String studyUid = studyUids.iterator().next();
        int duplicate = jdbc.sql("""
                        SELECT count(*) FROM imaging_studies
                        WHERE storage_provider = :provider AND study_instance_uid = :uid AND id <> :id
                        """)
                .param("provider", provider).param("uid", studyUid).param("id", currentStudyId)
                .query(Integer.class).single();
        if (duplicate > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Badanie o tym StudyInstanceUID jest już zapisane w wybranym magazynie");
        }

        Map<String, List<DicomInstanceMetadata>> grouped = instances.stream()
                .collect(Collectors.groupingBy(DicomInstanceMetadata::seriesInstanceUid,
                        LinkedHashMap::new, Collectors.toList()));
        List<ValidatedSeries> series = grouped.values().stream().map(group -> {
            DicomInstanceMetadata first = group.getFirst();
            DicomSeriesMetadata metadata = new DicomSeriesMetadata(first.seriesInstanceUid(), first.seriesNumber(),
                    first.seriesDescription(), first.sequenceName(), group.size());
            return new ValidatedSeries(metadata, geometryValidator.validate(group), List.copyOf(group));
        }).toList();

        return new ValidatedStudy(studyUid, modalities.iterator().next(),
                firstValue(instances, DicomInstanceMetadata::studyDescription),
                firstValue(instances, DicomInstanceMetadata::bodyPart),
                instances.stream().map(DicomInstanceMetadata::performedAt).filter(Objects::nonNull)
                        .findFirst().orElse(null), series,
                instances.stream().mapToLong(DicomInstanceMetadata::sizeBytes).sum());
    }

    private DicomStudyMetadata verifyStoredStudy(DicomStoragePort storage, String studyUid, int expected) {
        DicomStudyMetadata last = null;
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                last = storage.getStudy(studyUid);
                if (last.instanceCount() == 0 || last.instanceCount() == expected) return last;
            } catch (RuntimeException exception) {
                lastError = exception;
            }
            try {
                Thread.sleep(300L * (attempt + 1));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Przerwano weryfikację importu DICOM", exception);
            }
        }
        if (last == null && lastError != null) throw lastError;
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Liczba instancji w magazynie DICOM nie zgadza się z importem");
    }

    private void persistAvailable(UUID studyId, UUID uploadId, ValidatedStudy validated,
                                  String description, String bodyPart, Instant performedAt,
                                  List<DicomSeriesMetadata> remoteSeries, String sourceFormat,
                                  String provider, UUID sourceAssetId) {
        Map<String, DicomSeriesMetadata> remoteByUid = remoteSeries == null ? Map.of() : remoteSeries.stream()
                .collect(Collectors.toMap(DicomSeriesMetadata::seriesInstanceUid, Function.identity(), (a, b) -> a));
        transactions.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM imaging_series WHERE study_id = :study").param("study", studyId).update();
            for (ValidatedSeries value : validated.series()) {
                UUID seriesId = UUID.randomUUID();
                DicomSeriesMetadata remote = remoteByUid.get(value.metadata().seriesInstanceUid());
                DicomSeriesMetadata metadata = merge(value.metadata(), remote);
                DicomSeriesGeometry geometry = value.geometry();
                jdbc.sql("""
                                INSERT INTO imaging_series (
                                  id, study_id, series_instance_uid, series_number, series_description,
                                  sequence_name, image_count, modality, rows_count, columns_count,
                                  pixel_spacing_x, pixel_spacing_y, slice_thickness, calculated_spacing,
                                  orientation_json, frame_of_reference_uid, transfer_syntax_uid,
                                  rescale_slope, rescale_intercept, window_center, window_width,
                                  photometric_interpretation, bits_stored, pixel_representation,
                                  stack_eligible, mpr_eligible, volume_rendering_eligible, eligibility_reason
                                ) VALUES (
                                  :id, :study, :uid, :number, :description, :sequence, :images, :modality,
                                  :rows, :columns, :spacingX, :spacingY, :thickness, :calculatedSpacing,
                                  CAST(:orientation AS jsonb), :frame, :transferSyntax, :slope, :intercept,
                                  CAST(:windowCenter AS jsonb), CAST(:windowWidth AS jsonb), :photometric,
                                  :bitsStored, :pixelRepresentation, :stackEligible, :mprEligible,
                                  :volumeEligible, :eligibilityReason
                                )
                                """)
                        .param("id", seriesId).param("study", studyId).param("uid", metadata.seriesInstanceUid())
                        .param("number", metadata.seriesNumber(), Types.INTEGER)
                        .param("description", metadata.seriesDescription(), Types.VARCHAR)
                        .param("sequence", metadata.sequenceName(), Types.VARCHAR).param("images", metadata.imageCount())
                        .param("modality", geometry.modality()).param("rows", geometry.rows(), Types.INTEGER)
                        .param("columns", geometry.columns(), Types.INTEGER)
                        .param("spacingX", item(geometry.pixelSpacing(), 0), Types.DOUBLE)
                        .param("spacingY", item(geometry.pixelSpacing(), 1), Types.DOUBLE)
                        .param("thickness", geometry.sliceThickness(), Types.DOUBLE)
                        .param("calculatedSpacing", geometry.calculatedSpacing(), Types.DOUBLE)
                        .param("orientation", json(geometry.orientation())).param("frame", geometry.frameOfReferenceUid(), Types.VARCHAR)
                        .param("transferSyntax", geometry.transferSyntaxUid(), Types.VARCHAR)
                        .param("slope", geometry.rescaleSlope(), Types.DOUBLE)
                        .param("intercept", geometry.rescaleIntercept(), Types.DOUBLE)
                        .param("windowCenter", json(geometry.windowCenter())).param("windowWidth", json(geometry.windowWidth()))
                        .param("photometric", geometry.photometricInterpretation(), Types.VARCHAR)
                        .param("bitsStored", geometry.bitsStored(), Types.INTEGER)
                        .param("pixelRepresentation", geometry.pixelRepresentation(), Types.INTEGER)
                        .param("stackEligible", geometry.stackEligible()).param("mprEligible", geometry.mprEligible())
                        .param("volumeEligible", geometry.volumeRenderingEligible())
                        .param("eligibilityReason", geometry.eligibilityReason(), Types.VARCHAR).update();
                persistInstances(seriesId, value);
            }
            jdbc.sql("""
                            UPDATE imaging_studies
                            SET study_instance_uid = :uid, storage_provider = :provider,
                                provider_reference = :providerReference, modality = :modality,
                                study_description = :description, body_part = :bodyPart,
                                performed_at = :performedAt, series_count = :seriesCount,
                                instance_count = :instanceCount, source_format = :sourceFormat,
                                total_bytes = :totalBytes, status = 'AVAILABLE', error_message = NULL,
                                verified_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                                row_version = row_version + 1
                            WHERE id = :id
                            """)
                    .param("uid", validated.studyInstanceUid()).param("provider", provider)
                    .param("providerReference", validated.studyInstanceUid())
                    .param("modality", validated.modality()).param("description", description, Types.VARCHAR)
                    .param("bodyPart", bodyPart, Types.VARCHAR)
                    .param("performedAt", databaseTime(performedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("seriesCount", validated.series().size()).param("instanceCount", validated.instanceCount())
                    .param("sourceFormat", sourceFormat).param("totalBytes", validated.totalBytes())
                    .param("id", studyId).update();
            jdbc.sql("""
                            UPDATE imaging_uploads
                            SET status = 'AVAILABLE', progress = 100, error_message = NULL,
                                completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                            WHERE id = :id
                            """).param("id", uploadId).update();
            jdbc.sql("""
                            UPDATE studies
                            SET study_type_id = (SELECT id FROM study_types WHERE code = :modality),
                                performed_at = :performedAt, ingestion_status = 'READY',
                                updated_at = CURRENT_TIMESTAMP, metadata = CAST(:metadata AS jsonb)
                            WHERE id = :id
                            """)
                    .param("modality", studyTypeCode(validated.modality()))
                    .param("performedAt", databaseTime(performedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("metadata", metadataJson(validated, provider, sourceFormat, sourceAssetId))
                    .param("id", studyId).update();
        });
    }

    private void persistInstances(UUID seriesId, ValidatedSeries series) {
        for (DicomInstanceMetadata instance : series.instances()) {
            List<Double> position = instance.imagePositionPatient();
            jdbc.sql("""
                            INSERT INTO imaging_instances (
                              id, series_id, sop_instance_uid, sop_class_uid, instance_number,
                              position_x, position_y, position_z, orientation_json,
                              projected_slice_position, sha256, size_bytes, transfer_syntax_uid
                            ) VALUES (
                              :id, :series, :sop, :sopClass, :instanceNumber, :x, :y, :z,
                              CAST(:orientation AS jsonb), :projected, :sha, :size, :transferSyntax
                            )
                            """)
                    .param("id", UUID.randomUUID()).param("series", seriesId)
                    .param("sop", instance.sopInstanceUid()).param("sopClass", instance.sopClassUid(), Types.VARCHAR)
                    .param("instanceNumber", instance.instanceNumber(), Types.INTEGER)
                    .param("x", item(position, 0), Types.DOUBLE).param("y", item(position, 1), Types.DOUBLE)
                    .param("z", item(position, 2), Types.DOUBLE)
                    .param("orientation", json(instance.imageOrientationPatient()))
                    .param("projected", series.geometry().projectedPositions().get(instance.sopInstanceUid()), Types.DOUBLE)
                    .param("sha", instance.sha256()).param("size", instance.sizeBytes())
                    .param("transferSyntax", instance.transferSyntaxUid(), Types.VARCHAR).update();
        }
    }

    private DicomSeriesMetadata merge(DicomSeriesMetadata local, DicomSeriesMetadata remote) {
        if (remote == null) return local;
        return new DicomSeriesMetadata(local.seriesInstanceUid(),
                local.seriesNumber() != null ? local.seriesNumber() : remote.seriesNumber(),
                firstNonBlank(local.seriesDescription(), remote.seriesDescription()),
                firstNonBlank(local.sequenceName(), remote.sequenceName()),
                remote.imageCount() > 0 ? remote.imageCount() : local.imageCount());
    }

    private void updateStatus(UUID studyId, UUID uploadId, String status, int progress,
                              String error, boolean completed) {
        jdbc.sql("""
                        UPDATE imaging_studies SET status = :status, error_message = :error,
                          updated_at = CURRENT_TIMESTAMP WHERE id = :study
                        """)
                .param("status", status).param("error", error, Types.VARCHAR).param("study", studyId).update();
        jdbc.sql("""
                        UPDATE imaging_uploads SET status = :status, progress = :progress,
                          error_message = :error, updated_at = CURRENT_TIMESTAMP,
                          completed_at = CASE WHEN :completed THEN CURRENT_TIMESTAMP ELSE completed_at END
                        WHERE id = :upload
                        """)
                .param("status", status).param("progress", progress).param("error", error, Types.VARCHAR)
                .param("completed", completed).param("upload", uploadId).update();
        if ("FAILED".equals(status)) {
            jdbc.sql("UPDATE studies SET ingestion_status = 'FAILED', updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .param("id", studyId).update();
        }
    }

    private void updateValidatedCount(UUID uploadId, int count) {
        jdbc.sql("UPDATE imaging_uploads SET validated_files = :count, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("count", count).param("id", uploadId).update();
    }

    private void updateStoredCount(UUID uploadId, int count) {
        jdbc.sql("UPDATE imaging_uploads SET stored_files = :count, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("count", count).param("id", uploadId).update();
    }

    private String metadataJson(ValidatedStudy study, String provider, String sourceFormat, UUID sourceAssetId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("imagingProvider", provider);
        metadata.put("sourceFormat", sourceFormat);
        metadata.put("modality", study.modality());
        metadata.put("studyInstanceUid", study.studyInstanceUid());
        metadata.put("seriesCount", study.series().size());
        metadata.put("instanceCount", study.instanceCount());
        if (sourceAssetId != null) metadata.put("sourceAssetId", sourceAssetId.toString());
        return json(metadata);
    }

    private UUID storeOriginalNifti(UUID studyId, UUID patientId,
                                    DicomUploadPreparer.PreparedUpload prepared) {
        if (!"NIFTI".equals(prepared.sourceFormat()) || prepared.originalFile() == null) return null;
        UUID storageId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        String extension = prepared.originalFilename().toLowerCase(Locale.ROOT).endsWith(".nii.gz") ? "nii.gz" : "nii";
        String objectKey = "patients/%s/imaging/%s/%s/original.%s"
                .formatted(patientId, studyId, assetId, extension);
        try {
            long size = Files.size(prepared.originalFile());
            jdbc.sql("""
                            INSERT INTO storage_objects (
                              id, provider, bucket_name, object_key, original_filename, mime_type,
                              size_bytes, storage_status
                            ) VALUES (:id, 'MINIO', :bucket, :key, :filename, :mime, :size, 'PENDING')
                            """)
                    .param("id", storageId).param("bucket", objectStorage.originalBucket())
                    .param("key", objectKey).param("filename", prepared.originalFilename())
                    .param("mime", prepared.originalMimeType()).param("size", size).update();
            ObjectStorageService.StoredObject stored;
            try (var input = Files.newInputStream(prepared.originalFile())) {
                stored = objectStorage.putOriginal(objectKey, prepared.originalMimeType(), size, input);
            }
            jdbc.sql("""
                            UPDATE storage_objects SET bucket_name = :bucket, sha256 = :sha,
                              storage_status = 'STORED' WHERE id = :id
                            """)
                    .param("bucket", stored.bucket()).param("sha", stored.sha256()).param("id", storageId).update();
            jdbc.sql("""
                            INSERT INTO study_assets (
                              id, study_id, storage_object_id, asset_type, is_primary, version_number
                            ) VALUES (:id, :study, :storage, 'ORIGINAL', TRUE, 1)
                            """)
                    .param("id", assetId).param("study", studyId).param("storage", storageId).update();
            return assetId;
        } catch (IOException | RuntimeException exception) {
            jdbc.sql("UPDATE storage_objects SET storage_status = 'FAILED' WHERE id = :id")
                    .param("id", storageId).update();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Nie udało się zachować oryginalnego pliku NIfTI w MinIO", exception);
        }
    }

    private String errorMessage(RuntimeException exception) {
        String value = exception instanceof ResponseStatusException response ? response.getReason() : exception.getMessage();
        if (value == null || value.isBlank()) value = "Nie udało się zaimportować badania obrazowego";
        return value.substring(0, Math.min(value.length(), 1000));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (RuntimeException exception) { throw new IllegalStateException(exception); }
    }

    private Double item(List<Double> values, int index) {
        return values == null || values.size() <= index ? null : values.get(index);
    }

    private <T> T firstNonNull(T first, T second) { return first != null ? first : second; }
    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
    private String firstValue(List<DicomInstanceMetadata> instances,
                              Function<DicomInstanceMetadata, String> getter) {
        return instances.stream().map(getter).filter(Objects::nonNull).filter(value -> !value.isBlank())
                .findFirst().orElse(null);
    }
    private OffsetDateTime databaseTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
    private String originalFilename(MultipartFile[] files) {
        if (files != null && files.length == 1 && files[0] != null) {
            String name = files[0].getOriginalFilename();
            if (name != null && !name.isBlank()) return name;
        }
        return "IMAGING";
    }

    static String studyTypeCode(String dicomModality) {
        return switch (dicomModality) {
            case "MR" -> "MRI";
            case "CT" -> "CT";
            case "US" -> "ULTRASOUND";
            default -> throw new IllegalArgumentException("Unsupported DICOM modality: " + dicomModality);
        };
    }

    private record ValidatedSeries(DicomSeriesMetadata metadata, DicomSeriesGeometry geometry,
                                   List<DicomInstanceMetadata> instances) { }
    private record ValidatedStudy(String studyInstanceUid, String modality, String studyDescription,
                                  String bodyPart, Instant performedAt, List<ValidatedSeries> series,
                                  long totalBytes) {
        int instanceCount() { return series.stream().mapToInt(value -> value.instances().size()).sum(); }
    }
}
