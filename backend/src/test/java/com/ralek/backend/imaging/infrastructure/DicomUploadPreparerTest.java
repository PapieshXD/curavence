package com.ralek.backend.imaging.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DicomUploadPreparerTest {

    private final ImagingProperties properties = new ImagingProperties(
            "orthanc", "http://localhost", "user", "password",
            "http://localhost:3000", "http://localhost:8080", Duration.ofMinutes(10),
            10, 1024 * 1024, 100, 1024 * 1024);
    private final NiftiToDicomConverter converter = new NiftiToDicomConverter(new NiftiReader(), properties);
    private final DicomUploadPreparer preparer = new DicomUploadPreparer(properties, converter);

    @Test
    void preparesMultipleDicomFiles() {
        MockMultipartFile first = new MockMultipartFile("file", "one.dcm", "application/dicom", new byte[]{1, 2});
        MockMultipartFile second = new MockMultipartFile("file", "two.dcm", "application/dicom", new byte[]{3, 4});

        try (var prepared = preparer.prepare(new MockMultipartFile[]{first, second})) {
            assertThat(prepared.fileCount()).isEqualTo(2);
            assertThat(prepared.dicomFiles()).hasSize(2);
        }
    }

    @Test
    void rejectsZipSlipPath() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../escape.dcm"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        MockMultipartFile archive = new MockMultipartFile("file", "study.zip", "application/zip", bytes.toByteArray());

        assertThatThrownBy(() -> preparer.prepare(new MockMultipartFile[]{archive}))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("niedozwoloną ścieżkę");
    }

    @Test
    void preparesStoredZipEntryWithExtendedDataDescriptor() throws Exception {
        byte[] dicom = {1, 2, 3, 4};
        MockMultipartFile archive = new MockMultipartFile(
                "file",
                "onedrive-study.zip",
                "application/zip",
                storedZipWithExtendedDataDescriptor("CT/image.dcm", dicom));

        try (var prepared = preparer.prepare(new MockMultipartFile[]{archive})) {
            assertThat(prepared.fileCount()).isEqualTo(1);
            assertThat(Files.readAllBytes(prepared.dicomFiles().getFirst())).isEqualTo(dicom);
        }
    }

    @Test
    void preparesCompressedNiftiAsDicomSlices() throws Exception {
        MockMultipartFile nifti = new MockMultipartFile("file", "brain.nii.gz",
                "application/gzip", NiftiFixture.niiGz());
        var request = new NiftiToDicomConverter.ConversionRequest(
                UUID.randomUUID(), "MRI mózgu", "brain.nii.gz", Instant.parse("2026-08-03T10:15:00Z"));

        try (var prepared = preparer.prepare(new MockMultipartFile[]{nifti}, request)) {
            assertThat(prepared.sourceFormat()).isEqualTo("NIFTI");
            assertThat(prepared.originalFilename()).isEqualTo("brain.nii.gz");
            assertThat(prepared.fileCount()).isEqualTo(NiftiFixture.DEPTH);
            assertThat(prepared.dicomFiles()).hasSize(NiftiFixture.DEPTH);
        }
    }

    /**
     * Reproduces the valid ZIP variant emitted by OneDrive: a STORED entry whose
     * CRC and sizes are supplied in an extended data descriptor after its data.
     */
    private byte[] storedZipWithExtendedDataDescriptor(String filename, byte[] content) {
        byte[] name = filename.getBytes(StandardCharsets.UTF_8);
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        int crc = (int) crc32.getValue();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        writeInt(bytes, 0x04034b50);
        writeShort(bytes, 20);
        writeShort(bytes, 0x0008);
        writeShort(bytes, 0);
        writeShort(bytes, 0);
        writeShort(bytes, 0);
        writeInt(bytes, 0);
        writeInt(bytes, 0);
        writeInt(bytes, 0);
        writeShort(bytes, name.length);
        writeShort(bytes, 0);
        bytes.writeBytes(name);
        bytes.writeBytes(content);

        writeInt(bytes, 0x08074b50);
        writeInt(bytes, crc);
        writeInt(bytes, content.length);
        writeInt(bytes, content.length);

        int centralDirectoryOffset = bytes.size();
        writeInt(bytes, 0x02014b50);
        writeShort(bytes, 20);
        writeShort(bytes, 20);
        writeShort(bytes, 0x0008);
        writeShort(bytes, 0);
        writeShort(bytes, 0);
        writeShort(bytes, 0);
        writeInt(bytes, crc);
        writeInt(bytes, content.length);
        writeInt(bytes, content.length);
        writeShort(bytes, name.length);
        writeShort(bytes, 0);
        writeShort(bytes, 0);
        writeShort(bytes, 0);
        writeShort(bytes, 0);
        writeInt(bytes, 0);
        writeInt(bytes, 0);
        bytes.writeBytes(name);
        int centralDirectorySize = bytes.size() - centralDirectoryOffset;

        writeInt(bytes, 0x06054b50);
        writeShort(bytes, 0);
        writeShort(bytes, 0);
        writeShort(bytes, 1);
        writeShort(bytes, 1);
        writeInt(bytes, centralDirectorySize);
        writeInt(bytes, centralDirectoryOffset);
        writeShort(bytes, 0);
        return bytes.toByteArray();
    }

    private void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private void writeInt(ByteArrayOutputStream output, int value) {
        writeShort(output, value & 0xffff);
        writeShort(output, (value >>> 16) & 0xffff);
    }
}
