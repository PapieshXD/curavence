package com.ralek.backend.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

@Component
public class PdfDocumentProcessor {

    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final Set<String> PDF_MIME_TYPES = Set.of(
            "application/pdf", "application/x-pdf", "application/octet-stream");

    private final DocumentProperties properties;

    public PdfDocumentProcessor(DocumentProperties properties) {
        this.properties = properties;
    }

    public PreparedPdf process(MultipartFile file) {
        validateUpload(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie udało się odczytać pliku PDF");
        }
        if (!startsWith(bytes, PDF_SIGNATURE)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Zawartość pliku nie jest dokumentem PDF");
        }

        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Zaszyfrowane dokumenty PDF nie są obsługiwane");
            }
            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Dokument PDF nie zawiera żadnej strony");
            }
            if (pages > properties.maxPages()) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                        "Dokument PDF może mieć maksymalnie " + properties.maxPages() + " stron");
            }

            ExtractedText extracted = extractText(document);
            int meaningfulCharacters = (int) extracted.text().codePoints()
                    .filter(codePoint -> !Character.isWhitespace(codePoint))
                    .count();
            ProcessingStatus status = meaningfulCharacters >= properties.minimumDigitalTextCharacters()
                    ? ProcessingStatus.TEXT_EXTRACTED
                    : ProcessingStatus.OCR_REQUIRED;
            String text = status == ProcessingStatus.TEXT_EXTRACTED ? extracted.text() : "";
            return new PreparedPdf(bytes, pages, text, status, extracted.truncated());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Zaszyfrowane dokumenty PDF nie są obsługiwane");
        } catch (IOException | RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Plik PDF jest uszkodzony lub ma nieobsługiwaną strukturę");
        }
    }

    private ExtractedText extractText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        StringBuilder result = new StringBuilder();
        boolean truncated = false;

        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = normalize(stripper.getText(document));
            if (pageText.isBlank()) {
                continue;
            }
            int separatorLength = result.isEmpty() ? 0 : 2;
            int remaining = properties.maxExtractedCharacters() - result.length() - separatorLength;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            if (pageText.length() > remaining) {
                result.append(pageText, 0, remaining);
                truncated = true;
                break;
            }
            result.append(pageText);
        }
        return new ExtractedText(result.toString().trim(), truncated);
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wybierz plik PDF");
        }
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "Plik PDF może mieć maksymalnie " + toMegabytes(properties.maxUploadBytes()) + " MB");
        }
        String filename = file.getOriginalFilename() == null
                ? ""
                : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().toLowerCase(Locale.ROOT);
        if (!PDF_MIME_TYPES.contains(contentType) && !filename.endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Dozwolone są wyłącznie dokumenty PDF");
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static long toMegabytes(long bytes) {
        return Math.max(1, bytes / 1024 / 1024);
    }

    public enum ProcessingStatus {
        TEXT_EXTRACTED,
        OCR_REQUIRED
    }

    public record PreparedPdf(
            byte[] bytes,
            int pages,
            String text,
            ProcessingStatus status,
            boolean textTruncated
    ) {
    }

    private record ExtractedText(String text, boolean truncated) {
    }
}
