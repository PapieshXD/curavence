package com.ralek.backend.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ralek.storage")
public record StorageProperties(
        boolean enabled,
        String endpoint,
        String accessKey,
        String secretKey,
        String originalBucket,
        String derivedBucket,
        String tempBucket
) {
}
