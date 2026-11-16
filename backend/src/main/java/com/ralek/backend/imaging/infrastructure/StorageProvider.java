package com.ralek.backend.imaging.infrastructure;

import java.util.Locale;

public enum StorageProvider {
    ORTHANC,
    GOOGLE_HEALTHCARE;

    public static StorageProvider from(String value) {
        if (value == null || value.isBlank()) return ORTHANC;
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        if ("GOOGLE".equals(normalized)) normalized = "GOOGLE_HEALTHCARE";
        return valueOf(normalized);
    }
}
