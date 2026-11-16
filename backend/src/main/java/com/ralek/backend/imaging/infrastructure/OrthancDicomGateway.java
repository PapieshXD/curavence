package com.ralek.backend.imaging.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ralek.backend.imaging.domain.DicomSeriesMetadata;
import com.ralek.backend.imaging.domain.DicomStoreResult;
import com.ralek.backend.imaging.domain.DicomStudyMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class OrthancDicomGateway implements DicomStoragePort {

    private static final Set<String> FORWARDED_RESPONSE_HEADERS = Set.of(
            "content-type", "content-length", "content-disposition", "etag", "last-modified",
            "cache-control", "accept-ranges", "content-range");
    private final ImagingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String authorization;

    public OrthancDicomGateway(ImagingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String credentials = properties.orthancUsername() + ":" + properties.orthancPassword();
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.ORTHANC;
    }

    @Override
    public DicomStoreResult store(Path dicomDirectory) {
        List<Path> files;
        try (var paths = Files.list(dicomDirectory)) {
            files = paths.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException exception) {
            throw gatewayError("Nie udało się odczytać katalogu DICOM", exception);
        }
        if (files.isEmpty()) throw gatewayError("Brak plików DICOM do zapisu", null);

        String studyUid = null;
        int stored = 0;
        for (Path file : files) {
            String boundary = "mednode-" + UUID.randomUUID();
            HttpRequest.BodyPublisher body;
            try {
                body = HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofString("--" + boundary
                                + "\r\nContent-Type: application/dicom\r\n\r\n"),
                        HttpRequest.BodyPublishers.ofFile(file),
                        HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            } catch (IOException exception) {
                throw gatewayError("Nie udało się odczytać pliku " + file.getFileName(), exception);
            }
            HttpRequest request = baseRequest("/dicom-web/studies")
                    .header("Content-Type", "multipart/related; type=\"application/dicom\"; boundary=" + boundary)
                    .POST(body)
                    .build();
            HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 409) requireSuccess(response.statusCode(), response.body());
            stored++;
        }
        return new DicomStoreResult(studyUid, stored);
    }

    @Override
    public DicomStudyMetadata getStudy(String studyInstanceUid) {
        String query = "/dicom-web/studies?StudyInstanceUID="
                + URLEncoder.encode(studyInstanceUid, StandardCharsets.UTF_8);
        JsonNode studies = getDicomJson(query);
        if (!studies.isArray() || studies.isEmpty()) {
            throw gatewayError("Orthanc nie zwrócił zapisanego badania", null);
        }
        JsonNode study = studies.get(0);
        return new DicomStudyMetadata(
                text(study, "0020000D"),
                firstNonBlank(text(study, "00080061"), text(study, "00080060")),
                text(study, "00081030"),
                text(study, "00180015"),
                dicomInstant(text(study, "00080020"), text(study, "00080030")),
                number(study, "00201206"),
                number(study, "00201208"));
    }

    @Override
    public List<DicomSeriesMetadata> getSeries(String studyInstanceUid) {
        JsonNode series = getDicomJson("/dicom-web/studies/" + studyInstanceUid + "/series");
        List<DicomSeriesMetadata> result = new ArrayList<>();
        if (!series.isArray()) return result;
        for (JsonNode item : series) {
            result.add(new DicomSeriesMetadata(
                    text(item, "0020000E"),
                    nullableNumber(item, "00200011"),
                    text(item, "0008103E"),
                    text(item, "00180024"),
                    number(item, "00201209")));
        }
        return List.copyOf(result);
    }

    @Override
    public DicomWebResponse execute(DicomWebRequest source, String enforcedStudyInstanceUid) {
        String path = source.path();
        if (!path.startsWith("/") || path.contains("..") || path.contains("\\") || path.contains("?") || path.contains("#")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowa ścieżka DICOMweb");
        }
        String query = source.queryString();
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        if ("studies".equals(normalizedPath) && enforcedStudyInstanceUid != null) {
            String studyFilter = "StudyInstanceUID="
                    + URLEncoder.encode(enforcedStudyInstanceUid, StandardCharsets.UTF_8);
            query = query == null || query.isBlank() ? studyFilter : query + "&" + studyFilter;
        }
        String uri = "/dicom-web" + path
                + (query == null || query.isBlank() ? "" : "?" + query);
        HttpRequest.Builder builder = baseRequest(uri)
                .method(source.method().equalsIgnoreCase("HEAD") ? "HEAD" : "GET",
                        HttpRequest.BodyPublishers.noBody());
        copyRequestHeader(source, builder, "Accept");
        copyRequestHeader(source, builder, "Range");
        copyRequestHeader(source, builder, "If-None-Match");
        copyRequestHeader(source, builder, "If-Modified-Since");

        HttpResponse<java.io.InputStream> response = send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        java.util.Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (FORWARDED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) headers.put(name, values);
        });
        return new DicomWebResponse(response.statusCode(), Map.copyOf(headers), response.body());
    }

    private JsonNode getDicomJson(String path) {
        HttpRequest request = baseRequest(path)
                .header("Accept", "application/dicom+json")
                .GET().build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), response.body());
        try {
            return objectMapper.readTree(response.body());
        } catch (RuntimeException exception) {
            throw gatewayError("Orthanc zwrócił nieprawidłowe metadane", exception);
        }
    }

    private HttpRequest.Builder baseRequest(String path) {
        URI baseUri = URI.create(properties.orthancBaseUrl().replaceAll("/+$", "") + "/");
        URI resolved = baseUri.resolve(path.startsWith("/") ? path.substring(1) : path);

        int basePort = baseUri.getPort() == -1 ? ("https".equalsIgnoreCase(baseUri.getScheme()) ? 443 : 80) : baseUri.getPort();
        int resolvedPort = resolved.getPort() == -1 ? ("https".equalsIgnoreCase(resolved.getScheme()) ? 443 : 80) : resolved.getPort();

        if (!baseUri.getScheme().equalsIgnoreCase(resolved.getScheme())
                || !baseUri.getHost().equalsIgnoreCase(resolved.getHost())
                || basePort != resolvedPort) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy adres docelowy DICOMweb");
        }

        String basePath = baseUri.getPath() == null ? "/" : baseUri.getPath();
        String resolvedPath = resolved.getPath() == null ? "/" : resolved.getPath();
        if (!resolvedPath.startsWith(basePath)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowa ścieżka DICOMweb");
        }

        return HttpRequest.newBuilder(resolved)
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", authorization);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return httpClient.send(request, handler);
        } catch (IOException exception) {
            throw gatewayError("Nie można połączyć się z Orthanc", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw gatewayError("Przerwano komunikację z Orthanc", exception);
        }
    }

    private void requireSuccess(int status, String body) {
        if (status < 200 || status >= 300) {
            String detail = body == null ? "" : body.substring(0, Math.min(body.length(), 300));
            throw gatewayError("Orthanc odrzucił żądanie (HTTP " + status + "): " + detail, null);
        }
    }

    private String text(JsonNode object, String tag) {
        JsonNode value = object.path(tag).path("Value");
        if (!value.isArray() || value.isEmpty()) return null;
        JsonNode first = value.get(0);
        return first.isTextual() || first.isNumber() ? first.asText() : null;
    }

    private int number(JsonNode object, String tag) {
        Integer value = nullableNumber(object, tag);
        return value == null ? 0 : value;
    }

    private Integer nullableNumber(JsonNode object, String tag) {
        String value = text(object, tag);
        if (value == null) return null;
        try { return Integer.valueOf(value); } catch (NumberFormatException ignored) { return null; }
    }

    private Instant dicomInstant(String date, String time) {
        if (date == null) return null;
        try {
            LocalDate day = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
            LocalTime clock = LocalTime.MIDNIGHT;
            if (time != null) {
                String normalized = (time.replace(".", "") + "000000").substring(0, 6);
                clock = LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HHmmss"));
            }
            return LocalDateTime.of(day, clock).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) { return null; }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private void copyRequestHeader(DicomWebRequest source, HttpRequest.Builder target, String name) {
        source.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> target.header(name, value));
    }

    private ResponseStatusException gatewayError(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
