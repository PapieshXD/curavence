package com.ralek.backend.imaging.infrastructure;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.util.UIDUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class NiftiToDicomConverter {

    private static final DateTimeFormatter DICOM_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DICOM_TIME = DateTimeFormatter.ofPattern("HHmmss.SSSSSS");

    private final NiftiReader reader;
    private final ImagingProperties properties;

    public NiftiToDicomConverter(NiftiReader reader, ImagingProperties properties) {
        this.reader = reader;
        this.properties = properties;
    }

    public ConversionResult convert(Path nifti, Path destination, ConversionRequest request) {
        NiftiVolume volume = reader.read(nifti);
        if (volume.instanceCount() > properties.maxArchiveEntries()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "NIfTI wygenerowałby zbyt wiele instancji DICOM");
        }
        try {
            Files.createDirectories(destination);
            Range[] ranges = scanRanges(volume);
            String studyUid = UIDUtils.createUID();
            String frameOfReferenceUid = UIDUtils.createUID();
            Instant performedAt = request.performedAt() == null ? Instant.now() : request.performedAt();
            var dateTime = performedAt.atOffset(ZoneOffset.UTC);
            String date = DICOM_DATE.format(dateTime);
            String time = DICOM_TIME.format(dateTime);
            int written = 0;

            try (FileChannel channel = FileChannel.open(volume.path(), StandardOpenOption.READ)) {
                for (int frame = 0; frame < volume.frames(); frame++) {
                    Range range = ranges[frame];
                    String seriesUid = UIDUtils.createUID();
                    String seriesDescription = seriesDescription(request.originalFilename(), frame, volume.frames());
                    for (int slice = 0; slice < volume.depth(); slice++) {
                        ByteBuffer source = readSlice(channel, volume, frame, slice);
                        byte[] pixels = convertPixels(source, volume, range);
                        Attributes dataset = dataset(volume, request, range, studyUid, seriesUid,
                                frameOfReferenceUid, date, time, frame, slice, seriesDescription, pixels);
                        Path target = destination.resolve("nifti-" + (frame + 1) + "-" + (slice + 1) + ".dcm");
                        try (DicomOutputStream output = new DicomOutputStream(target.toFile())) {
                            output.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset);
                        }
                        written++;
                    }
                }
            }
            return new ConversionResult(studyUid, volume.frames(), written);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nie udało się przekonwertować NIfTI do DICOM", exception);
        }
    }

    private Attributes dataset(NiftiVolume volume, ConversionRequest request, Range range,
                               String studyUid, String seriesUid, String frameOfReferenceUid,
                               String date, String time, int frame, int slice,
                               String seriesDescription, byte[] pixels) {
        Attributes data = new Attributes();
        data.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
        data.setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage);
        data.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        data.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        data.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid);
        data.setString(Tag.FrameOfReferenceUID, VR.UI, frameOfReferenceUid);
        data.setString(Tag.PatientName, VR.PN, "MEDNODE^PACJENT");
        data.setString(Tag.PatientID, VR.LO, request.patientId().toString());
        data.setString(Tag.Modality, VR.CS, "MR");
        data.setString(Tag.ImageType, VR.CS, "DERIVED", "SECONDARY");
        data.setString(Tag.StudyDescription, VR.LO, limited(request.description(), 64));
        data.setString(Tag.SeriesDescription, VR.LO, seriesDescription);
        data.setString(Tag.DerivationDescription, VR.ST,
                "Obraz pochodny utworzony przez Curavence z pliku NIfTI");
        data.setString(Tag.Manufacturer, VR.LO, "Curavence");
        data.setString(Tag.ConversionType, VR.CS, "WSD");
        data.setString(Tag.BurnedInAnnotation, VR.CS, "NO");
        data.setString(Tag.LossyImageCompression, VR.CS, "00");
        data.setString(Tag.StudyDate, VR.DA, date);
        data.setString(Tag.StudyTime, VR.TM, time);
        data.setString(Tag.SeriesDate, VR.DA, date);
        data.setString(Tag.SeriesTime, VR.TM, time);
        data.setString(Tag.ContentDate, VR.DA, date);
        data.setString(Tag.ContentTime, VR.TM, time);
        data.setString(Tag.StudyID, VR.SH, "NIFTI");
        data.setString(Tag.AccessionNumber, VR.SH, "");
        data.setInt(Tag.SeriesNumber, VR.IS, frame + 1);
        data.setInt(Tag.InstanceNumber, VR.IS, slice + 1);
        data.setInt(Tag.AcquisitionNumber, VR.IS, frame + 1);
        if (volume.frames() > 1) {
            data.setInt(Tag.NumberOfTemporalPositions, VR.IS, volume.frames());
            data.setInt(Tag.TemporalPositionIdentifier, VR.IS, frame + 1);
        }

        data.setInt(Tag.Rows, VR.US, volume.height());
        data.setInt(Tag.Columns, VR.US, volume.width());
        data.setInt(Tag.SamplesPerPixel, VR.US, 1);
        data.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
        data.setInt(Tag.BitsAllocated, VR.US, 16);
        data.setInt(Tag.BitsStored, VR.US, 16);
        data.setInt(Tag.HighBit, VR.US, 15);
        data.setInt(Tag.PixelRepresentation, VR.US, 0);

        DicomGeometry geometry = dicomGeometry(volume.geometry(), slice);
        data.setDouble(Tag.PixelSpacing, VR.DS, geometry.rowSpacing(), geometry.columnSpacing());
        data.setDouble(Tag.SliceThickness, VR.DS, geometry.sliceThickness());
        data.setDouble(Tag.SpacingBetweenSlices, VR.DS, geometry.spacingBetweenSlices());
        data.setDouble(Tag.ImageOrientationPatient, VR.DS, geometry.orientation());
        data.setDouble(Tag.ImagePositionPatient, VR.DS, geometry.position());

        data.setDouble(Tag.RescaleSlope, VR.DS, 1.0);
        data.setDouble(Tag.RescaleIntercept, VR.DS, 0.0);
        data.setString(Tag.RescaleType, VR.LO, "US");
        data.setDouble(Tag.WindowCenter, VR.DS, 32768.0);
        data.setDouble(Tag.WindowWidth, VR.DS, 65536.0);
        data.setInt(Tag.SmallestImagePixelValue, VR.US, 0);
        data.setInt(Tag.LargestImagePixelValue, VR.US, range.constant() ? 0 : 65535);
        data.setBytes(Tag.PixelData, VR.OW, pixels);
        return data;
    }

    private Range[] scanRanges(NiftiVolume volume) throws IOException {
        Range[] ranges = new Range[volume.frames()];
        try (FileChannel channel = FileChannel.open(volume.path(), StandardOpenOption.READ)) {
            for (int frame = 0; frame < volume.frames(); frame++) {
                double minimum = Double.POSITIVE_INFINITY;
                double maximum = Double.NEGATIVE_INFINITY;
                for (int slice = 0; slice < volume.depth(); slice++) {
                    ByteBuffer data = readSlice(channel, volume, frame, slice);
                    while (data.remaining() >= volume.bytesPerVoxel()) {
                        double value = physicalValue(data, volume);
                        if (Double.isFinite(value)) {
                            minimum = Math.min(minimum, value);
                            maximum = Math.max(maximum, value);
                        }
                    }
                }
                if (!Double.isFinite(minimum) || !Double.isFinite(maximum)) {
                    minimum = 0.0;
                    maximum = 0.0;
                }
                boolean constant = maximum <= minimum;
                double slope = constant ? 1.0 : (maximum - minimum) / 65535.0;
                ranges[frame] = new Range(minimum, slope, minimum, constant);
            }
        }
        return ranges;
    }

    private byte[] convertPixels(ByteBuffer source, NiftiVolume volume, Range range) {
        ByteBuffer output = ByteBuffer.allocate(Math.multiplyExact((int) volume.voxelsPerSlice(), 2))
                .order(ByteOrder.LITTLE_ENDIAN);
        while (source.remaining() >= volume.bytesPerVoxel()) {
            double value = physicalValue(source, volume);
            if (!Double.isFinite(value)) value = range.minimum();
            int stored = range.constant() ? 0 : (int) Math.round((value - range.storageOffset()) / range.storageScale());
            stored = Math.max(0, Math.min(65535, stored));
            output.putShort((short) stored);
        }
        return output.array();
    }

    private double physicalValue(ByteBuffer buffer, NiftiVolume volume) {
        double raw = switch (volume.datatype()) {
            case 2 -> Byte.toUnsignedInt(buffer.get());
            case 4 -> buffer.getShort();
            case 8 -> buffer.getInt();
            case 16 -> buffer.getFloat();
            case 64 -> buffer.getDouble();
            case 256 -> buffer.get();
            case 512 -> Short.toUnsignedInt(buffer.getShort());
            case 768 -> Integer.toUnsignedLong(buffer.getInt());
            default -> throw new IllegalStateException("Nieobsługiwany datatype NIfTI");
        };
        return raw * volume.scaleSlope() + volume.scaleIntercept();
    }

    private ByteBuffer readSlice(FileChannel channel, NiftiVolume volume, int frame, int slice) throws IOException {
        int bytes = Math.toIntExact(volume.sliceBytes());
        ByteBuffer buffer = ByteBuffer.allocate(bytes).order(volume.byteOrder());
        long sliceIndex = (long) frame * volume.depth() + slice;
        long position = volume.voxelOffset() + sliceIndex * volume.sliceBytes();
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) throw new IOException("Nieoczekiwany koniec danych NIfTI");
        }
        buffer.flip();
        return buffer.order(volume.byteOrder());
    }

    private DicomGeometry dicomGeometry(NiftiGeometry source, int slice) {
        double[] i = rasToLps(source.axisIRas());
        double[] j = rasToLps(source.axisJRas());
        double[] k = rasToLps(source.axisKRas());
        double columnSpacing = norm(i);
        double rowSpacing = norm(j);
        double sliceThickness = norm(k);
        double[] iDirection = normalize(i, new double[]{1.0, 0.0, 0.0});
        double projection = dot(j, iDirection);
        double[] orthogonalJ = new double[]{j[0] - projection * iDirection[0],
                j[1] - projection * iDirection[1], j[2] - projection * iDirection[2]};
        double[] jDirection = normalize(orthogonalJ, new double[]{0.0, 1.0, 0.0});
        double[] normal = cross(iDirection, jDirection);
        double spacing = Math.abs(dot(k, normal));
        if (spacing < 1.0e-6) spacing = sliceThickness;
        double[] origin = rasToLps(source.originRas());
        double[] position = new double[]{origin[0] + slice * k[0], origin[1] + slice * k[1], origin[2] + slice * k[2]};
        return new DicomGeometry(Math.max(rowSpacing, 1.0e-6), Math.max(columnSpacing, 1.0e-6),
                Math.max(sliceThickness, 1.0e-6), Math.max(spacing, 1.0e-6),
                new double[]{iDirection[0], iDirection[1], iDirection[2],
                        jDirection[0], jDirection[1], jDirection[2]}, position);
    }

    private double[] rasToLps(double[] value) {
        return new double[]{-value[0], -value[1], value[2]};
    }

    private double norm(double[] value) {
        return Math.sqrt(dot(value, value));
    }

    private double[] normalize(double[] value, double[] fallback) {
        double length = norm(value);
        if (length < 1.0e-12) return fallback;
        return new double[]{value[0] / length, value[1] / length, value[2] / length};
    }

    private double dot(double[] first, double[] second) {
        return first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
    }

    private double[] cross(double[] first, double[] second) {
        return new double[]{first[1] * second[2] - first[2] * second[1],
                first[2] * second[0] - first[0] * second[2],
                first[0] * second[1] - first[1] * second[0]};
    }

    private String seriesDescription(String filename, int frame, int frames) {
        String base = filename == null || filename.isBlank() ? "NIfTI" : filename;
        if (frames > 1) base += " — wolumen " + (frame + 1) + "/" + frames;
        return limited(base, 64);
    }

    private String limited(String value, int max) {
        String safe = value == null || value.isBlank() ? "MRI NIfTI" : value.trim();
        return safe.substring(0, Math.min(max, safe.length()));
    }

    public record ConversionRequest(UUID patientId, String description, String originalFilename,
                                    Instant performedAt) { }

    public record ConversionResult(String studyInstanceUid, int seriesCount, int instanceCount) { }

    private record Range(double minimum, double storageScale, double storageOffset, boolean constant) { }

    private record DicomGeometry(double rowSpacing, double columnSpacing, double sliceThickness,
                                 double spacingBetweenSlices, double[] orientation, double[] position) { }
}
