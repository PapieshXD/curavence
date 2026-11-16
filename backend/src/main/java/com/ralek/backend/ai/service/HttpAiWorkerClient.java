package com.ralek.backend.ai.service;

import com.ralek.backend.ai.config.AiProperties;
import com.ralek.backend.ai.dto.WorkerExplanationRequest;
import com.ralek.backend.ai.dto.WorkerExplanationResponse;
import com.ralek.backend.ai.exception.AiIntegrationException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpAiWorkerClient implements AiWorkerClient {

    private final AiProperties aiProperties;
    private final RestClient restClient;

    public HttpAiWorkerClient(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) aiProperties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) aiProperties.getReadTimeout().toMillis());

        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);

        if (aiProperties.getBaseUrl() != null && !aiProperties.getBaseUrl().isBlank()) {
            builder.baseUrl(aiProperties.getBaseUrl());
        }

        if (aiProperties.getApiKey() != null && !aiProperties.getApiKey().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + aiProperties.getApiKey());
        }

        this.restClient = builder.build();
    }

    @Override
    public WorkerExplanationResponse submitExplanation(WorkerExplanationRequest request) {
        if (aiProperties.getBaseUrl() == null || aiProperties.getBaseUrl().isBlank()) {
            throw new AiIntegrationException("AI worker base URL is missing. Set ralek.ai.base-url first.");
        }

        try {
            WorkerExplanationResponse response = restClient.post()
                    .uri(aiProperties.getSubmitPath())
                    .body(request)
                    .retrieve()
                    .body(WorkerExplanationResponse.class);

            if (response == null) {
                throw new AiIntegrationException("AI worker returned an empty response.");
            }

            return response;
        } catch (RestClientException exception) {
            throw new AiIntegrationException("Could not submit AI task to external worker.", exception);
        }
    }
}
