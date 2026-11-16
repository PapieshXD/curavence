package com.ralek.backend.imaging.infrastructure;

import com.ralek.backend.imaging.domain.DicomInstanceMetadata;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DicomMetadataReaderTest {

    @TempDir
    Path directory;

    @Test
    void readsOnlyHeadersFromMrInstance() throws Exception {
        Path file = directory.resolve("brain.dcm");
        Attributes attributes = new Attributes();
        attributes.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
        attributes.setString(Tag.SOPClassUID, VR.UI, UID.MRImageStorage);
        attributes.setString(Tag.SOPInstanceUID, VR.UI, "1.2.826.0.1.1");
        attributes.setString(Tag.StudyInstanceUID, VR.UI, "1.2.826.0.1.2");
        attributes.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.826.0.1.3");
        attributes.setString(Tag.Modality, VR.CS, "MR");
        attributes.setString(Tag.StudyDescription, VR.LO, "MRI głowy");
        attributes.setString(Tag.BodyPartExamined, VR.CS, "HEAD");
        attributes.setString(Tag.StudyDate, VR.DA, "20260720");
        attributes.setString(Tag.StudyTime, VR.TM, "101530");
        attributes.setInt(Tag.SeriesNumber, VR.IS, 7);
        attributes.setString(Tag.SeriesDescription, VR.LO, "T2 FLAIR");
        try (DicomOutputStream output = new DicomOutputStream(file.toFile())) {
            output.writeDataset(attributes.createFileMetaInformation(UID.ExplicitVRLittleEndian), attributes);
        }

        DicomInstanceMetadata metadata = new DicomMetadataReader().read(file);

        assertThat(metadata.modality()).isEqualTo("MR");
        assertThat(metadata.studyInstanceUid()).isEqualTo("1.2.826.0.1.2");
        assertThat(metadata.seriesInstanceUid()).isEqualTo("1.2.826.0.1.3");
        assertThat(metadata.studyDescription()).isEqualTo("MRI głowy");
        assertThat(metadata.seriesNumber()).isEqualTo(7);
        assertThat(metadata.performedAt()).isNotNull();
    }

    @Test
    void readsCtGeometryAndHuCalibrationWithoutPixelData() throws Exception {
        Path file = directory.resolve("ct.dcm");
        Attributes attributes = new Attributes();
        attributes.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
        attributes.setString(Tag.SOPInstanceUID, VR.UI, "1.2.826.0.2.1");
        attributes.setString(Tag.StudyInstanceUID, VR.UI, "1.2.826.0.2.2");
        attributes.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.826.0.2.3");
        attributes.setString(Tag.Modality, VR.CS, "CT");
        attributes.setInt(Tag.Rows, VR.US, 512);
        attributes.setInt(Tag.Columns, VR.US, 512);
        attributes.setInt(Tag.InstanceNumber, VR.IS, 9);
        attributes.setDouble(Tag.PixelSpacing, VR.DS, 0.8, 0.8);
        attributes.setDouble(Tag.SliceThickness, VR.DS, 1.0);
        attributes.setDouble(Tag.ImagePositionPatient, VR.DS, 0, 0, 8);
        attributes.setDouble(Tag.ImageOrientationPatient, VR.DS, 1, 0, 0, 0, 1, 0);
        attributes.setDouble(Tag.RescaleSlope, VR.DS, 1);
        attributes.setDouble(Tag.RescaleIntercept, VR.DS, -1024);
        attributes.setDouble(Tag.WindowCenter, VR.DS, 40, -600);
        attributes.setDouble(Tag.WindowWidth, VR.DS, 400, 1500);
        attributes.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
        attributes.setInt(Tag.BitsStored, VR.US, 12);
        attributes.setInt(Tag.PixelRepresentation, VR.US, 0);
        try (DicomOutputStream output = new DicomOutputStream(file.toFile())) {
            output.writeDataset(attributes.createFileMetaInformation(UID.ExplicitVRLittleEndian), attributes);
        }

        DicomInstanceMetadata metadata = new DicomMetadataReader().read(file);

        assertThat(metadata.modality()).isEqualTo("CT");
        assertThat(metadata.imagePositionPatient()).containsExactly(0.0, 0.0, 8.0);
        assertThat(metadata.rescaleSlope()).isEqualTo(1.0);
        assertThat(metadata.rescaleIntercept()).isEqualTo(-1024.0);
        assertThat(metadata.windowCenter()).containsExactly(40.0, -600.0);
        assertThat(metadata.sha256()).hasSize(64);
        assertThat(metadata.sizeBytes()).isPositive();
    }
}
