package com.ralek.backend.imaging.domain;

import java.util.List;
import java.util.Map;

public record DicomSeriesGeometry(
        String seriesInstanceUid,
        String modality,
        Integer rows,
        Integer columns,
        List<Double> pixelSpacing,
        Double sliceThickness,
        Double calculatedSpacing,
        List<Double> orientation,
        String frameOfReferenceUid,
        String transferSyntaxUid,
        Double rescaleSlope,
        Double rescaleIntercept,
        List<Double> windowCenter,
        List<Double> windowWidth,
        String photometricInterpretation,
        Integer bitsStored,
        Integer pixelRepresentation,
        boolean stackEligible,
        boolean mprEligible,
        boolean volumeRenderingEligible,
        String eligibilityReason,
        Map<String, Double> projectedPositions
) {
}
