package com.ralek.backend.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentProcessorTest {

    private final PdfDocumentProcessor processor = new PdfDocumentProcessor(
            new DocumentProperties(1024 * 1024, 3, 1_000, 10));

    @Test
    void extractsTextFromDigitalPdf() throws IOException {
        MockMultipartFile file = pdf("wyniki.pdf", "Hemoglobin 14.2 WBC 7.1 Platelets 240");

        PdfDocumentProcessor.PreparedPdf result = processor.process(file);

        assertThat(result.pages()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(PdfDocumentProcessor.ProcessingStatus.TEXT_EXTRACTED);
        assertThat(result.text()).contains("Hemoglobin 14.2", "Platelets 240");
        assertThat(result.textTruncated()).isFalse();
    }

    @Test
    void marksImageOnlyPdfForOcr() throws IOException {
        MockMultipartFile file = pdf("skan.pdf", null);

        PdfDocumentProcessor.PreparedPdf result = processor.process(file);

        assertThat(result.status()).isEqualTo(PdfDocumentProcessor.ProcessingStatus.OCR_REQUIRED);
        assertThat(result.text()).isEmpty();
    }

    @Test
    void rejectsFileWhoseContentIsNotPdf() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", "not a pdf".getBytes());

        assertThatThrownBy(() -> processor.process(file))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void rejectsEncryptedPdf() throws IOException {
        byte[] encrypted;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-secret", "user-secret", new AccessPermission());
            document.protect(policy);
            document.save(output);
            encrypted = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "secured.pdf", "application/pdf", encrypted);

        assertThatThrownBy(() -> processor.process(file))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    @Test
    void enforcesPageLimit() throws IOException {
        byte[] content;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int page = 0; page < 4; page++) document.addPage(new PDPage());
            document.save(output);
            content = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "long.pdf", "application/pdf", content);

        assertThatThrownBy(() -> processor.process(file))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
    }

    private MockMultipartFile pdf(String filename, String text) throws IOException {
        byte[] content;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(40, 740);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(output);
            content = output.toByteArray();
        }
        return new MockMultipartFile("file", filename, "application/pdf", content);
    }
}
