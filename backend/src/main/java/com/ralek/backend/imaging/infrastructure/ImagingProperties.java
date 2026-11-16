package com.ralek.backend.imaging.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ralek.imaging")
public record ImagingProperties(
        String provider,
        String orthancBaseUrl,
        String orthancUsername,
        String orthancPassword,
        String ohifBaseUrl,
        String publicApiBaseUrl,
        Duration viewerSessionTtl,
        int maxArchiveEntries,
        long maxArchiveUncompressedBytes,
        int maxArchiveCompressionRatio,
        long maxUploadBytes
) {
}
