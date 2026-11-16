package com.ralek.backend.ai.dto;

public record ExplainResultResponse(
        String jobId,
        String status,
        String provider,
        String model,
        String message
) {
}
