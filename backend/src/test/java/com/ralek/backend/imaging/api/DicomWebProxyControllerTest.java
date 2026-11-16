package com.ralek.backend.imaging.api;

import com.ralek.backend.imaging.application.ViewerSessionService;
import com.ralek.backend.imaging.infrastructure.ImagingProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DicomWebProxyControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void returnsOhif312DataSourceConfigurationForViewerSession() {
        ViewerSessionService sessions = mock(ViewerSessionService.class);
        ImagingProperties properties = new ImagingProperties(
                "orthanc", "http://127.0.0.1:8042", "user", "password",
                "http://127.0.0.1:3000", "http://127.0.0.1:3000", Duration.ofMinutes(10),
                100, 1024, 100, 1024);
        UUID sessionId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        DicomWebProxyController controller = new DicomWebProxyController(sessions, null, properties);

        Map<String, Object> config = controller.config(sessionId, false);
        List<Map<String, Object>> dataSources = (List<Map<String, Object>>) config.get("dataSources");
        Map<String, Object> dataSource = dataSources.getFirst();
        Map<String, Object> dicomWeb = (Map<String, Object>) dataSource.get("configuration");

        assertThat(dataSource.get("sourceName")).isEqualTo("dicomweb");
        assertThat(dicomWeb.get("qidoRoot")).isEqualTo(
                "http://127.0.0.1:3000/api/v1/viewer/" + sessionId + "/dicomweb");
        assertThat(dicomWeb.get("requestOptions"))
                .isEqualTo(Map.of("requestFromBrowser", true, "withCredentials", true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void simplifiedConfigRemovesSeriesBrowserFromLongitudinalMode() {
        ViewerSessionService sessions = mock(ViewerSessionService.class);
        ImagingProperties properties = new ImagingProperties(
                "orthanc", "http://127.0.0.1:8042", "user", "password",
                "http://127.0.0.1:3000", "http://127.0.0.1:3000", Duration.ofMinutes(10),
                100, 1024, 100, 1024);
        DicomWebProxyController controller = new DicomWebProxyController(sessions, null, properties);

        Map<String, Object> config = controller.config(UUID.randomUUID(), true);
        Map<String, Object> modes = (Map<String, Object>) config.get("modesConfiguration");
        Map<String, Object> longitudinal = (Map<String, Object>) modes.get("@ohif/mode-longitudinal");

        assertThat(longitudinal.toString()).contains("leftPanels={$set=[]}");
    }
}
