package com.ralek.backend.workspace;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.storage.ObjectStorageService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import com.ralek.backend.workspace.WorkspaceDtos.*;
import io.minio.GetObjectResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkspaceService {

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png");
    private static final Set<String> ALLOWED_SCOPES = Set.of(
            "FULL_STUDY", "SELECTED_STUDIES", "LAB_RESULTS", "DIAGNOSTIC_REPORT",
            "REHABILITATION_SUMMARY", "TRAINING_RESTRICTIONS");

    private final JdbcClient jdbc;
    private final UserContextService contextService;
    private final AuditService auditService;
    private final ObjectStorageService objectStorage;

    public WorkspaceService(JdbcClient jdbc, UserContextService contextService,
                            AuditService auditService, ObjectStorageService objectStorage) {
        this.jdbc = jdbc;
        this.contextService = contextService;
        this.auditService = auditService;
        this.objectStorage = objectStorage;
    }

    public WorkspaceResponse workspace(Jwt jwt, String requestedView) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        List<PatientDto> patients = patients(actor);
        List<StudyDto> studies = studies(actor);
        List<GrantDto> grants = grants(actor);
        List<AuditDto> audit = auditEvents(actor);
        List<ReviewDto> reviews = reviews(actor);
        long awaiting = studies.stream().filter(study -> "AWAITING_REVIEW".equals(study.clinicalStatus())).count();
        long activeGrants = grants.stream().filter(grant -> "ACTIVE".equals(grant.status())).count();

        return new WorkspaceResponse(
                new SessionDto(actor.id(), actor.email(), actor.firstName(), actor.lastName(),
                        actor.displayName(), actor.roles(), actor.availableViews(), actor.superuser(),
                        actor.effectiveView()),
                new OverviewDto(patients.size(), studies.size(), activeGrants, audit.size(), awaiting),
                profile(actor),
                patients,
                studies,
                grants,
                audit,
                reviews,
                studyTypes(),
                recipients(actor)
        );
    }

    public UUID uploadStudy(Jwt jwt, String requestedView, MultipartFile file, String title,
                            String studyTypeCode, UUID requestedPatientId, Instant performedAt) {
        validateFile(file);
        String mimeType = file.getContentType();
        PreparedUpload upload = new PreparedUpload(
                file.getOriginalFilename(), mimeType, file.getSize(), file::getInputStream, "{}", List.of());
        return persistStudy(jwt, requestedView, upload, title, studyTypeCode, requestedPatientId, performedAt)
                .studyId();
    }

    public StoredStudy uploadPreparedStudy(Jwt jwt, String requestedView, PreparedUpload upload,
                                           String title, String studyTypeCode, UUID requestedPatientId,
                                           Instant performedAt) {
        if (upload == null || upload.size() == 0 || upload.source() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wybierz plik");
        }
        return persistStudy(jwt, requestedView, upload, title, studyTypeCode, requestedPatientId, performedAt);
    }

    private StoredStudy persistStudy(Jwt jwt, String requestedView, PreparedUpload upload,
                                     String title, String studyTypeCode, UUID requestedPatientId,
                                     Instant performedAt) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        UUID patientId = writablePatient(actor, requestedPatientId);
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nazwa badania jest wymagana");
        }
        String normalizedTitle = title.trim();
        if (normalizedTitle.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nazwa badania może mieć maksymalnie 255 znaków");
        }
        UUID studyTypeId = jdbc.sql("SELECT id FROM study_types WHERE code = :code")
                .param("code", studyTypeCode == null || studyTypeCode.isBlank() ? "MEDICAL_DOCUMENT" : studyTypeCode)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieznany typ badania"));

        UUID studyId = UUID.randomUUID();
        UUID primaryAssetId = UUID.randomUUID();
        List<PendingAsset> assets = new ArrayList<>();
        assets.add(new PendingAsset(primaryAssetId, UUID.randomUUID(), true, "ORIGINAL",
                objectStorage.originalBucket(),
                "patients/%s/studies/%s/%s/original.%s".formatted(
                        patientId, studyId, primaryAssetId, extension(upload.mimeType())),
                safeFilename(upload.filename(), upload.mimeType()), upload.mimeType(), upload.size(), upload.source()));
        for (DerivedAsset derived : upload.derivedAssets()) {
            UUID assetId = UUID.randomUUID();
            assets.add(new PendingAsset(assetId, UUID.randomUUID(), false, derived.assetType(),
                    objectStorage.derivedBucket(),
                    "patients/%s/studies/%s/%s/derived.%s".formatted(
                            patientId, studyId, assetId, derived.extension()),
                    safeDerivedFilename(derived.filename(), derived.extension()), derived.mimeType(),
                    derived.content().length, () -> new ByteArrayInputStream(derived.content())));
        }

        jdbc.sql("""
                        INSERT INTO studies (
                            id, patient_id, study_type_id, created_by, title, source_name, performed_at,
                            ingestion_status, clinical_status, metadata
                        ) VALUES (:id, :patient, :type, :actor, :title, 'Przesłane przez użytkownika',
                                  :performedAt, 'UPLOADING', 'AWAITING_REVIEW', CAST(:metadata AS JSONB))
                        """)
                .param("id", studyId)
                .param("patient", patientId)
                .param("type", studyTypeId)
                .param("actor", actor.id())
                .param("title", normalizedTitle)
                .param("performedAt", databaseTime(performedAt == null ? Instant.now() : performedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("metadata", upload.metadataJson() == null || upload.metadataJson().isBlank()
                        ? "{}" : upload.metadataJson())
                .update();
        for (PendingAsset asset : assets) {
            jdbc.sql("""
                            INSERT INTO storage_objects (
                                id, provider, bucket_name, object_key, original_filename, mime_type,
                                size_bytes, storage_status
                            ) VALUES (:id, 'MINIO', :bucket, :objectKey, :filename, :mimeType,
                                      :size, 'PENDING')
                            """)
                    .param("id", asset.storageId())
                    .param("bucket", asset.bucket())
                    .param("objectKey", asset.objectKey())
                    .param("filename", asset.filename())
                    .param("mimeType", asset.mimeType())
                    .param("size", asset.size())
                    .update();
        }

        try {
            for (PendingAsset asset : assets) {
                ObjectStorageService.StoredObject stored;
                try (InputStream input = asset.source().open()) {
                    stored = asset.primary()
                            ? objectStorage.putOriginal(asset.objectKey(), asset.mimeType(), asset.size(), input)
                            : objectStorage.putDerived(asset.objectKey(), asset.mimeType(), asset.size(), input);
                }
                jdbc.sql("""
                                UPDATE storage_objects
                                SET bucket_name = :bucket, sha256 = :sha256, storage_status = 'STORED'
                                WHERE id = :id
                                """)
                        .param("bucket", stored.bucket())
                        .param("sha256", stored.sha256())
                        .param("id", asset.storageId())
                        .update();
                jdbc.sql("""
                                INSERT INTO study_assets (
                                    id, study_id, storage_object_id, asset_type, is_primary, version_number
                                ) VALUES (:id, :study, :storage, :assetType, :primary, 1)
                                """)
                        .param("id", asset.assetId())
                        .param("study", studyId)
                        .param("storage", asset.storageId())
                        .param("assetType", asset.assetType())
                        .param("primary", asset.primary())
                        .update();
            }
            jdbc.sql("UPDATE studies SET ingestion_status = 'READY', updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .param("id", studyId)
                    .update();
            auditService.record(actor.id(), patientId, "STUDY", studyId, "UPLOAD", true,
                    null, Map.of("assetId", primaryAssetId, "mimeType", upload.mimeType(),
                            "sizeBytes", upload.size(), "derivedAssets", upload.derivedAssets().size()));
            return new StoredStudy(studyId, primaryAssetId);
        } catch (IOException | RuntimeException exception) {
            for (PendingAsset asset : assets) {
                jdbc.sql("""
                                UPDATE storage_objects SET storage_status = 'FAILED'
                                WHERE id = :id AND storage_status = 'PENDING'
                                """)
                        .param("id", asset.storageId()).update();
            }
            jdbc.sql("UPDATE studies SET ingestion_status = 'FAILED', updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                    .param("id", studyId).update();
            auditService.record(actor.id(), patientId, "STUDY", studyId, "UPLOAD", false,
                    "Błąd zapisu w magazynie obiektowym", Map.of("mimeType", upload.mimeType()));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Nie udało się zapisać pliku w MinIO");
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public UUID createGrant(Jwt jwt, String requestedView, CreateGrantRequest request) {
        return createGrant(jwt, requestedView, request, false);
    }

    UUID createVisitGrant(Jwt jwt, String requestedView, CreateGrantRequest request) {
        return createGrant(jwt, requestedView, request, true);
    }

    private UUID createGrant(Jwt jwt, String requestedView, CreateGrantRequest request, boolean contextOnlyAllowed) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        UUID patientId = writablePatient(actor, null);
        String scope = request.scope().toUpperCase();
        if (!ALLOWED_SCOPES.contains(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieobsługiwany zakres udostępnienia");
        }
        UUID recipientId = jdbc.sql("SELECT id FROM users WHERE lower(email) = lower(:email) AND status = 'ACTIVE'")
                .param("email", request.recipientEmail())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie znaleziono aktywnego odbiorcy"));
        if (recipientId.equals(actor.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie można udostępnić danych samemu sobie");
        }
        List<UUID> studyIds = request.studyIds() == null ? List.of() : request.studyIds();
        if ("SELECTED_STUDIES".equals(scope) && studyIds.isEmpty() && !contextOnlyAllowed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wybierz co najmniej jedno badanie");
        }
        for (UUID studyId : studyIds) {
            boolean belongsToPatient = jdbc.sql("SELECT count(*) FROM studies WHERE id = :id AND patient_id = :patient")
                    .param("id", studyId).param("patient", patientId).query(Integer.class).single() == 1;
            if (!belongsToPatient) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Badanie nie należy do wybranego pacjenta");
            }
        }

        UUID grantId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO access_grants (
                            id, patient_id, recipient_user_id, granted_by, scope, purpose,
                            expires_at, status
                        ) VALUES (:id, :patient, :recipient, :actor, :scope, :purpose,
                                  :expiresAt, 'ACTIVE')
                        """)
                .param("id", grantId).param("patient", patientId).param("recipient", recipientId)
                .param("actor", actor.id()).param("scope", scope)
                .param("purpose", request.purpose())
                .param("expiresAt", databaseTime(request.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        for (UUID studyId : studyIds) {
            jdbc.sql("INSERT INTO access_grant_studies (grant_id, study_id) VALUES (:grant, :study)")
                    .param("grant", grantId).param("study", studyId).update();
        }
        auditService.record(actor.id(), patientId, "ACCESS_GRANT", grantId, "SHARE", true,
                null, Map.of("recipientUserId", recipientId, "scope", scope));
        return grantId;
    }

    @org.springframework.transaction.annotation.Transactional
    public void revokeGrant(Jwt jwt, String requestedView, UUID grantId) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        GrantOwner owner = jdbc.sql("SELECT patient_id, granted_by FROM access_grants WHERE id = :id")
                .param("id", grantId)
                .query((rs, rowNum) -> new GrantOwner(
                        rs.getObject("patient_id", UUID.class), rs.getObject("granted_by", UUID.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono udostępnienia"));
        if (!actor.superuser() && !actor.administrator()
                && !owner.patientId().equals(actor.patientId()) && !owner.grantedBy().equals(actor.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak prawa do cofnięcia tego dostępu");
        }
        jdbc.sql("""
                        UPDATE access_grants
                        SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND status <> 'REVOKED'
                        """)
                .param("id", grantId).update();
        auditService.record(actor.id(), owner.patientId(), "ACCESS_GRANT", grantId,
                "REVOKE_ACCESS", true, null, Map.of());
    }

    public UUID addReview(Jwt jwt, String requestedView, UUID studyId, ReviewRequest request) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        if (!actor.superuser() && !actor.administrator() && !actor.effectiveView().equals("doctor")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Opis kliniczny może dodać lekarz lub diagnosta");
        }
        UUID patientId = patientIdForVisibleStudy(actor, studyId);
        UUID reviewId = UUID.randomUUID();
        String status = request.signed() ? "SIGNED" : "DRAFT";
        String summaryHtml = ReviewHtmlSanitizer.sanitize(request.summaryHtml());
        String conclusionHtml = ReviewHtmlSanitizer.sanitize(request.conclusionHtml());
        jdbc.sql("""
                        INSERT INTO clinical_reviews (
                            id, study_id, reviewer_id, status, summary, conclusion, summary_html, conclusion_html, signed_at
                        ) VALUES (:id, :study, :reviewer, :status, :summary, :conclusion, :summaryHtml, :conclusionHtml, :signedAt)
                        """)
                .param("id", reviewId).param("study", studyId).param("reviewer", actor.id())
                .param("status", status).param("summary", request.summary().trim())
                .param("conclusion", request.conclusion())
                .param("summaryHtml", summaryHtml).param("conclusionHtml", conclusionHtml)
                .param("signedAt", databaseTime(request.signed() ? Instant.now() : null), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        jdbc.sql("UPDATE studies SET clinical_status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("status", request.signed() ? "SIGNED" : "REVIEWED")
                .param("id", studyId).update();
        auditService.record(actor.id(), patientId, "CLINICAL_REVIEW", reviewId,
                request.signed() ? "SIGN" : "CREATE", true, null, Map.of("studyId", studyId));
        return reviewId;
    }

    public void requireReviewAccess(Jwt jwt, String requestedView, UUID studyId) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        if (!actor.superuser() && !actor.administrator() && !actor.effectiveView().equals("doctor")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Opis kliniczny może dodać lekarz lub diagnosta");
        }
        patientIdForVisibleStudy(actor, studyId);
    }

    public void updatePatient(Jwt jwt, String requestedView, UpdatePatientRequest request) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        UUID patientId = writablePatient(actor, null);
        jdbc.sql("UPDATE patients SET phone = :phone, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("phone", request.phone().trim()).param("id", patientId).update();
        auditService.record(actor.id(), patientId, "PATIENT", patientId, "UPDATE", true,
                null, Map.of("field", "phone"));
    }

    @org.springframework.transaction.annotation.Transactional
    public void updateDocument(Jwt jwt, String requestedView, UUID documentId, UpdateDocumentRequest request) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        UUID patientId = patientIdForEditableStudy(actor, documentId);
        var old = jdbc.sql("SELECT s.*,st.code AS type_code FROM studies s JOIN study_types st ON st.id=s.study_type_id WHERE s.id=:id FOR UPDATE OF s")
            .param("id",documentId).query((rs,n)->new Object[]{rs.getString("title"),instant(rs,"performed_at"),rs.getString("type_code"),rs.getLong("metadata_revision")}).single();
        if(request.revision()!=null && request.revision()!=(long)old[3])
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Dane zmieniły się. Odśwież dokument przed zapisem.");
        String title=request.title().trim();
        if(title.isBlank() || title.length()>255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Nazwa musi mieć od 1 do 255 znaków");
        String category=request.typeCode()==null ? (String)old[2] : request.typeCode();
        Set<String> editable=Set.of("MEDICAL_DOCUMENT","LABORATORY","ULTRASOUND","MRI","CT","XRAY","OTHER");
        if(!category.equals(old[2]) && (!editable.contains(category) || !editable.contains(old[2]) ||
            jdbc.sql("SELECT count(*) FROM imaging_studies WHERE id=:id").param("id",documentId).query(Integer.class).single()>0 ||
            jdbc.sql("SELECT count(*) FROM study_assets sa JOIN storage_objects so ON so.id=sa.storage_object_id WHERE sa.study_id=:id AND sa.asset_type='ORIGINAL' AND so.mime_type='application/pdf'").param("id",documentId).query(Integer.class).single()==0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ta zmiana kategorii wymaga innego sposobu przetwarzania");
        jdbc.sql("""
            INSERT INTO document_metadata_history(id,study_id,actor_id,old_title,new_title,old_performed_at,new_performed_at,old_type_code,new_type_code)
            VALUES (:id,:study,:actor,:oldTitle,:title,:oldDate,:date,:oldType,:type)
            """).param("id",UUID.randomUUID()).param("study",documentId).param("actor",actor.id())
            .param("oldTitle",old[0]).param("title",title).param("oldDate",databaseTime((Instant)old[1]),Types.TIMESTAMP_WITH_TIMEZONE)
            .param("date",databaseTime(request.performedAt()),Types.TIMESTAMP_WITH_TIMEZONE).param("oldType",old[2]).param("type",category).update();
        jdbc.sql("""
            UPDATE studies SET title=:title,performed_at=:date,study_type_id=(SELECT id FROM study_types WHERE code=:type),
            metadata_revision=metadata_revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND archived_at IS NULL
            """).param("id",documentId).param("title",title).param("date",databaseTime(request.performedAt()),Types.TIMESTAMP_WITH_TIMEZONE)
            .param("type",category).update();
        auditService.record(actor.id(), patientId, "STUDY", documentId, "UPDATE", true,
                null, Map.of("operation", "DOCUMENT_METADATA",
                        "fields", List.of("title", "performedAt")));
    }

    public void archiveDocument(Jwt jwt, String requestedView, UUID documentId) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        UUID patientId = patientIdForEditableStudy(actor, documentId);
        int updated = jdbc.sql("""
                        UPDATE studies
                        SET archived_ingestion_status=ingestion_status, ingestion_status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND archived_at IS NULL
                        """)
                .param("id", documentId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono dokumentu");
        }
        auditService.record(actor.id(), patientId, "STUDY", documentId, "DELETE", true,
                null, Map.of("operation", "SOFT_ARCHIVE"));
    }

    public DownloadedAsset ecgOriginal(Jwt jwt, String requestedView, UUID documentId) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        patientIdForVisibleStudy(actor, documentId);
        UUID assetId = jdbc.sql("""
                SELECT sa.id FROM studies s
                JOIN study_types st ON st.id = s.study_type_id
                JOIN study_assets sa ON sa.study_id = s.id AND sa.asset_type = 'ORIGINAL'
                WHERE s.id = :id AND s.archived_at IS NULL AND st.code IN ('EKG', 'ECG')
                ORDER BY sa.created_at LIMIT 1
                """).param("id", documentId).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono źródłowego pliku EKG"));
        return downloadAsset(jwt, requestedView, assetId, false);
    }

    public UUID requireStudyReadAccess(Jwt jwt, String requestedView, UUID studyId) {
        return patientIdForVisibleStudy(contextService.resolve(jwt, requestedView), studyId);
    }

    public DocumentContent documentContent(Jwt jwt, String requestedView, UUID documentId) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        UUID patientId = patientIdForVisibleStudy(actor, documentId);
        TextAssetRow textAsset = jdbc.sql("""
                        SELECT s.title, so.bucket_name, so.object_key
                        FROM studies s
                        JOIN study_assets sa ON sa.study_id = s.id AND sa.asset_type = 'EXTRACTED_TEXT'
                        JOIN storage_objects so ON so.id = sa.storage_object_id
                        WHERE s.id = :id AND s.archived_at IS NULL AND so.storage_status = 'STORED'
                        ORDER BY sa.created_at DESC
                        LIMIT 1
                        """)
                .param("id", documentId)
                .query((rs, rowNum) -> new TextAssetRow(
                        rs.getString("title"), rs.getString("bucket_name"), rs.getString("object_key")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Dokument nie ma warstwy tekstowej. Najpierw uruchom OCR."));
        try (InputStream stream = objectStorage.get(textAsset.bucket(), textAsset.objectKey())) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Dokument nie zawiera tekstu, na podstawie którego można odpowiedzieć.");
            }
            auditService.record(actor.id(), patientId, "STUDY", documentId, "VIEW", true,
                    null, Map.of("purpose", "DOCUMENT_QUESTION"));
            return new DocumentContent(documentId, patientId, textAsset.title(), text);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Nie udało się odczytać tekstu dokumentu", exception);
        }
    }

    public DownloadedAsset downloadAsset(Jwt jwt, String requestedView, UUID assetId, boolean download) {
        UserContext actor = contextService.resolve(jwt, requestedView);
        AssetRow asset = jdbc.sql("""
                        SELECT sa.id, sa.study_id, s.patient_id, so.bucket_name, so.object_key,
                               so.original_filename, so.mime_type, so.size_bytes
                        FROM study_assets sa
                        JOIN studies s ON s.id = sa.study_id
                        JOIN storage_objects so ON so.id = sa.storage_object_id
                        WHERE sa.id = :id AND so.storage_status = 'STORED'
                        """)
                .param("id", assetId)
                .query((rs, rowNum) -> new AssetRow(
                        rs.getObject("id", UUID.class), rs.getObject("study_id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getString("bucket_name"),
                        rs.getString("object_key"), rs.getString("original_filename"),
                        rs.getString("mime_type"), rs.getLong("size_bytes")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono pliku"));
        patientIdForVisibleStudy(actor, asset.studyId());
        GetObjectResponse stream = objectStorage.get(asset.bucket(), asset.objectKey());
        auditService.record(actor.id(), asset.patientId(), "STUDY_ASSET", asset.id(),
                download ? "DOWNLOAD" : "VIEW", true, null, Map.of("studyId", asset.studyId()));
        return new DownloadedAsset(asset.filename(), asset.mimeType(), asset.size(), stream);
    }

    private ProfileDto profile(UserContext actor) {
        UUID patientId = actor.patientView() ? effectivePatientId(actor) : null;
        if (patientId == null) {
            return null;
        }
        return jdbc.sql("""
                        SELECT p.id, u.id AS user_id, u.first_name, u.last_name, u.email,
                               p.date_of_birth, p.biological_sex, p.phone
                        FROM patients p JOIN users u ON u.id = p.user_id
                        WHERE p.id = :id
                        """)
                .param("id", patientId)
                .query((rs, rowNum) -> new ProfileDto(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"),
                        rs.getObject("date_of_birth", java.time.LocalDate.class),
                        rs.getString("biological_sex"), rs.getString("phone")))
                .optional().orElse(null);
    }

    private List<PatientDto> patients(UserContext actor) {
        String condition = patientVisibility(actor, "p");
        return statement("""
                        SELECT p.id, u.id AS user_id, u.first_name, u.last_name, u.email,
                               p.date_of_birth, p.biological_sex, p.phone, count(s.id) AS study_count
                        FROM patients p
                        JOIN users u ON u.id = p.user_id
                        LEFT JOIN studies s ON s.patient_id = p.id AND s.archived_at IS NULL
                        WHERE %s
                        GROUP BY p.id, u.id, u.first_name, u.last_name, u.email,
                                 p.date_of_birth, p.biological_sex, p.phone
                        ORDER BY u.last_name, u.first_name
                        """.formatted(condition), actor)
                .query((rs, rowNum) -> new PatientDto(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"),
                        rs.getObject("date_of_birth", java.time.LocalDate.class),
                        rs.getString("biological_sex"), rs.getString("phone"), rs.getLong("study_count")))
                .list();
    }

    private List<StudyDto> studies(UserContext actor) {
        return statement("""
                        SELECT s.id, s.patient_id, concat(pu.first_name, ' ', pu.last_name) AS patient_name,
                               st.code AS type_code, st.name AS type_name, s.title, s.description,
                               s.source_name, s.performed_at, s.ingestion_status, s.clinical_status,
                               asset.asset_id, asset.original_filename, asset.mime_type, asset.size_bytes,
                               EXISTS (
                                   SELECT 1
                                   FROM study_assets text_asset
                                   JOIN storage_objects text_object ON text_object.id = text_asset.storage_object_id
                                   WHERE text_asset.study_id = s.id
                                     AND text_asset.asset_type = 'EXTRACTED_TEXT'
                                     AND text_object.storage_status = 'STORED'
                               ) AS has_extracted_text,
                               s.metadata -> 'documentProcessing' ->> 'processingStatus'
                                   AS document_processing_status,
                               imaging.id AS imaging_study_id, s.metadata_revision
                        FROM studies s
                        JOIN patients p ON p.id = s.patient_id
                        JOIN users pu ON pu.id = p.user_id
                        JOIN study_types st ON st.id = s.study_type_id
                        LEFT JOIN imaging_studies imaging ON imaging.id = s.id
                        LEFT JOIN LATERAL (
                            SELECT sa.id AS asset_id, so.original_filename, so.mime_type, so.size_bytes
                            FROM study_assets sa
                            JOIN storage_objects so ON so.id = sa.storage_object_id
                            WHERE sa.study_id = s.id AND so.storage_status = 'STORED'
                            ORDER BY sa.is_primary DESC, sa.created_at DESC
                            LIMIT 1
                        ) asset ON TRUE
                        WHERE s.archived_at IS NULL AND %s
                        ORDER BY s.performed_at DESC NULLS LAST, s.created_at DESC
                        """.formatted(studyVisibility(actor, "s")), actor)
                .query((rs, rowNum) -> new StudyDto(
                        rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("patient_name"), rs.getString("type_code"), rs.getString("type_name"),
                        rs.getString("title"), rs.getString("description"), rs.getString("source_name"),
                        instant(rs, "performed_at"), rs.getString("ingestion_status"),
                        rs.getString("clinical_status"), rs.getObject("asset_id", UUID.class),
                        rs.getString("original_filename"), rs.getString("mime_type"),
                        nullableLong(rs, "size_bytes"), rs.getBoolean("has_extracted_text"),
                        rs.getString("document_processing_status"),
                        rs.getObject("imaging_study_id", UUID.class), rs.getLong("metadata_revision")))
                .list();
    }

    private List<GrantDto> grants(UserContext actor) {
        String condition;
        if (actor.superuser() || actor.administrator()) {
            condition = "TRUE";
        } else if (actor.patientView()) {
            condition = "ag.patient_id = :patientId";
        } else {
            condition = "ag.recipient_user_id = :actorId";
        }
        return statement("""
                        SELECT ag.id, ag.patient_id, concat(pu.first_name, ' ', pu.last_name) AS patient_name,
                               ru.id AS recipient_id, concat(ru.first_name, ' ', ru.last_name) AS recipient_name,
                               ru.email AS recipient_email, coalesce(pr.professional_type, 'USER') AS recipient_role,
                               ag.scope, ag.purpose, ag.created_at, ag.expires_at, ag.status
                        FROM access_grants ag
                        JOIN patients p ON p.id = ag.patient_id
                        JOIN users pu ON pu.id = p.user_id
                        JOIN users ru ON ru.id = ag.recipient_user_id
                        LEFT JOIN practitioners pr ON pr.user_id = ru.id
                        WHERE %s
                        ORDER BY ag.created_at DESC
                        """.formatted(condition), actor)
                .query((rs, rowNum) -> new GrantDto(
                        rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("patient_name"), rs.getObject("recipient_id", UUID.class),
                        rs.getString("recipient_name"), rs.getString("recipient_email"),
                        rs.getString("recipient_role"), rs.getString("scope"), rs.getString("purpose"),
                        instant(rs, "created_at"), instant(rs, "expires_at"), rs.getString("status")))
                .list();
    }

    private List<AuditDto> auditEvents(UserContext actor) {
        String condition;
        if (actor.superuser() || actor.administrator()) {
            condition = "TRUE";
        } else if (actor.patientView()) {
            condition = "ae.patient_id = :patientId";
        } else {
            condition = "ae.actor_user_id = :actorId OR EXISTS (SELECT 1 FROM access_grants ag WHERE ag.patient_id = ae.patient_id AND ag.recipient_user_id = :actorId AND ag.status = 'ACTIVE' AND (ag.expires_at IS NULL OR ag.expires_at > CURRENT_TIMESTAMP))";
        }
        return statement("""
                        SELECT ae.id, concat(coalesce(au.first_name, 'System'), ' ', coalesce(au.last_name, '')) AS actor_name,
                               ae.patient_id, concat(pu.first_name, ' ', pu.last_name) AS patient_name,
                               ae.resource_type, ae.resource_id, ae.action, ae.success,
                               ae.denial_reason, ae.created_at
                        FROM audit_events ae
                        LEFT JOIN users au ON au.id = ae.actor_user_id
                        LEFT JOIN patients p ON p.id = ae.patient_id
                        LEFT JOIN users pu ON pu.id = p.user_id
                        WHERE %s
                        ORDER BY ae.created_at DESC
                        LIMIT 100
                        """.formatted(condition), actor)
                .query((rs, rowNum) -> new AuditDto(
                        rs.getObject("id", UUID.class), rs.getString("actor_name"),
                        rs.getObject("patient_id", UUID.class), rs.getString("patient_name"),
                        rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                        rs.getString("action"), rs.getBoolean("success"), rs.getString("denial_reason"),
                        instant(rs, "created_at")))
                .list();
    }

    private List<ReviewDto> reviews(UserContext actor) {
        return statement("""
                        SELECT cr.id, cr.study_id, s.title AS study_title,
                               concat(u.first_name, ' ', u.last_name) AS reviewer_name,
                               cr.status, cr.summary, cr.conclusion, cr.summary_html, cr.conclusion_html, cr.updated_at, cr.signed_at
                        FROM clinical_reviews cr
                        JOIN studies s ON s.id = cr.study_id
                        JOIN users u ON u.id = cr.reviewer_id
                        WHERE %s
                        ORDER BY cr.updated_at DESC
                        """.formatted(studyVisibility(actor, "s")), actor)
                .query((rs, rowNum) -> new ReviewDto(
                        rs.getObject("id", UUID.class), rs.getObject("study_id", UUID.class),
                        rs.getString("study_title"), rs.getString("reviewer_name"), rs.getString("status"),
                        rs.getString("summary"), rs.getString("conclusion"),
                        rs.getString("summary_html"), rs.getString("conclusion_html"),
                        instant(rs, "updated_at"), instant(rs, "signed_at")))
                .list();
    }

    private List<StudyTypeDto> studyTypes() {
        return jdbc.sql("SELECT code, name FROM study_types ORDER BY name")
                .query((rs, rowNum) -> new StudyTypeDto(rs.getString("code"), rs.getString("name")))
                .list();
    }

    private List<UserOptionDto> recipients(UserContext actor) {
        if (!actor.patientView() && !actor.administrator() && !actor.superuser()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT u.id, concat(u.first_name, ' ', u.last_name) AS name,
                               u.email, p.professional_type
                        FROM practitioners p JOIN users u ON u.id = p.user_id
                        WHERE u.status = 'ACTIVE'
                        ORDER BY u.last_name, u.first_name
                        """)
                .query((rs, rowNum) -> new UserOptionDto(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
                        rs.getString("professional_type")))
                .list();
    }

    private UUID writablePatient(UserContext actor, UUID requestedPatientId) {
        if (actor.patientView()) {
            UUID patientId = effectivePatientId(actor);
            if (patientId != null) return patientId;
        }
        if (actor.superuser() || actor.administrator()) {
            if (requestedPatientId != null) {
                boolean exists = jdbc.sql("SELECT count(*) FROM patients WHERE id = :id")
                        .param("id", requestedPatientId).query(Integer.class).single() == 1;
                if (exists) return requestedPatientId;
            }
            return jdbc.sql("SELECT id FROM patients ORDER BY created_at LIMIT 1")
                    .query(UUID.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Brak pacjenta w bazie"));
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ta rola nie może modyfikować danych pacjenta");
    }

    private UUID effectivePatientId(UserContext actor) {
        if (actor.patientId() != null) return actor.patientId();
        if (actor.superuser()) {
            return jdbc.sql("SELECT id FROM patients ORDER BY created_at LIMIT 1")
                    .query(UUID.class).optional().orElse(null);
        }
        return null;
    }

    private UUID patientIdForVisibleStudy(UserContext actor, UUID studyId) {
        UUID patientId = jdbc.sql("SELECT patient_id FROM studies WHERE id = :id AND archived_at IS NULL")
                .param("id", studyId).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono badania"));
        if (actor.superuser() || actor.administrator()) return patientId;
        if (actor.patientView() && patientId.equals(actor.patientId())) return patientId;
        if (actor.practitionerView()) {
            int access = jdbc.sql("""
                            SELECT count(*) FROM access_grants ag
                            WHERE ag.patient_id = :patient AND ag.recipient_user_id = :actor
                              AND ag.status = 'ACTIVE'
                              AND (ag.expires_at IS NULL OR ag.expires_at > CURRENT_TIMESTAMP)
                              AND (
                                  ag.scope = 'FULL_STUDY'
                                  OR (ag.scope IN ('SELECTED_STUDIES', 'REHABILITATION_SUMMARY') AND EXISTS (
                                      SELECT 1 FROM access_grant_studies gs
                                      WHERE gs.grant_id = ag.id AND gs.study_id = :study))
                                  OR (ag.scope = 'LAB_RESULTS' AND EXISTS (
                                      SELECT 1 FROM studies scoped_study JOIN study_types scoped_type ON scoped_type.id = scoped_study.study_type_id
                                      WHERE scoped_study.id = :study AND (
                                          scoped_type.code = 'LABORATORY'
                                          OR (jsonb_exists(scoped_study.metadata, 'clinicalResult')
                                              AND COALESCE(scoped_study.metadata -> 'clinicalResult' ->> 'presentation', 'parameters') = 'parameters'))))
                                  OR (ag.scope = 'DIAGNOSTIC_REPORT' AND (
                                      EXISTS (SELECT 1 FROM studies report_study
                                          WHERE report_study.id = :study
                                            AND report_study.metadata -> 'clinicalResult' ->> 'presentation' = 'report')
                                      OR EXISTS (SELECT 1 FROM clinical_reviews scoped_review
                                          WHERE scoped_review.study_id = :study)))
                              )
                            """)
                    .param("patient", patientId).param("actor", actor.id()).param("study", studyId)
                    .query(Integer.class).single();
            if (access > 0) return patientId;
        }
        auditService.record(actor.id(), patientId, "STUDY", studyId, "ACCESS_DENIED", false,
                "Brak aktywnego udostępnienia", Map.of());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do badania");
    }

    private UUID patientIdForEditableStudy(UserContext actor, UUID studyId) {
        UUID patientId = jdbc.sql("SELECT patient_id FROM studies WHERE id = :id AND archived_at IS NULL")
                .param("id", studyId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Nie znaleziono dokumentu"));
        if (actor.superuser() || actor.administrator()) return patientId;
        if (actor.patientView() && patientId.equals(actor.patientId())) return patientId;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tylko właściciel dokumentu lub administrator może nim zarządzać");
    }

    private String patientVisibility(UserContext actor, String patientAlias) {
        if (actor.superuser() || actor.administrator()) return "TRUE";
        if (actor.patientView()) return patientAlias + ".id = :patientId";
        return "EXISTS (SELECT 1 FROM access_grants ag WHERE ag.patient_id = " + patientAlias
                + ".id AND ag.recipient_user_id = :actorId AND ag.status = 'ACTIVE' "
                + "AND (ag.expires_at IS NULL OR ag.expires_at > CURRENT_TIMESTAMP))";
    }

    String studyVisibility(UserContext actor, String studyAlias) {
        if (actor.superuser() || actor.administrator()) return "TRUE";
        if (actor.patientView()) return studyAlias + ".patient_id = :patientId";
        return "EXISTS (SELECT 1 FROM access_grants ag WHERE ag.patient_id = " + studyAlias
                + ".patient_id AND ag.recipient_user_id = :actorId AND ag.status = 'ACTIVE' "
                + "AND (ag.expires_at IS NULL OR ag.expires_at > CURRENT_TIMESTAMP) "
                + "AND (ag.scope = 'FULL_STUDY' "
                + "OR (ag.scope IN ('SELECTED_STUDIES', 'REHABILITATION_SUMMARY') AND EXISTS (SELECT 1 FROM access_grant_studies gs "
                + "WHERE gs.grant_id = ag.id AND gs.study_id = " + studyAlias + ".id)) "
                + "OR (ag.scope = 'LAB_RESULTS' AND (EXISTS (SELECT 1 FROM study_types scoped_type "
                + "WHERE scoped_type.id = " + studyAlias + ".study_type_id AND scoped_type.code = 'LABORATORY') "
                + "OR (jsonb_exists(" + studyAlias + ".metadata, 'clinicalResult') AND COALESCE(" + studyAlias
                + ".metadata -> 'clinicalResult' ->> 'presentation', 'parameters') = 'parameters'))) "
                + "OR (ag.scope = 'DIAGNOSTIC_REPORT' AND " + studyAlias
                + ".metadata -> 'clinicalResult' ->> 'presentation' = 'report') "
                + "OR (ag.scope = 'DIAGNOSTIC_REPORT' AND EXISTS (SELECT 1 FROM clinical_reviews scoped_review "
                + "WHERE scoped_review.study_id = " + studyAlias + ".id))))";
    }

    private JdbcClient.StatementSpec statement(String sql, UserContext actor) {
        UUID unused = new UUID(0, 0);
        return jdbc.sql(sql)
                .param("actorId", actor.id())
                .param("patientId", actor.patientId() == null ? unused : actor.patientId());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wybierz plik PDF, JPG lub PNG");
        }
        String mimeType = file.getContentType();
        if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Dozwolone są tylko PDF, JPG i PNG");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] signature = input.readNBytes(8);
            boolean matches = switch (mimeType) {
                case "application/pdf" -> startsWith(signature, new byte[]{'%', 'P', 'D', 'F', '-'});
                case "image/jpeg" -> startsWith(signature, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
                case "image/png" -> startsWith(signature, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
                default -> false;
            };
            if (!matches) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Zawartość pliku nie zgadza się z zadeklarowanym typem");
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie udało się odczytać pliku");
        }
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private String safeFilename(String original, String mimeType) {
        String fallback = "document." + extension(mimeType);
        if (original == null || original.isBlank()) return fallback;
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "").trim();
        return name.isBlank() ? fallback : name.substring(0, Math.min(name.length(), 500));
    }

    private String safeDerivedFilename(String original, String extension) {
        String safeExtension = extension != null && extension.matches("[a-zA-Z0-9]{1,10}")
                ? extension.toLowerCase() : "bin";
        if (original == null || original.isBlank()) return "derived." + safeExtension;
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "").trim();
        return name.isBlank() ? "derived." + safeExtension : name.substring(0, Math.min(name.length(), 500));
    }

    private String extension(String mimeType) {
        return switch (mimeType) {
            case "application/pdf" -> "pdf";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            default -> "bin";
        };
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record GrantOwner(UUID patientId, UUID grantedBy) {
    }

    private record AssetRow(UUID id, UUID studyId, UUID patientId, String bucket, String objectKey,
                            String filename, String mimeType, long size) {
    }

    private record TextAssetRow(String title, String bucket, String objectKey) {
    }

    private record PendingAsset(UUID assetId, UUID storageId, boolean primary, String assetType,
                                String bucket, String objectKey, String filename, String mimeType,
                                long size, InputStreamSource source) {
    }

    @FunctionalInterface
    public interface InputStreamSource {
        InputStream open() throws IOException;
    }

    public record DerivedAsset(String filename, String mimeType, String assetType,
                               String extension, byte[] content) {
        public DerivedAsset {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record PreparedUpload(String filename, String mimeType, long size, InputStreamSource source,
                                 String metadataJson, List<DerivedAsset> derivedAssets) {
        public PreparedUpload {
            derivedAssets = derivedAssets == null ? List.of() : List.copyOf(derivedAssets);
        }
    }

    public record StoredStudy(UUID studyId, UUID primaryAssetId) {
    }

    public record DownloadedAsset(String filename, String mimeType, long size, InputStream stream) {
    }

    public record DocumentContent(UUID documentId, UUID patientId, String title, String text) {
    }
}
