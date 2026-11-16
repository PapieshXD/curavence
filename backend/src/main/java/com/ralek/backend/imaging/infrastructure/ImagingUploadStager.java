package com.ralek.backend.imaging.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
public class ImagingUploadStager {

    private final ImagingProperties properties;

    public ImagingUploadStager(ImagingProperties properties) {
        this.properties = properties;
    }

    public StagedUpload stage(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie wybrano plików badania");
        }
        Path root = null;
        try {
            root = Files.createTempDirectory("mednode-imaging-staging-");
            Path incoming = Files.createDirectories(root.resolve("incoming"));
            List<MultipartFile> staged = new ArrayList<>();
            long total = 0;
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                long size = file.getSize();
                total = Math.addExact(total, size);
                if (total > properties.maxUploadBytes()) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "Przekroczono maksymalny rozmiar importu obrazowego");
                }
                String original = safeName(file.getOriginalFilename());
                Path target = incoming.resolve(UUID.randomUUID() + "-" + original);
                try (InputStream input = file.getInputStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                staged.add(new PathMultipartFile(target, original,
                        file.getContentType() == null ? "application/octet-stream" : file.getContentType()));
            }
            if (staged.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Przesłane pliki są puste");
            }
            return new StagedUpload(root, staged.toArray(MultipartFile[]::new), total);
        } catch (IOException | ArithmeticException exception) {
            deleteQuietly(root);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nie udało się zapisać przesłanych plików w katalogu roboczym", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(root);
            throw exception;
        }
    }

    public StagedUpload resume(Path root) {
        try {
            Path temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            Path resolved = root.toRealPath();
            if (!resolved.getParent().equals(temp)
                    || !resolved.getFileName().toString().startsWith("mednode-imaging-staging-")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy katalog wznowienia importu");
            }
            Path incoming = resolved.resolve("incoming");
            List<MultipartFile> files;
            try (var paths = Files.list(incoming)) {
                files = paths.filter(Files::isRegularFile).sorted().map(path -> {
                    String stored = path.getFileName().toString();
                    String original = stored.length() > 37 && stored.charAt(36) == '-'
                            ? stored.substring(37) : stored;
                    return (MultipartFile) new PathMultipartFile(path, original, "application/octet-stream");
                }).toList();
            }
            long total = files.stream().mapToLong(MultipartFile::getSize).sum();
            if (files.isEmpty() || total > properties.maxUploadBytes()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie można wznowić pustego importu");
            }
            return new StagedUpload(resolved, files.toArray(MultipartFile[]::new), total);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Katalog roboczy importu nie jest już dostępny", exception);
        }
    }

    public void discard(Path root) {
        resume(root).close();
    }

    private String safeName(String value) {
        if (value == null || value.isBlank()) return "upload.bin";
        String result = Path.of(value.replace('\\', '/')).getFileName().toString();
        return result.length() <= 255 ? result : result.substring(result.length() - 255);
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public record StagedUpload(Path root, MultipartFile[] files, long totalBytes) implements AutoCloseable {
        @Override
        public void close() {
            deleteQuietly(root);
        }
    }

    private record PathMultipartFile(Path path, String originalFilename,
                                     String contentType) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return getSize() == 0; }
        @Override public long getSize() {
            try { return Files.size(path); } catch (IOException exception) { return 0; }
        }
        @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        @Override public void transferTo(java.io.File destination) throws IOException {
            Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
