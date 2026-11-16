package com.ralek.backend.imaging.domain;

public record DicomSeriesMetadata(
        String seriesInstanceUid,
        Integer seriesNumber,
        String seriesDescription,
        String sequenceName,
        int imageCount
) {
}
