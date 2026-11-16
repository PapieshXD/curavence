package com.ralek.backend.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record WorkerExplanationCallbackRequest(
        @NotBlank String jobId,
        @NotBlank String status,
        String summary,
        String structuredDataJson,
        String errorMessage
) {
}
