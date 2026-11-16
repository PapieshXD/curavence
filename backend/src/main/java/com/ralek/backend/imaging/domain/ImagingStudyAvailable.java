package com.ralek.backend.imaging.domain;

import java.util.UUID;

public record ImagingStudyAvailable(UUID studyId, UUID patientId, String studyInstanceUid) {
}
