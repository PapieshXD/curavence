package com.ralek.backend.imaging.api;

import com.ralek.backend.imaging.application.ViewerSessionService;
import com.ralek.backend.imaging.application.ViewerSessionService.ViewerSession;
import com.ralek.backend.imaging.infrastructure.DicomStoragePort;
import com.ralek.backend.imaging.infrastructure.DicomStoragePort.DicomWebRequest;
import com.ralek.backend.imaging.infrastructure.DicomStorageRegistry;
import com.ralek.backend.imaging.infrastructure.ImagingProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public class DicomWebProxyController {

    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "content-type", "content-length", "content-disposition", "etag", "last-modified",
            "accept-ranges", "content-range");
    private final ViewerSessionService sessions;
    private final DicomStorageRegistry storages;
    private final ImagingProperties properties;

    public DicomWebProxyController(ViewerSessionService sessions, DicomStorageRegistry storages,
                                   ImagingProperties properties) {
        this.sessions = sessions;
        this.storages = storages;
        this.properties = properties;
    }

    @GetMapping(value = "/api/v1/viewer/{sessionId}/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> config(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "false") boolean simplified
    ) {
        sessions.requireForConfig(sessionId);
        String root = properties.publicApiBaseUrl().replaceAll("/+$", "")
                + "/api/v1/viewer/" + sessionId + "/dicomweb";
        Map<String, Object> configuration = Map.ofEntries(
                Map.entry("name", "Curavence"), Map.entry("wadoUriRoot", root),
                Map.entry("qidoRoot", root), Map.entry("wadoRoot", root),
                Map.entry("qidoSupportsIncludeField", true), Map.entry("supportsReject", false),
                Map.entry("imageRendering", "wadors"), Map.entry("thumbnailRendering", "wadors"),
                Map.entry("enableStudyLazyLoad", true), Map.entry("supportsFuzzyMatching", false),
                Map.entry("supportsWildcard", true),
                Map.entry("requestOptions", Map.of("requestFromBrowser", true, "withCredentials", true))
        );
        Map<String, Object> dataSource = Map.of(
                "namespace", "@ohif/extension-default.dataSourcesModule.dicomweb",
                "sourceName", "dicomweb",
                "configuration", configuration
        );
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("routerBasename", "/");
        config.put("showStudyList", false);
        config.put("extensions", List.of());
        config.put("modes", List.of());
        config.put("dataSources", List.of(dataSource));
        config.put("defaultDataSourceName", "dicomweb");
        config.put("showWarningMessageForCrossOrigin", false);
        config.put("showCPUFallbackMessage", true);
        config.put("showLoadingIndicator", true);
        if (simplified) config.put("modesConfiguration", simplifiedViewerMode());
        return Map.copyOf(config);
    }

    private Map<String, Object> simplifiedViewerMode() {
        Map<String, Object> props = Map.of(
                "leftPanels", Map.of("$set", List.of()),
                "leftPanelClosed", Map.of("$set", true)
        );
        Map<String, Object> route = Map.of(
                "layoutInstance", Map.of("props", props)
        );
        return Map.of(
                "@ohif/mode-longitudinal",
                Map.of("routes", Map.of("0", route))
        );
    }

    @RequestMapping("/api/v1/viewer/{sessionId}/dicomweb/**")
    public void dicomWeb(@PathVariable UUID sessionId, HttpServletRequest request,
                         HttpServletResponse response) {
        ViewerSession session = sessions.require(sessionId, request);
        String marker = "/api/v1/viewer/" + sessionId + "/dicomweb";
        String uri = request.getRequestURI();
        int index = uri.indexOf(marker);
        String path = index < 0 ? "/" : uri.substring(index + marker.length());
        if (path.isBlank()) path = "/";
        sessions.validateDicomWebRequest(session, path, request);
        DicomStoragePort storage = storages.require(session.storageProvider());
        try (var upstream = storage.execute(new DicomWebRequest(request.getMethod(), path,
                request.getQueryString(), requestHeaders(request)), session.studyInstanceUid())) {
            response.setStatus(upstream.status());
            upstream.headers().forEach((name, values) -> {
                if (FORWARDED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> response.addHeader(name, value));
                }
            });
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("X-Content-Type-Options", "nosniff");
            if (!"HEAD".equalsIgnoreCase(request.getMethod()) && upstream.body() != null) {
                upstream.body().transferTo(response.getOutputStream());
            }
        } catch (IOException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Przerwano strumieniowanie obrazu DICOM", exception);
        }
    }

    private Map<String, List<String>> requestHeaders(HttpServletRequest request) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (!Set.of("accept", "range", "if-none-match", "if-modified-since")
                    .contains(name.toLowerCase(Locale.ROOT))) continue;
            List<String> values = new ArrayList<>();
            request.getHeaders(name).asIterator().forEachRemaining(values::add);
            result.put(name, List.copyOf(values));
        }
        return Map.copyOf(result);
    }
}
