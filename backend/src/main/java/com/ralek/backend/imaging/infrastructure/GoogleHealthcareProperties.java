package com.ralek.backend.imaging.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ralek.imaging.google")
public record GoogleHealthcareProperties(
        String projectId,
        String location,
        String datasetId,
        String dicomStoreId
) {
    public String resourceName() {
        require(projectId, "project-id");
        require(location, "location");
        require(datasetId, "dataset-id");
        require(dicomStoreId, "dicom-store-id");
        return "projects/%s/locations/%s/datasets/%s/dicomStores/%s"
                .formatted(projectId, location, datasetId, dicomStoreId);
    }

    private void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Brak konfiguracji ralek.imaging.google." + name);
        }
    }
}
