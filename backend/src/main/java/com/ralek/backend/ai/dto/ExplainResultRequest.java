package com.ralek.backend.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ExplainResultRequest(
        @NotBlank String patientId,
        @NotBlank String documentId,
        @NotBlank String requestedBy,
        @NotBlank String sourceText,
        @Size(max = 8) List<@NotBlank String> contextFragments,
        String language,
        boolean forceRefresh
) {
    public ExplainResultRequest {
        contextFragments = contextFragments == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(contextFragments));
        language = (language == null || language.isBlank()) ? "pl" : language;
    }
}
