package com.ralek.backend.document;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/** Preserves device exports without claiming to decode or clinically interpret them. */
@Component
public class EcgFileProcessor {
    private static final Set<String> EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp",
            "txt", "csv", "tsv", "xml", "json", "ecg", "scp", "edf", "bdf",
            "gdf", "hea", "dat", "atr", "mat", "dcm", "dicom", "zip");
    private final DocumentProperties properties;

    public EcgFileProcessor(DocumentProperties properties) {
        this.properties = properties;
    }

    public PreparedEcg process(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wybierz niepusty plik EKG.");
        }
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Plik EKG przekracza limit rozmiaru.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Nieobsługiwany format pliku EKG.");
        }
        try {
            byte[] bytes = file.getBytes();
            String mime = "application/octet-stream";
            // Only verified browser-compatible images are eligible for inline preview.
            if (Set.of("png", "jpg", "jpeg", "gif", "bmp").contains(extension)) {
                try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) throw new IOException("Invalid image");
                    var reader = readers.next();
                    try {
                        reader.setInput(input);
                        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                        String expected = extension.equals("jpg") ? "jpeg" : extension;
                        if (!format.equals(expected) || reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
                            throw new IOException("Invalid image format");
                        }
                        mime = "image/" + format;
                    } finally {
                        reader.dispose();
                    }
                }
            }
            return new PreparedEcg(bytes, extension.toUpperCase(Locale.ROOT), mime);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Nie udało się odczytać pliku EKG lub obraz jest uszkodzony.");
        }
    }

    public record PreparedEcg(byte[] bytes, String format, String mimeType) {}
}
