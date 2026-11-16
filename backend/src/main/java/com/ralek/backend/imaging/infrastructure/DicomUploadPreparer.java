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
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

@Component
public class DicomUploadPreparer {

    private final ImagingProperties properties;
    private final NiftiToDicomConverter niftiConverter;

    public DicomUploadPreparer(ImagingProperties properties, NiftiToDicomConverter niftiConverter) {
        this.properties = properties;
        this.niftiConverter = niftiConverter;
    }

    public PreparedUpload prepare(MultipartFile[] files) {
        return prepare(files, null);
    }

    public PreparedUpload prepare(MultipartFile[] files,
                                  NiftiToDicomConverter.ConversionRequest niftiRequest) {
        if (files == null || files.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Wybierz plik NIfTI, ZIP lub pliki DICOM");
        }
        Path root = null;
        try {
            root = Files.createTempDirectory("mednode-imaging-");
            Path extracted = Files.createDirectories(root.resolve("extracted"));
            Path dicom = Files.createDirectories(root.resolve("dicom"));
            Path originals = Files.createDirectories(root.resolve("original"));
            List<MultipartFile> selected = java.util.Arrays.stream(files)
                    .filter(file -> file != null && !file.isEmpty())
                    .toList();
            List<MultipartFile> niftiFiles = selected.stream()
                    .filter(file -> isNifti(safeName(file.getOriginalFilename())))
                    .toList();
            if (!niftiFiles.isEmpty()) {
                if (selected.size() != 1 || niftiFiles.size() != 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Plik NIfTI prześlij pojedynczo; nie łącz go z DICOM ani ZIP");
                }
                if (niftiRequest == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Brak danych potrzebnych do importu NIfTI");
                }
                return prepareNifti(root, dicom, originals, niftiFiles.getFirst(), niftiRequest);
            }

            int zipFiles = 0;
            int copied = 0;

            for (MultipartFile file : selected) {
                String name = safeName(file.getOriginalFilename());
                if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    zipFiles++;
                    if (selected.size() != 1 || zipFiles > 1) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Archiwum ZIP prześlij jako pojedynczy plik");
                    }
                    extractZip(file, extracted);
                } else if (!isArchive(name)) {
                    Path target = dicom.resolve(UUID.randomUUID() + "-" + name);
                    try (InputStream input = file.getInputStream()) {
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    copied++;
                } else {
                    throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "Dozwolone są pliki .nii, .nii.gz, pliki DICOM lub jedno archiwum .zip");
                }
            }

            if (zipFiles == 1) {
                try (var paths = Files.walk(extracted)) {
                    List<Path> dicomFiles = paths
                            .filter(Files::isRegularFile)
                            .filter(this::isDicomCandidate)
                            .sorted()
                            .toList();
                    for (Path source : dicomFiles) {
                        Files.copy(source, dicom.resolve(UUID.randomUUID() + "-" + source.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                        copied++;
                    }
                }
            }
            if (copied == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Nie znaleziono żadnych plików DICOM");
            }
            return new PreparedUpload(root, dicom, copied, "DICOM", null, null, null);
        } catch (IOException exception) {
            deleteQuietly(root);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nie udało się przygotować przesłanych plików", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(root);
            throw exception;
        }
    }

    private PreparedUpload prepareNifti(Path root, Path dicom, Path originals, MultipartFile file,
                                        NiftiToDicomConverter.ConversionRequest request) throws IOException {
        String name = safeName(file.getOriginalFilename());
        Path original = originals.resolve(name);
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, original, StandardCopyOption.REPLACE_EXISTING);
        }

        Path nifti = original;
        if (name.toLowerCase(Locale.ROOT).endsWith(".nii.gz")) {
            nifti = root.resolve("volume.nii");
            try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(original))) {
                Files.copy(gzip, nifti, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Plik .nii.gz nie jest prawidłowym archiwum GZIP", exception);
            }
        }

        var result = niftiConverter.convert(nifti, dicom, request);
        String mimeType = name.toLowerCase(Locale.ROOT).endsWith(".gz")
                ? "application/gzip" : "application/x-nifti";
        return new PreparedUpload(root, dicom, result.instanceCount(), "NIFTI",
                original, name, mimeType);
    }

    private void extractZip(MultipartFile file, Path destination) throws IOException {
        Path archive = Files.createTempFile(destination.getParent(), "upload-", ".zip");
        try {
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING);
            }

            int entries = 0;
            long totalBytes = 0;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                var archiveEntries = zip.entries();
                while (archiveEntries.hasMoreElements()) {
                    ZipEntry entry = archiveEntries.nextElement();
                    entries++;
                    if (entries > properties.maxArchiveEntries()) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                "Archiwum zawiera zbyt wiele plików");
                    }
                    String lowerName = entry.getName().toLowerCase(Locale.ROOT);
                    if (!entry.isDirectory() && isNestedArchive(lowerName)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Archiwum zawiera niedozwolone archiwum zagnieżdżone");
                    }
                    Path target = destination.resolve(entry.getName()).normalize();
                    if (!target.startsWith(destination) || entry.getName().contains("\\")) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Archiwum zawiera niedozwoloną ścieżkę");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }

                    Files.createDirectories(target.getParent());
                    long entryBytes = 0;
                    byte[] buffer = new byte[64 * 1024];
                    try (InputStream input = zip.getInputStream(entry);
                         var output = Files.newOutputStream(target)) {
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            entryBytes = Math.addExact(entryBytes, read);
                            totalBytes = Math.addExact(totalBytes, read);
                            if (totalBytes > properties.maxArchiveUncompressedBytes()) {
                                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                        "Archiwum po rozpakowaniu przekracza dozwolony rozmiar");
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                    long compressed = entry.getCompressedSize();
                    if (compressed > 0 && entryBytes / Math.max(1, compressed)
                            > properties.maxArchiveCompressionRatio()) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                "Archiwum zawiera plik o niedozwolonym współczynniku kompresji");
                    }
                }
            }
        } catch (ZipException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Archiwum ZIP jest uszkodzone lub ma nieobsługiwany format", exception);
        } finally {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) { }
        }
    }

    private String safeName(String original) {
        if (original == null || original.isBlank()) return "upload.bin";
        String value = Path.of(original.replace('\\', '/')).getFileName().toString();
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private boolean isNifti(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".nii") || lower.endsWith(".nii.gz");
    }

    private boolean isArchive(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private boolean isNestedArchive(String lowerName) {
        return lowerName.endsWith(".zip") || lowerName.endsWith(".7z") || lowerName.endsWith(".rar")
                || lowerName.endsWith(".tar") || lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz");
    }

    private boolean isDicomCandidate(Path path) {
        String name = path.getFileName().toString();
        if ("DICOMDIR".equalsIgnoreCase(name) || name.startsWith(".")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dcm") || !name.contains(".");
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    public record PreparedUpload(Path root, Path dicomDirectory, int fileCount, String sourceFormat,
                                 Path originalFile, String originalFilename,
                                 String originalMimeType) implements AutoCloseable {
        public List<Path> dicomFiles() {
            try (var paths = Files.list(dicomDirectory)) {
                return new ArrayList<>(paths.filter(Files::isRegularFile).sorted().toList());
            } catch (IOException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Nie udało się odczytać przygotowanych plików", exception);
            }
        }

        @Override
        public void close() {
            deleteQuietly(root);
        }
    }
}
