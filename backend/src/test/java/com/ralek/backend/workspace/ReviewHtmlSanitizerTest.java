package com.ralek.backend.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReviewHtmlSanitizerTest {
    @Test
    void retainsFormattingAndEmbeddedPngButRemovesActiveContent() {
        String png = "data:image/png;base64,iVBORw0KGgo=";
        String cleaned = ReviewHtmlSanitizer.sanitize("<p><strong>Opis</strong><script>alert(1)</script>"
                + "<img src=\"" + png + "\" onerror=\"alert(1)\"></p>");
        assertTrue(cleaned.contains("<strong>Opis</strong>"));
        assertTrue(cleaned.contains(png));
        assertFalse(cleaned.contains("onerror"));
        assertFalse(cleaned.contains("<script"));
    }

    @Test
    void rejectsExternalAndFalselyTypedImages() {
        assertThrows(ResponseStatusException.class,
                () -> ReviewHtmlSanitizer.sanitize("<p><img src='https://example.com/secret.png'></p>"));
        assertThrows(ResponseStatusException.class,
                () -> ReviewHtmlSanitizer.sanitize("<p><img src='data:image/png;base64,QUJD'></p>"));
    }
}
