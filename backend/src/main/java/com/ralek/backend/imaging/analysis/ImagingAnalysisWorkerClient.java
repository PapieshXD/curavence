package com.ralek.backend.imaging.analysis;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class ImagingAnalysisWorkerClient {

    private final ImagingAnalysisProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI baseUri;

    public ImagingAnalysisWorkerClient(ImagingAnalysisProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        String base = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl() : properties.getBaseUrl() + "/";
        this.baseUri = URI.create(base);
    }

    public WorkerResult analyze(WorkerRequest request) {
        requireEnabled();
        HttpRequest httpRequest = authorized(baseUri.resolve("v1/analyses"))
                .timeout(properties.getReadTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(request)))
                .build();
        HttpResponse<byte[]> response = send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
            throw new IllegalStateException("Worker segmentacji odrzucił analizę (HTTP "
                    + response.statusCode() + "): " + abbreviate(detail));
        }
        WorkerResult result = objectMapper.readValue(response.body(), WorkerResult.class);
        if (result == null || result.status() == null) {
            throw new IllegalStateException("Worker segmentacji zwrócił pustą odpowiedź");
        }
        return result;
    }

    public long downloadArtifact(String analysisId, WorkerArtifact artifact, Path target) {
        requireEnabled();
        String expectedPrefix = "/v1/analyses/" + analysisId + "/artifacts/";
        if (artifact.downloadPath() == null || !artifact.downloadPath().startsWith(expectedPrefix)
                || artifact.downloadPath().contains("..")) {
            throw new IllegalStateException("Worker zwrócił nieprawidłową ścieżkę artefaktu");
        }
        URI uri = baseUri.resolve(artifact.downloadPath().substring(1));
        HttpRequest request = authorized(uri).timeout(properties.getReadTimeout()).GET().build();
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try { response.body().close(); } catch (IOException ignored) { }
            throw new IllegalStateException("Nie udało się pobrać artefaktu segmentacji (HTTP "
                    + response.statusCode() + ")");
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > properties.getMaxArtifactBytes()) {
            try { response.body().close(); } catch (IOException ignored) { }
            throw new IllegalStateException("Artefakt segmentacji przekracza dozwolony rozmiar");
        }
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > properties.getMaxArtifactBytes()) {
                    throw new IllegalStateException("Artefakt segmentacji przekracza dozwolony rozmiar");
                }
                output.write(buffer, 0, read);
            }
            return total;
        } catch (IOException exception) {
            throw new IllegalStateException("Nie udało się zapisać artefaktu segmentacji", exception);
        }
    }

    private HttpRequest.Builder authorized(URI uri) {
        return HttpRequest.newBuilder(uri).header("X-Service-Token", properties.getServiceToken());
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return httpClient.send(request, handler);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Nie można połączyć się z workerem segmentacji", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Przerwano komunikację z workerem segmentacji", exception);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Automatyczna segmentacja jest wyłączona");
        }
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) return "brak szczegółów";
        return value.substring(0, Math.min(value.length(), 800));
    }

    public record WorkerRequest(String analysisId, WorkerStudy study, WorkerOptions options) { }

    public record WorkerStudy(
            String studyInstanceUid,
            String storageProvider,
            String modality,
            String bodyPartExamined,
            String studyDescription,
            List<WorkerSeries> series
    ) { }

    public record WorkerSeries(
            String seriesInstanceUid,
            String modality,
            String description,
            String sequenceName,
            int imageCount
    ) { }

    public record WorkerOptions(boolean fast, String device, boolean runPathologyModels, String scopeMode, List<String> seriesInstanceUids) { }

    public record WorkerArtifact(
            String id,
            String kind,
            String label,
            String filename,
            String mimeType,
            long sizeBytes,
            String downloadPath,
            String seriesInstanceUid,
            Integer sliceIndex
    ) { }

    public record WorkerResult(
            String analysisId,
            String status,
            int progress,
            String pipeline,
            String engine,
            JsonNode classification,
            JsonNode quality,
            JsonNode measurements,
            JsonNode observations,
            JsonNode candidateFindings,
            List<WorkerArtifact> artifacts,
            String summary,
            JsonNode modelVersions,
            JsonNode warnings,
            String completedAt,
            String disclaimer,
            JsonNode scope, JsonNode seriesResults, JsonNode canonicalEvidence,
            String evidenceHash, JsonNode explanations, String promptVersion
    ) { }
}
