package com.ralek.backend.imaging.infrastructure;

import com.ralek.backend.imaging.analysis.ImagingAnalysisProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ImagingPreviewWorkerClientTest {
    @Test
    void preparesPreviewWithoutUnsupportedHttp2Upgrade() throws Exception {
        var upgrade = new AtomicReference<String>();
        var token = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/previews", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            token.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            method.set(exchange.getRequestMethod());
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"cacheKey\":\"key\",\"frameCount\":25,\"contactSheetColumns\":5,\"contactSheetRows\":5,\"cellWidth\":64,\"cellHeight\":64,\"hasOverlay\":false,\"frames\":[],\"legend\":[],\"warnings\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(upgrade.get() == null ? 200 : 400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ImagingAnalysisProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setServiceToken("test-token");
            var client = new ImagingPreviewWorkerClient(properties, new ObjectMapper());
            var result = client.prepare(new ImagingPreviewWorkerClient.Request("1.2", "1.2.3", null, List.of()));
            assertEquals(25, result.frameCount());
            assertNull(upgrade.get());
            assertEquals("POST", method.get());
            assertEquals("test-token", token.get());
        } finally { server.stop(0); }
    }
}
