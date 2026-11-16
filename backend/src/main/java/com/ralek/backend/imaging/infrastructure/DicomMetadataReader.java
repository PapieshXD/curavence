package com.ralek.backend.imaging.infrastructure;

import com.ralek.backend.imaging.domain.DicomInstanceMetadata;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

@Component
public class DicomMetadataReader {

    public DicomInstanceMetadata read(Path path) {
        try (DicomInputStream input = new DicomInputStream(path.toFile())) {
            input.setIncludeBulkData(DicomInputStream.IncludeBulkData.NO);
            Attributes fileMeta = input.readFileMetaInformation();
            Attributes attributes = input.readDataset();
            return new DicomInstanceMetadata(
                    path,
                    required(attributes, Tag.StudyInstanceUID, "StudyInstanceUID"),
                    required(attributes, Tag.SeriesInstanceUID, "SeriesInstanceUID"),
                    required(attributes, Tag.SOPInstanceUID, "SOPInstanceUID"),
                    required(attributes, Tag.SOPClassUID, "SOPClassUID"),
                    fileMeta == null ? null : trimToNull(fileMeta.getString(Tag.TransferSyntaxUID)),
                    required(attributes, Tag.Modality, "Modality"),
                    trimToNull(attributes.getString(Tag.StudyDescription)),
                    trimToNull(attributes.getString(Tag.BodyPartExamined)),
                    studyInstant(attributes),
                    attributes.getInt(Tag.SeriesNumber, -1) < 0 ? null : attributes.getInt(Tag.SeriesNumber, -1),
                    trimToNull(attributes.getString(Tag.SeriesDescription)),
                    trimToNull(attributes.getString(Tag.SequenceName)),
                    nullableInt(attributes, Tag.InstanceNumber),
                    nullableInt(attributes, Tag.Rows),
                    nullableInt(attributes, Tag.Columns),
                    doubles(attributes, Tag.PixelSpacing),
                    nullableDouble(attributes, Tag.SliceThickness),
                    doubles(attributes, Tag.ImagePositionPatient),
                    doubles(attributes, Tag.ImageOrientationPatient),
                    trimToNull(attributes.getString(Tag.FrameOfReferenceUID)),
                    nullableDouble(attributes, Tag.RescaleSlope),
                    nullableDouble(attributes, Tag.RescaleIntercept),
                    doubles(attributes, Tag.WindowCenter),
                    doubles(attributes, Tag.WindowWidth),
                    trimToNull(attributes.getString(Tag.PhotometricInterpretation)),
                    nullableInt(attributes, Tag.BitsStored),
                    nullableInt(attributes, Tag.PixelRepresentation),
                    strings(attributes, Tag.ImageType),
                    sha256(path),
                    Files.size(path)
            );
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nie udało się odczytać pliku DICOM: " + path.getFileName(), exception);
        }
    }

    private Integer nullableInt(Attributes attributes, int tag) {
        int value = attributes.getInt(tag, Integer.MIN_VALUE);
        return value == Integer.MIN_VALUE ? null : value;
    }

    private Double nullableDouble(Attributes attributes, int tag) {
        double value = attributes.getDouble(tag, Double.NaN);
        return Double.isNaN(value) ? null : value;
    }

    private List<Double> doubles(Attributes attributes, int tag) {
        double[] values = attributes.getDoubles(tag);
        if (values == null) return List.of();
        return Arrays.stream(values).boxed().toList();
    }

    private List<String> strings(Attributes attributes, int tag) {
        String[] values = attributes.getStrings(tag);
        if (values == null) return List.of();
        return Arrays.stream(values).map(this::trimToNull).filter(java.util.Objects::nonNull).toList();
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Brak algorytmu SHA-256", exception);
        }
    }

    private String required(Attributes attributes, int tag, String name) {
        String value = trimToNull(attributes.getString(tag));
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Plik DICOM nie zawiera wymaganego pola " + name);
        }
        return value;
    }

    private Instant studyInstant(Attributes attributes) {
        String date = trimToNull(attributes.getString(Tag.StudyDate));
        if (date == null) return null;
        String time = trimToNull(attributes.getString(Tag.StudyTime));
        try {
            LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
            LocalTime localTime = LocalTime.MIDNIGHT;
            if (time != null) {
                String normalized = time.replace(".", "");
                normalized = (normalized + "000000").substring(0, 6);
                localTime = LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HHmmss"));
            }
            return LocalDateTime.of(localDate, localTime).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
