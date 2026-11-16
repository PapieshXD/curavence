package com.ralek.backend.imaging.analysis;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ImagingAnalysisDtos {

    private ImagingAnalysisDtos() { }

    public record ScopeRequest(String scopeMode, List<String> seriesInstanceUids, String pipeline) {
        public ScopeRequest {
            scopeMode = scopeMode == null ? "ALL_ELIGIBLE" : scopeMode;
            seriesInstanceUids = seriesInstanceUids == null ? List.of() : List.copyOf(seriesInstanceUids);
            pipeline = pipeline == null ? "AUTO" : pipeline;
        }
    }

    public record AnalysisCreated(UUID id, UUID studyId, String status, int progress) { }

    public record AnalysisArtifactDto(
            UUID id,
            String kind,
            String label,
            String filename,
            String mimeType,
            long sizeBytes,
            Integer sliceIndex,
            UUID seriesId
    ) { }

    public record AnalysisDto(
            UUID id,
            UUID studyId,
            String pipeline,
            String engine,
            String status,
            int progress,
            JsonNode classification,
            JsonNode quality,
            JsonNode measurements,
            JsonNode observations,
            JsonNode candidateFindings,
            JsonNode modelVersions,
            JsonNode warnings,
            String summary,
            String disclaimer,
            String errorMessage,
            List<AnalysisArtifactDto> artifacts,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            int analysisVersion, UUID parentAnalysisId, JsonNode scope, JsonNode seriesResults,
            String evidenceHash, String audience, JsonNode explanation
    ) { }
}

