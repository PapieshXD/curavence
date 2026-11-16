package com.ralek.backend.document;

import java.util.List;

public final class ClinicalResultDtos {

    private ClinicalResultDtos() {
    }

    public record ClinicalResultSet(
            String name,
            String category,
            String categoryLabel,
            String presentation,
            String material,
            String comment,
            List<ClinicalResultParameter> parameters,
            List<ClinicalResultSection> sections
    ) {
    }

    public record ClinicalResultParameter(
            String id,
            String code,
            String name,
            Object value,
            Double numericValue,
            String unit,
            String interpretation,
            ClinicalResultReferenceRange referenceRange,
            String observedAt,
            List<ClinicalResultHistoryPoint> history,
            String sourceExcerpt,
            String sourceKind,
            String referenceSource
    ) {
    }

    public record ClinicalResultReferenceRange(Double low, Double high, String label) {
    }

    public record ClinicalResultHistoryPoint(String observedAt, double value) {
    }

    public record ClinicalResultSection(String id, String title, String content) {
    }
}
