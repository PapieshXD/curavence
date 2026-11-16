package com.ralek.backend.imaging.infrastructure;

import com.ralek.backend.imaging.analysis.ImagingAnalysisProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class ImagingPreviewWorkerClient {
    private final ImagingAnalysisProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI base;

    public ImagingPreviewWorkerClient(ImagingAnalysisProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.base = URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/");
    }

    public Manifest prepare(Request request) {
        HttpRequest httpRequest = authorized("v1/previews").timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(request))).build();
        byte[] body = send(httpRequest, 8_000_000);
        return mapper.readValue(body, Manifest.class);
    }

    public byte[] image(String key, String filename) {
        if (!key.matches("[a-f0-9]{64}") || !filename.matches("(?:image-\\d+|overlay-\\d+|sheet|sheet-overlay|preview)\\.png")) {
            throw new IllegalArgumentException("Invalid preview asset");
        }
        return send(authorized("v1/previews/" + key + "/" + filename)
                .timeout(Duration.ofSeconds(60)).GET().build(), 64_000_000);
    }

    private HttpRequest.Builder authorized(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).header("X-Service-Token", properties.getServiceToken());
    }

    private byte[] send(HttpRequest request, int limit) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Podgląd źródłowy nie jest dostępny");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String reason = "Nie udało się przygotować podglądu obrazów. Otwórz przeglądarkę zaawansowaną.";
                    if (response.statusCode() == 422) {
                        var detail = mapper.readTree(body.readNBytes(8192));
                        if (detail.has("detail") && detail.get("detail").isString()) reason = detail.get("detail").asString();
                    }
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
                }
                byte[] result = body.readNBytes(limit + 1);
                if (result.length > limit) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Podgląd przekracza limit rozmiaru");
                return result;
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Serwis podglądu obrazów jest chwilowo niedostępny", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Przerwano przygotowanie podglądu", exception);
        }
    }

    public record Mask(String id, String kind) { }
    public record Request(String studyInstanceUid, String seriesInstanceUid, String analysisId, List<Mask> masks) { }
    public record Frame(String id, int index, String sopInstanceUid, int frameNumber, int rows, int columns, boolean hasOverlay, String alignmentKey) { }
    public record Legend(String label, String color) { }
    public record Manifest(String cacheKey, int frameCount, List<Frame> frames, int contactSheetColumns,
                           int contactSheetRows, int cellWidth, int cellHeight, String overlayStatus,
                           String overlayMessage, List<Legend> legend, boolean hasOverlay, List<String> warnings) { }
}
