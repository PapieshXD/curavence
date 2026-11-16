package com.ralek.backend.imaging.infrastructure;

import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NiftiToDicomConverterTest {

    @TempDir
    Path directory;

    @Test
    void convertsNiftiVolumeIntoSpatiallyIndexedMrSeries() throws Exception {
        ImagingProperties properties = properties();
        NiftiToDicomConverter converter = new NiftiToDicomConverter(new NiftiReader(), properties);
        Path source = directory.resolve("brain.nii");
        Path dicom = directory.resolve("dicom");
        Files.write(source, NiftiFixture.nii());

        var result = converter.convert(source, dicom, new NiftiToDicomConverter.ConversionRequest(
                UUID.randomUUID(), "MRI mózgu NIfTI", "brain.nii", Instant.parse("2026-08-03T10:15:00Z")));

        assertThat(result.seriesCount()).isEqualTo(1);
        assertThat(result.instanceCount()).isEqualTo(NiftiFixture.DEPTH);
        Path first;
        try (var files = Files.list(dicom)) {
            assertThat(files.count()).isEqualTo(NiftiFixture.DEPTH);
        }
        first = dicom.resolve("nifti-1-1.dcm");
        try (DicomInputStream input = new DicomInputStream(first.toFile())) {
            var attributes = input.readDataset();
            assertThat(attributes.getString(Tag.Modality)).isEqualTo("MR");
            assertThat(attributes.getString(Tag.StudyDescription)).isEqualTo("MRI mózgu NIfTI");
            assertThat(attributes.getInt(Tag.Columns, 0)).isEqualTo(NiftiFixture.WIDTH);
            assertThat(attributes.getInt(Tag.Rows, 0)).isEqualTo(NiftiFixture.HEIGHT);
            double[] spacing = attributes.getDoubles(Tag.PixelSpacing);
            assertThat(spacing[0]).isCloseTo(0.9, within(1.0e-6));
            assertThat(spacing[1]).isCloseTo(0.8, within(1.0e-6));
            assertThat(attributes.getDoubles(Tag.ImagePositionPatient)).containsExactly(-10.0, -20.0, 30.0);
            assertThat(attributes.getBytes(Tag.PixelData)).hasSize(NiftiFixture.WIDTH * NiftiFixture.HEIGHT * 2);
        }
    }

    @Test
    void convertsEachVolumeOfFourDimensionalNiftiIntoSeparateSeries() throws Exception {
        NiftiToDicomConverter converter = new NiftiToDicomConverter(new NiftiReader(), properties());
        Path source = directory.resolve("functional.nii");
        Path dicom = directory.resolve("functional-dicom");
        Files.write(source, NiftiFixture.nii(2));

        var result = converter.convert(source, dicom, new NiftiToDicomConverter.ConversionRequest(
                UUID.randomUUID(), "MRI 4D", "functional.nii", Instant.parse("2026-08-03T10:15:00Z")));

        assertThat(result.seriesCount()).isEqualTo(2);
        assertThat(result.instanceCount()).isEqualTo(NiftiFixture.DEPTH * 2);
        try (DicomInputStream first = new DicomInputStream(dicom.resolve("nifti-1-1.dcm").toFile());
             DicomInputStream second = new DicomInputStream(dicom.resolve("nifti-2-1.dcm").toFile())) {
            assertThat(first.readDataset().getString(Tag.SeriesInstanceUID))
                    .isNotEqualTo(second.readDataset().getString(Tag.SeriesInstanceUID));
        }
    }

    @Test
    void writesBinaryMaskAsUnsignedPixelsForViewerCompatibility() throws Exception {
        NiftiToDicomConverter converter = new NiftiToDicomConverter(new NiftiReader(), properties());
        Path source = directory.resolve("mask.nii");
        Path dicom = directory.resolve("mask-dicom");
        Files.write(source, NiftiFixture.binaryNii());

        converter.convert(source, dicom, new NiftiToDicomConverter.ConversionRequest(
                UUID.randomUUID(), "Maska guza", "mask.nii", Instant.parse("2026-08-03T10:15:00Z")));

        try (DicomInputStream input = new DicomInputStream(dicom.resolve("nifti-1-2.dcm").toFile())) {
            var attributes = input.readDataset();
            assertThat(attributes.getInt(Tag.PixelRepresentation, -1)).isZero();
            assertThat(attributes.getInt(Tag.SmallestImagePixelValue, -1)).isZero();
            assertThat(attributes.getInt(Tag.LargestImagePixelValue, -1)).isEqualTo(65535);
            assertThat(attributes.getDouble(Tag.RescaleIntercept, -1)).isZero();
            assertThat(attributes.getDouble(Tag.RescaleSlope, -1)).isEqualTo(1.0);
            assertThat(attributes.getDouble(Tag.WindowCenter, 0)).isEqualTo(32768.0);
            assertThat(attributes.getDouble(Tag.WindowWidth, 0)).isEqualTo(65536.0);

            ByteBuffer pixels = ByteBuffer.wrap(attributes.getBytes(Tag.PixelData)).order(ByteOrder.LITTLE_ENDIAN);
            int center = (NiftiFixture.HEIGHT / 2) * NiftiFixture.WIDTH + NiftiFixture.WIDTH / 2;
            assertThat(Short.toUnsignedInt(pixels.getShort(0))).isZero();
            assertThat(Short.toUnsignedInt(pixels.getShort(center * 2))).isEqualTo(65535);
        }
    }

    static ImagingProperties properties() {
        return new ImagingProperties("orthanc", "http://localhost", "user", "password",
                "http://localhost:3000", "http://localhost:8080", Duration.ofMinutes(10),
                100, 1024 * 1024, 100, 1024 * 1024);
    }
}
