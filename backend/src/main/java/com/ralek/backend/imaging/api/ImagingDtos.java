package com.ralek.backend.imaging.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class ImagingDtos {

    private ImagingDtos() { }

    public record ImagingStudySummary(
            UUID id,
            UUID patientId,
            String patientName,
            String studyInstanceUid,
            String modality,
            String description,
            String bodyPart,
            Instant performedAt,
            int seriesCount,
            int instanceCount,
            String storageProvider,
            String sourceFormat,
            String status,
            String errorMessage
    ) { }

    public record ImagingSeriesDto(
            UUID id,
            String seriesInstanceUid,
            Integer seriesNumber,
            String description,
            String sequenceName,
            int imageCount,
            String modality,
            Integer rows,
            Integer columns,
            Double pixelSpacingX,
            Double pixelSpacingY,
            Double sliceThickness,
            Double calculatedSpacing,
            boolean stackEligible,
            boolean mprEligible,
            boolean volumeRenderingEligible,
            String eligibilityReason
    ) { }

    public record ImagingStudyDetails(ImagingStudySummary study, List<ImagingSeriesDto> series) { }

    public record ImagingStudyStatus(UUID studyId, String status, int progress, String errorMessage) { }

    public record ImagingStudyCreated(UUID id, String status, int progress) { }

    public record ViewerSessionResponse(
            UUID sessionId,
            UUID studyId,
            String studyInstanceUid,
            Instant expiresAt,
            String dicomWebRoot,
            String viewerUrl
    ) { }

    public record ImagingAnnotationDto(
            UUID id,
            UUID studyId,
            UUID seriesId,
            String sopInstanceUid,
            String frameOfReferenceUid,
            UUID authorId,
            String type,
            JsonNode geometry,
            JsonNode measurement,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record SaveImagingAnnotationRequest(
            UUID seriesId,
            String sopInstanceUid,
            String frameOfReferenceUid,
            String type,
            JsonNode geometry,
            JsonNode measurement,
            Long version
    ) { }
}
