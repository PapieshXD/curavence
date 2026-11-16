package com.ralek.backend.document;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Component
public class OllamaDocumentQuestionClient implements DocumentQuestionClient {

    private final DocumentChatProperties properties;
    private final RestClient restClient;

    public OllamaDocumentQuestionClient(DocumentChatProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Answer answer(List<ChatMessage> messages) {
        return ask(messages, false);
    }

    @Override
    public Answer answerJson(List<ChatMessage> messages) {
        return ask(messages, true);
    }

    private Answer ask(List<ChatMessage> messages, boolean json) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Czat z dokumentem jest obecnie wyłączony");
        }
        try {
            Map<String, Object> body = new HashMap<>(Map.of(
                    "model", properties.getModel(),
                    "stream", false,
                    "think", false,
                    "messages", messages,
                    "options", Map.of("temperature", json ? 0 : 0.1)
            ));
            if (json) body.put("format", "json");
            OllamaResponse response = restClient.post()
                    .uri(properties.getPath())
                    .body(body)
                    .retrieve()
                    .body(OllamaResponse.class);
            if (response == null || response.message() == null
                    || response.message().content() == null || response.message().content().isBlank()) {
                throw new ResponseStatusException(BAD_GATEWAY, "Model zwrócił pustą odpowiedź");
            }
            return new Answer(response.message().content().trim(),
                    response.model() == null ? properties.getModel() : response.model());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Nie udało się połączyć z lokalnym modelem językowym", exception);
        }
    }

    private record OllamaResponse(String model, OllamaMessage message) {
    }

    private record OllamaMessage(String role, String content) {
    }
}
