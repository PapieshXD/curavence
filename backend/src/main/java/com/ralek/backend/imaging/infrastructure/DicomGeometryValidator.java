package com.ralek.backend.imaging.infrastructure;

import com.ralek.backend.imaging.domain.DicomInstanceMetadata;
import com.ralek.backend.imaging.domain.DicomSeriesGeometry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class DicomGeometryValidator {

    private static final double VECTOR_TOLERANCE = 1e-4;
    private static final double POSITION_TOLERANCE = 1e-3;

    public DicomSeriesGeometry validate(List<DicomInstanceMetadata> instances) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("Seria DICOM nie zawiera instancji");
        }
        DicomInstanceMetadata first = instances.getFirst();
        List<String> reasons = new ArrayList<>();
        boolean dimensionsConsistent = instances.stream().allMatch(value ->
                Objects.equals(first.rows(), value.rows()) && Objects.equals(first.columns(), value.columns()));
        if (!dimensionsConsistent) reasons.add("niespójne wymiary obrazów");

        boolean orientationComplete = instances.stream()
                .allMatch(value -> value.imageOrientationPatient().size() == 6
                        && value.imagePositionPatient().size() == 3);
        List<Double> orientation = first.imageOrientationPatient();
        boolean orientationConsistent = orientationComplete && instances.stream()
                .allMatch(value -> close(orientation, value.imageOrientationPatient()));
        if (!orientationComplete) reasons.add("brak pełnej geometrii pacjenta");
        else if (!orientationConsistent) reasons.add("niespójna orientacja warstw");

        Map<String, Double> projected = new LinkedHashMap<>();
        Double calculatedSpacing = null;
        boolean positionsValid = false;
        boolean regularSpacing = false;
        if (orientationConsistent) {
            double[] normal = normal(orientation);
            if (normal != null) {
                instances.forEach(value -> projected.put(value.sopInstanceUid(),
                        dot(value.imagePositionPatient(), normal)));
                List<Double> positions = projected.values().stream().sorted().toList();
                List<Double> gaps = new ArrayList<>();
                positionsValid = true;
                for (int index = 1; index < positions.size(); index++) {
                    double gap = positions.get(index) - positions.get(index - 1);
                    if (gap <= POSITION_TOLERANCE) positionsValid = false;
                    else gaps.add(gap);
                }
                if (!positionsValid) reasons.add("zduplikowane lub niemonotoniczne pozycje warstw");
                if (!gaps.isEmpty()) {
                    List<Double> sortedGaps = gaps.stream().sorted(Comparator.naturalOrder()).toList();
                    calculatedSpacing = sortedGaps.get(sortedGaps.size() / 2);
                    double spacing = calculatedSpacing;
                    regularSpacing = gaps.stream().allMatch(gap -> Math.abs(gap - spacing)
                            <= Math.max(POSITION_TOLERANCE, spacing * 0.20));
                    if (!regularSpacing) reasons.add("nieregularny odstęp między warstwami");
                }
            } else {
                reasons.add("nieprawidłowy wektor orientacji");
            }
        }

        boolean frameConsistent = instances.stream()
                .map(DicomInstanceMetadata::frameOfReferenceUid).filter(Objects::nonNull).distinct().count() <= 1;
        if (!frameConsistent) reasons.add("różne FrameOfReferenceUID");
        boolean stackEligible = dimensionsConsistent && !instances.isEmpty();
        double inPlane = first.pixelSpacing().stream().filter(value -> value > 0).max(Double::compare).orElse(1.0);
        boolean spacingSuitableForMpr = calculatedSpacing != null && calculatedSpacing <= inPlane * 5.0;
        boolean spacingSuitableForVolume = calculatedSpacing != null && calculatedSpacing <= inPlane * 2.5;
        boolean mprEligible = stackEligible && orientationConsistent && positionsValid && regularSpacing
                && frameConsistent && instances.size() >= 8 && spacingSuitableForMpr;
        boolean derived = instances.stream().flatMap(value -> value.imageType().stream())
                .anyMatch(value -> "DERIVED".equalsIgnoreCase(value) || "SECONDARY".equalsIgnoreCase(value));
        boolean volumeEligible = mprEligible && instances.size() >= 16 && spacingSuitableForVolume && !derived;
        if (instances.size() < 8) reasons.add("za mało warstw dla MPR");
        else if (!spacingSuitableForMpr) reasons.add("zbyt duży odstęp warstw dla MPR");
        if (derived) reasons.add("seria pochodna/secondary");

        return new DicomSeriesGeometry(
                first.seriesInstanceUid(), first.modality(), first.rows(), first.columns(),
                first.pixelSpacing(), first.sliceThickness(), calculatedSpacing,
                orientation, first.frameOfReferenceUid(), first.transferSyntaxUid(),
                first.rescaleSlope(), first.rescaleIntercept(), first.windowCenter(), first.windowWidth(),
                first.photometricInterpretation(), first.bitsStored(), first.pixelRepresentation(),
                stackEligible, mprEligible, volumeEligible,
                reasons.isEmpty() ? null : String.join("; ", reasons), Map.copyOf(projected));
    }

    private boolean close(List<Double> first, List<Double> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            if (Math.abs(first.get(index) - second.get(index)) > VECTOR_TOLERANCE) return false;
        }
        return true;
    }

    private double[] normal(List<Double> orientation) {
        if (orientation.size() != 6) return null;
        double x = orientation.get(1) * orientation.get(5) - orientation.get(2) * orientation.get(4);
        double y = orientation.get(2) * orientation.get(3) - orientation.get(0) * orientation.get(5);
        double z = orientation.get(0) * orientation.get(4) - orientation.get(1) * orientation.get(3);
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < VECTOR_TOLERANCE ? null : new double[]{x / length, y / length, z / length};
    }

    private double dot(List<Double> position, double[] normal) {
        return position.get(0) * normal[0] + position.get(1) * normal[1] + position.get(2) * normal[2];
    }
}
