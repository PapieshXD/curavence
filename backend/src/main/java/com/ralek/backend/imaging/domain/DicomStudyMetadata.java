package com.ralek.backend.imaging.domain;

import java.time.Instant;

public record DicomStudyMetadata(
        String studyInstanceUid,
        String modality,
        String studyDescription,
        String bodyPart,
        Instant performedAt,
        int seriesCount,
        int instanceCount
) {
}
