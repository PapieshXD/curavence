package com.ralek.backend.imaging.infrastructure;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class DicomStorageRegistry {

    private final Map<StorageProvider, DicomStoragePort> adapters;
    private final ImagingProperties properties;

    public DicomStorageRegistry(List<DicomStoragePort> adapters, ImagingProperties properties) {
        EnumMap<StorageProvider, DicomStoragePort> indexed = new EnumMap<>(StorageProvider.class);
        adapters.forEach(adapter -> indexed.put(adapter.provider(), adapter));
        this.adapters = Map.copyOf(indexed);
        this.properties = properties;
    }

    public StorageProvider defaultProvider() {
        return StorageProvider.from(properties.provider());
    }

    public DicomStoragePort defaultAdapter() {
        return require(defaultProvider());
    }

    public DicomStoragePort require(String provider) {
        return require(StorageProvider.from(provider));
    }

    public DicomStoragePort require(StorageProvider provider) {
        DicomStoragePort adapter = adapters.get(provider);
        if (adapter == null) throw new IllegalStateException("Brak adaptera magazynu DICOM: " + provider);
        return adapter;
    }
}
