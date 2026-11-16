package com.ralek.backend.imaging.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record DicomInstanceMetadata(
        Path path,
        String studyInstanceUid,
        String seriesInstanceUid,
        String sopInstanceUid,
        String sopClassUid,
        String transferSyntaxUid,
        String modality,
        String studyDescription,
        String bodyPart,
        Instant performedAt,
        Integer seriesNumber,
        String seriesDescription,
        String sequenceName,
        Integer instanceNumber,
        Integer rows,
        Integer columns,
        List<Double> pixelSpacing,
        Double sliceThickness,
        List<Double> imagePositionPatient,
        List<Double> imageOrientationPatient,
        String frameOfReferenceUid,
        Double rescaleSlope,
        Double rescaleIntercept,
        List<Double> windowCenter,
        List<Double> windowWidth,
        String photometricInterpretation,
        Integer bitsStored,
        Integer pixelRepresentation,
        List<String> imageType,
        String sha256,
        long sizeBytes
) {
}
