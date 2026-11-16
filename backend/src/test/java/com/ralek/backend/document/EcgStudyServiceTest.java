package com.ralek.backend.document;

import com.ralek.backend.imaging.analysis.ImagingAnalysisProperties;
import com.ralek.backend.workspace.WorkspaceService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EcgStudyServiceTest {
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final DocumentQuestionClient ai = mock(DocumentQuestionClient.class);
    private HttpServer server;
    private EcgStudyService service;
    private final UUID id = UUID.randomUUID();
    private String token;
    private String received;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ecg/preview", exchange -> {
            token = exchange.getRequestHeaders().getFirst("X-Service-Token");
            received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = "{\"channels\":[],\"evidence\":{\"sampleCount\":3,\"sampleRate\":null}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var properties = new ImagingAnalysisProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setServiceToken("test-token");
        service = new EcgStudyService(workspace, ai, new ObjectMapper(), properties);
        when(workspace.ecgOriginal(null, "patient", id)).thenAnswer(invocation ->
                new WorkspaceService.DownloadedAsset("record.csv", "application/octet-stream", 5, new ByteArrayInputStream("1\n2\n3".getBytes())));
    }

    @Test void calibrationAiUsesOriginalEvidenceAndDoesNotApplyGuesses() {
        when(ai.answer(anyList())).thenReturn(new DocumentQuestionClient.Answer("Brak podstaw", "local-model"));
        var result = service.assessCalibration(null, "patient", id);
        assertThat(result.answer()).isEqualTo("Brak podstaw");
        var captor = ArgumentCaptor.forClass(List.class);
        verify(ai).answer(captor.capture());
        assertThat(captor.getValue().toString()).contains("nie identyfikują Hz", "Brak podstaw");
        verify(workspace).ecgOriginal(null, "patient", id);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void readsAuthorizedOriginalAndSendsServerEvidenceToAi() {
        when(ai.answer(anyList())).thenReturn(new DocumentQuestionClient.Answer("Opis", "local-model"));
        var result = service.ask(null, "patient", id, null, null,
                new DocumentQuestionDtos.DocumentQuestionRequest("Opisz", List.of()));
        assertThat(result.answer()).isEqualTo("Opis");
        assertThat(token).isEqualTo("test-token");
        assertThat(received).isEqualTo("1\n2\n3");
        verify(workspace).ecgOriginal(null, "patient", id);
        ArgumentCaptor<List<DocumentQuestionClient.ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(ai).answer(messages.capture());
        assertThat(messages.getValue().stream().filter(m -> m.role().equals("user")).map(DocumentQuestionClient.ChatMessage::content).toList())
                .anySatisfy(content -> assertThat(content).contains("\"sampleCount\":3", "\"sampleRate\":null"));
        assertThat(messages.getValue().get(0).content()).contains("Nie zgaduj", "Nie rozpoznawaj");
    }

    @Test void neverCallsAiWhenAccessIsDenied() {
        when(workspace.ecgOriginal(null, "patient", id)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.ask(null, "patient", id, null, null,
                new DocumentQuestionDtos.DocumentQuestionRequest("Opisz", List.of()))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ai);
    }

    @Test void rejectsInvalidCalibrationBeforeReadingPatientData() {
        assertThatThrownBy(() -> service.preview(null, "patient", id, Double.NaN, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.preview(null, "patient", id, 0.0, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.preview(null, "patient", id, null, "unknown")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(workspace);
    }

    @Test void advancedAnalysisIncludesServerMorphologyAndAudience() {
        server.createContext("/v1/ecg/morphology", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).contains("channel_index=1", "start_sample=250", "sample_count=2500");
            byte[] body = "{\"status\":\"estimated\",\"channelName\":\"II\",\"startSample\":250,\"endSample\":2750,\"summary\":{\"qrsMs\":{\"median\":100,\"count\":8}},\"markers\":[1,2]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        when(ai.answer(anyList())).thenReturn(new DocumentQuestionClient.Answer("Opis", "local-model"));
        service.askAdvanced(null, "patient", id, 250.0, "mV",
                new DocumentQuestionDtos.DocumentQuestionRequest("Opisz", List.of()), 1, 250, 2500, "clinician");
        ArgumentCaptor<List<DocumentQuestionClient.ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(ai).answer(messages.capture());
        String content = messages.getValue().toString();
        assertThat(content).contains("Tryb diagnosty", "qrsMs", "2750", "Fridericii");
        assertThat(content).doesNotContain("\"markers\":[1,2]");
        assertThat(messages.getValue().getLast().content()).contains("qrsMs", "2750", "Pytanie: Opisz");
    }
}
