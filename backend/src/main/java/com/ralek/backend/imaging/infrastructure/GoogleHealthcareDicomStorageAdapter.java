package com.ralek.backend.imaging.infrastructure;

import com.google.auth.oauth2.GoogleCredentials;
import com.ralek.backend.imaging.domain.DicomSeriesMetadata;
import com.ralek.backend.imaging.domain.DicomStoreResult;
import com.ralek.backend.imaging.domain.DicomStudyMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class GoogleHealthcareDicomStorageAdapter implements DicomStoragePort {

    private static final String CLOUD_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final Pattern SAFE_DICOMWEB_PATH = Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*$");
    private static final Pattern SAFE_DICOMWEB_QUERY = Pattern.compile("^[A-Za-z0-9._~!$'()*+,;:@%&=/-]*$");
    private static final Set<String> FORWARDED_RESPONSE_HEADERS = Set.of(
            "content-type", "content-length", "content-disposition", "etag", "last-modified",
            "cache-control", "accept-ranges", "content-range");

    private final GoogleHealthcareProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile GoogleCredentials credentials;

    public GoogleHealthcareDicomStorageAdapter(GoogleHealthcareProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.GOOGLE_HEALTHCARE;
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
                throw gatewayError("Nie udało się odczytać instancji DICOM", exception);
            }
            HttpRequest request = baseRequest("/studies")
                    .header("Content-Type", "multipart/related; type=\"application/dicom\"; boundary=" + boundary)
                    .header("Accept", "application/dicom+json")
                    .POST(body).build();
            HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 409) requireSuccess(response.statusCode(), response.body());
            stored++;
        }
        return new DicomStoreResult(null, stored);
    }

    @Override
    public DicomStudyMetadata getStudy(String studyInstanceUid) {
        JsonNode studies = getDicomJson("/studies?StudyInstanceUID="
                + URLEncoder.encode(studyInstanceUid, StandardCharsets.UTF_8));
        if (!studies.isArray() || studies.isEmpty()) {
            throw gatewayError("Google DICOM Store nie zwrócił zapisanego badania", null);
        }
        JsonNode study = studies.get(0);
        return new DicomStudyMetadata(
                text(study, "0020000D"), firstNonBlank(text(study, "00080061"), text(study, "00080060")),
                text(study, "00081030"), text(study, "00180015"),
                dicomInstant(text(study, "00080020"), text(study, "00080030")),
                number(study, "00201206"), number(study, "00201208"));
    }

    @Override
    public List<DicomSeriesMetadata> getSeries(String studyInstanceUid) {
        JsonNode series = getDicomJson("/studies/" + studyInstanceUid + "/series");
        List<DicomSeriesMetadata> result = new ArrayList<>();
        if (!series.isArray()) return result;
        for (JsonNode item : series) {
            result.add(new DicomSeriesMetadata(
                    text(item, "0020000E"), nullableNumber(item, "00200011"),
                    text(item, "0008103E"), text(item, "00180024"), number(item, "00201209")));
        }
        return List.copyOf(result);
    }

    @Override
    public DicomWebResponse execute(DicomWebRequest source, String enforcedStudyInstanceUid) {
        String path = source.path();
        if (path == null || path.isBlank()) path = "/";
        if (!path.startsWith("/") || path.contains("..") || path.contains("\\")
                || !SAFE_DICOMWEB_PATH.matcher(path).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowa ścieżka DICOMweb");
        }

        String query = source.queryString();
        if (query != null && !query.isBlank() && !SAFE_DICOMWEB_QUERY.matcher(query).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowe parametry DICOMweb");
        }

        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        if ("studies".equals(normalizedPath) && enforcedStudyInstanceUid != null) {
            String filter = "StudyInstanceUID="
                    + URLEncoder.encode(enforcedStudyInstanceUid, StandardCharsets.UTF_8);
            query = query == null || query.isBlank() ? filter : query + "&" + filter;
        }
        String uri = (path.startsWith("/") ? path : "/" + path)
                + (query == null || query.isBlank() ? "" : "?" + query);
        HttpRequest.Builder builder = baseRequest(uri)
                .method(source.method().equalsIgnoreCase("HEAD") ? "HEAD" : "GET",
                        HttpRequest.BodyPublishers.noBody());
        copyHeader(source, builder, "Accept");
        copyHeader(source, builder, "Range");
        copyHeader(source, builder, "If-None-Match");
        copyHeader(source, builder, "If-Modified-Since");
        HttpResponse<java.io.InputStream> response = send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (FORWARDED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) headers.put(name, values);
        });
        return new DicomWebResponse(response.statusCode(), Map.copyOf(headers), response.body());
    }

    private JsonNode getDicomJson(String path) {
        HttpResponse<String> response = send(baseRequest(path)
                .header("Accept", "application/dicom+json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), response.body());
        try {
            return objectMapper.readTree(response.body());
        } catch (RuntimeException exception) {
            throw gatewayError("Google DICOM Store zwrócił nieprawidłowe metadane", exception);
        }
    }

    private HttpRequest.Builder baseRequest(String path) {
        String base = "https://healthcare.googleapis.com/v1/" + properties.resourceName() + "/dicomWeb";
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + accessToken());
    }

    private synchronized String accessToken() {
        try {
            if (credentials == null) {
                credentials = GoogleCredentials.getApplicationDefault().createScoped(List.of(CLOUD_SCOPE));
            }
            credentials.refreshIfExpired();
            if (credentials.getAccessToken() == null) credentials.refresh();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException exception) {
            throw gatewayError("Nie można uzyskać poświadczeń Google Application Default Credentials", exception);
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return httpClient.send(request, handler);
        } catch (IOException exception) {
            throw gatewayError("Nie można połączyć się z Google Cloud Healthcare API", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw gatewayError("Przerwano komunikację z Google Cloud Healthcare API", exception);
        }
    }

    private void requireSuccess(int status, String body) {
        if (status < 200 || status >= 300) {
            String detail = body == null ? "" : body.substring(0, Math.min(body.length(), 300));
            throw gatewayError("Google DICOM Store odrzucił żądanie (HTTP " + status + "): " + detail, null);
        }
    }

    private void copyHeader(DicomWebRequest source, HttpRequest.Builder target, String name) {
        source.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).filter(value -> value != null && !value.isBlank())
                .forEach(value -> target.header(name, value));
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
            String normalized = time == null ? "000000" : (time.replace(".", "") + "000000").substring(0, 6);
            return LocalDateTime.of(day, LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HHmmss")))
                    .toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) { return null; }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private ResponseStatusException gatewayError(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
