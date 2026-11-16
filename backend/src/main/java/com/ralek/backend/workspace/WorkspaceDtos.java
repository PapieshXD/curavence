package com.ralek.backend.workspace;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    public record SessionDto(
            UUID id,
            String email,
            String firstName,
            String lastName,
            String displayName,
            Set<String> roles,
            List<String> availableViews,
            boolean superuser,
            String effectiveView
    ) {
    }

    public record ProfileDto(
            UUID patientId,
            UUID userId,
            String firstName,
            String lastName,
            String email,
            LocalDate dateOfBirth,
            String biologicalSex,
            String phone
    ) {
    }

    public record PatientDto(
            UUID id,
            UUID userId,
            String firstName,
            String lastName,
            String email,
            LocalDate dateOfBirth,
            String biologicalSex,
            String phone,
            long studyCount
    ) {
    }

    public record StudyDto(
            UUID id,
            UUID patientId,
            String patientName,
            String typeCode,
            String typeName,
            String title,
            String description,
            String sourceName,
            Instant performedAt,
            String ingestionStatus,
            String clinicalStatus,
            UUID primaryAssetId,
            String originalFilename,
            String mimeType,
            Long sizeBytes,
            boolean hasExtractedText,
            String documentProcessingStatus,
            UUID imagingStudyId, long metadataRevision
    ) {
    }

    public record GrantDto(
            UUID id,
            UUID patientId,
            String patientName,
            UUID recipientUserId,
            String recipientName,
            String recipientEmail,
            String recipientRole,
            String scope,
            String purpose,
            Instant createdAt,
            Instant expiresAt,
            String status
    ) {
    }

    public record AuditDto(
            UUID id,
            String actorName,
            UUID patientId,
            String patientName,
            String resourceType,
            UUID resourceId,
            String action,
            boolean success,
            String denialReason,
            Instant createdAt
    ) {
    }

    public record ReviewDto(
            UUID id,
            UUID studyId,
            String studyTitle,
            String reviewerName,
            String status,
            String summary,
            String conclusion,
            String summaryHtml,
            String conclusionHtml,
            Instant updatedAt,
            Instant signedAt
    ) {
    }

    public record StudyTypeDto(String code, String name) {
    }

    public record UserOptionDto(UUID id, String name, String email, String role) {
    }

    public record OverviewDto(long patients, long studies, long activeGrants, long auditEvents,
                              long awaitingReview) {
    }

    public record WorkspaceResponse(
            SessionDto session,
            OverviewDto overview,
            ProfileDto profile,
            List<PatientDto> patients,
            List<StudyDto> studies,
            List<GrantDto> grants,
            List<AuditDto> auditEvents,
            List<ReviewDto> reviews,
            List<StudyTypeDto> studyTypes,
            List<UserOptionDto> recipients
    ) {
    }

    public record CreateGrantRequest(
            @NotBlank @Email String recipientEmail,
            @NotBlank String scope,
            String purpose,
            Instant expiresAt,
            List<UUID> studyIds
    ) {
    }

    public record ReviewRequest(
            @NotBlank String summary,
            String conclusion,
            boolean signed,
            String summaryHtml,
            String conclusionHtml
    ) {
    }

    public record UpdatePatientRequest(@NotBlank String phone) {
    }

    public record UpdateDocumentRequest(
            @NotBlank @Size(max = 255) String title,
            Instant performedAt, String typeCode, Long revision
    ) {
        public UpdateDocumentRequest(String title, Instant performedAt) { this(title, performedAt, null, null); }
    }
}
