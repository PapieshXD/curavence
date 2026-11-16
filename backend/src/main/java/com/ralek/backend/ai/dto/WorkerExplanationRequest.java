package com.ralek.backend.ai.dto;

import java.util.List;

public record WorkerExplanationRequest(
        String jobId,
        String taskType,
        String model,
        String patientId,
        String documentId,
        String requestedBy,
        String language,
        boolean forceRefresh,
        String sourceText,
        List<String> contextFragments,
        String callbackUrl
) {
}
