package com.ralek.backend.imaging.infrastructure;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.util.UIDUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates a tiny, anonymous MR series for local integration testing. */
public final class DicomFixtureGenerator {

    private DicomFixtureGenerator() { }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        String studyUid = UIDUtils.createUID();
        String seriesUid = UIDUtils.createUID();
        for (int instance = 1; instance <= 3; instance++) {
            Attributes data = new Attributes();
            data.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
            data.setString(Tag.SOPClassUID, VR.UI, UID.MRImageStorage);
            data.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
            data.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
            data.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid);
            data.setString(Tag.PatientName, VR.PN, "TEST^ANONYMOUS");
            data.setString(Tag.PatientID, VR.LO, "MEDNODE-QA");
            data.setString(Tag.Modality, VR.CS, "MR");
            data.setString(Tag.StudyDescription, VR.LO, "Syntetyczne MRI QA");
            data.setString(Tag.SeriesDescription, VR.LO, "T2 test");
            data.setString(Tag.BodyPartExamined, VR.CS, "HEAD");
            data.setString(Tag.StudyDate, VR.DA, "20260803");
            data.setString(Tag.StudyTime, VR.TM, "120000");
            data.setInt(Tag.SeriesNumber, VR.IS, 1);
            data.setInt(Tag.InstanceNumber, VR.IS, instance);
            data.setInt(Tag.Rows, VR.US, 64);
            data.setInt(Tag.Columns, VR.US, 64);
            data.setInt(Tag.SamplesPerPixel, VR.US, 1);
            data.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
            data.setInt(Tag.BitsAllocated, VR.US, 16);
            data.setInt(Tag.BitsStored, VR.US, 12);
            data.setInt(Tag.HighBit, VR.US, 11);
            data.setInt(Tag.PixelRepresentation, VR.US, 0);
            data.setDouble(Tag.PixelSpacing, VR.DS, 1.0, 1.0);
            data.setDouble(Tag.SliceThickness, VR.DS, 2.0);
            data.setDouble(Tag.ImagePositionPatient, VR.DS, 0.0, 0.0, instance * 2.0);
            data.setDouble(Tag.ImageOrientationPatient, VR.DS, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0);
            data.setDouble(Tag.WindowCenter, VR.DS, 1024.0);
            data.setDouble(Tag.WindowWidth, VR.DS, 2048.0);

            ByteBuffer pixels = ByteBuffer.allocate(64 * 64 * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < 64 * 64; index++) {
                pixels.putShort((short) ((index + instance * 150) % 2048));
            }
            data.setBytes(Tag.PixelData, VR.OW, pixels.array());
            Path target = directory.resolve("slice-" + instance + ".dcm");
            try (DicomOutputStream output = new DicomOutputStream(target.toFile())) {
                output.writeDataset(data.createFileMetaInformation(UID.ExplicitVRLittleEndian), data);
            }
        }
        System.out.println(studyUid);
    }
}
