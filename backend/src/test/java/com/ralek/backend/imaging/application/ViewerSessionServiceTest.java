package com.ralek.backend.imaging.application;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewerSessionServiceTest {

    private final ViewerSessionService service = new ViewerSessionService(null, null, null, null, null, null);
    private final ViewerSessionService.ViewerSession session = new ViewerSessionService.ViewerSession(
            UUID.randomUUID(), UUID.randomUUID(), "2.25.123", UUID.randomUUID(), UUID.randomUUID(),
            "ORTHANC", "secret", Instant.now().plusSeconds(60), false);

    @Test
    void allowsUnfilteredStudyQueryBecauseGatewayAddsSessionStudyFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dicomweb/studies");

        assertThatCode(() -> service.validateDicomWebRequest(session, "/studies", request))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsWadoUriAliasOnlyForSessionStudy() {
        MockHttpServletRequest allowed = new MockHttpServletRequest("GET", "/api/v1/dicomweb/wado");
        allowed.setParameter("studyUID", "2.25.123");
        MockHttpServletRequest denied = new MockHttpServletRequest("GET", "/api/v1/dicomweb/wado");
        denied.setParameter("studyUID", "2.25.999");

        assertThatCode(() -> service.validateDicomWebRequest(session, "/wado", allowed))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateDicomWebRequest(session, "/wado", denied))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("innego badania");
    }

    @Test
    void buildsOhif312DicomWebUrlWithDynamicConfig() {
        UUID sessionId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        String url = ViewerSessionService.viewerUrl(
                "http://127.0.0.1:3000/", "http://127.0.0.1:3000/", sessionId, "2.25.123", false);

        assertThat(url).isEqualTo("http://127.0.0.1:3000/viewer/dicomweb"
                + "?configUrl=http%3A%2F%2F127.0.0.1%3A3000%2Fapi%2Fv1%2Fviewer%2F"
                + "11111111-2222-3333-4444-555555555555%2Fconfig%3Fsimplified%3Dfalse"
                + "&StudyInstanceUIDs=2.25.123&mednodeConfig=2");
    }

    @Test
    void buildsSimplifiedViewerUrlWithAllSeriesGrid() {
        UUID sessionId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        String url = ViewerSessionService.viewerUrl(
                "http://127.0.0.1:3000", "http://127.0.0.1:3000", sessionId, "2.25.123", true);

        assertThat(url).contains("simplified%3Dtrue")
                .endsWith("&hangingProtocolId=%40ohif%2FmnGrid8");
    }
}
