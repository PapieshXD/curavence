package com.ralek.backend.ai.dto;

public record WorkerExplanationResponse(
        String remoteJobId,
        String status,
        String message
) {
}
