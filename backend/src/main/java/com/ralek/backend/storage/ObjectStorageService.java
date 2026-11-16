package com.ralek.backend.storage;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class ObjectStorageService {

    private final MinioClient client;
    private final StorageProperties properties;

    public ObjectStorageService(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public StoredObject putOriginal(String objectKey, String mimeType, long size, InputStream source) {
        return put(properties.originalBucket(), objectKey, mimeType, size, source);
    }

    public StoredObject putDerived(String objectKey, String mimeType, long size, InputStream source) {
        return put(properties.derivedBucket(), objectKey, mimeType, size, source);
    }

    private StoredObject put(String bucket, String objectKey, String mimeType, long size, InputStream source) {
        requireEnabled();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(source, digest)) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .contentType(mimeType)
                        .stream(input, size, -1)
                        .build());
            }
            return new StoredObject(bucket, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new StorageException("Nie udało się obliczyć sumy kontrolnej pliku", exception);
        } catch (Exception exception) {
            throw new StorageException("Nie udało się zapisać pliku w MinIO", exception);
        }
    }

    public GetObjectResponse get(String bucket, String objectKey) {
        requireEnabled();
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new StorageException("Nie udało się pobrać pliku z MinIO", exception);
        }
    }

    public String originalBucket() {
        return properties.originalBucket();
    }

    public void remove(String bucket, String objectKey) {
        requireEnabled();
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build()); }
        catch (Exception exception) { throw new StorageException("Nie udało się usunąć wygasłego pakietu", exception); }
    }

    public String derivedBucket() {
        return properties.derivedBucket();
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new StorageException("Magazyn obiektowy jest wyłączony", null);
        }
    }

    public record StoredObject(String bucket, String sha256) {
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
