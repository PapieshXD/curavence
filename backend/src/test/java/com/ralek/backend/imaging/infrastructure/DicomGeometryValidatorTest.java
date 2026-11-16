package com.ralek.backend.imaging.infrastructure;

import com.ralek.backend.imaging.domain.DicomInstanceMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DicomGeometryValidatorTest {

    private final DicomGeometryValidator validator = new DicomGeometryValidator();

    @Test
    void qualifiesRegularThinCtVolumeForMprAndVolumeRendering() {
        List<DicomInstanceMetadata> slices = java.util.stream.IntStream.range(0, 24)
                .mapToObj(index -> slice(index + 1, index, 0.8, List.of("ORIGINAL", "PRIMARY")))
                .toList();

        var result = validator.validate(slices);

        assertThat(result.calculatedSpacing()).isEqualTo(1.0);
        assertThat(result.stackEligible()).isTrue();
        assertThat(result.mprEligible()).isTrue();
        assertThat(result.volumeRenderingEligible()).isTrue();
        assertThat(result.projectedPositions()).containsEntry("1.2.3.1", 0.0)
                .containsEntry("1.2.3.24", 23.0);
    }

    @Test
    void keepsSparseDerivedCtAsStackOnly() {
        List<DicomInstanceMetadata> slices = java.util.stream.IntStream.range(0, 36)
                .mapToObj(index -> slice(36 - index, index * 9.3, 0.984, List.of("DERIVED", "SECONDARY")))
                .toList();

        var result = validator.validate(slices);

        assertThat(result.stackEligible()).isTrue();
        assertThat(result.mprEligible()).isFalse();
        assertThat(result.volumeRenderingEligible()).isFalse();
        assertThat(result.eligibilityReason()).contains("zbyt duży odstęp").contains("pochodna");
    }

    private DicomInstanceMetadata slice(int instance, double z, double inPlaneSpacing,
                                        List<String> imageType) {
        return new DicomInstanceMetadata(Path.of("slice-" + instance + ".dcm"),
                "1.2.1", "1.2.2", "1.2.3." + instance, "1.2.840.10008.5.1.4.1.1.2",
                "1.2.840.10008.1.2.1", "CT", "CT QA", "CHEST", null, 1,
                "Axial", null, instance, 512, 512, List.of(inPlaneSpacing, inPlaneSpacing),
                1.0, List.of(0.0, 0.0, z), List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
                "1.2.frame", 1.0, -1024.0, List.of(40.0), List.of(400.0),
                "MONOCHROME2", 12, 0, imageType, "hash-" + instance, 1024);
    }
}
