package com.ralek.backend.ai.dto;

import java.util.List;

public record AiCapabilityResponse(
        boolean enabled,
        boolean mockMode,
        String provider,
        String defaultModel,
        List<String> supportedOperations
) {
}
