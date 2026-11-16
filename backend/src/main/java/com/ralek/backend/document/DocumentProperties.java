package com.ralek.backend.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ralek.documents")
public record DocumentProperties(
        long maxUploadBytes,
        int maxPages,
        int maxExtractedCharacters,
        int minimumDigitalTextCharacters
) {
}
