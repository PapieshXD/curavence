package com.ralek.backend.ai.service;

import com.ralek.backend.ai.config.AiProperties;
import com.ralek.backend.ai.dto.AiCapabilityResponse;
import com.ralek.backend.ai.dto.ExplainResultRequest;
import com.ralek.backend.ai.dto.ExplainResultResponse;
import com.ralek.backend.ai.dto.WorkerExplanationRequest;
import com.ralek.backend.ai.dto.WorkerExplanationResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AiOrchestrationService {

    private static final List<String> SUPPORTED_OPERATIONS = List.of(
            "EXPLAIN_RESULT",
            "SUMMARIZE_DOCUMENT",
            "EXTRACT_STRUCTURED_DATA",
            "GENERATE_PATIENT_QUESTIONS"
    );

    private final AiProperties aiProperties;
    private final AiWorkerClient aiWorkerClient;

    public AiOrchestrationService(AiProperties aiProperties, AiWorkerClient aiWorkerClient) {
        this.aiProperties = aiProperties;
        this.aiWorkerClient = aiWorkerClient;
    }

    public AiCapabilityResponse getCapabilities() {
        return new AiCapabilityResponse(
                aiProperties.isEnabled(),
                aiProperties.isMockMode(),
                aiProperties.getProvider(),
                aiProperties.getModel(),
                SUPPORTED_OPERATIONS
        );
    }

    public ExplainResultResponse submitExplanation(ExplainResultRequest request) {
        String localJobId = UUID.randomUUID().toString();

        if (!aiProperties.isEnabled() || aiProperties.isMockMode()) {
            return new ExplainResultResponse(
                    localJobId,
                    "QUEUED_MOCK",
                    aiProperties.getProvider(),
                    aiProperties.getModel(),
                    "AI request accepted in mock mode. No external GPU worker was called."
            );
        }

        WorkerExplanationResponse workerResponse = aiWorkerClient.submitExplanation(toWorkerRequest(localJobId, request));

        return new ExplainResultResponse(
                workerResponse.remoteJobId() != null && !workerResponse.remoteJobId().isBlank()
                        ? workerResponse.remoteJobId()
                        : localJobId,
                workerResponse.status() != null && !workerResponse.status().isBlank()
                        ? workerResponse.status()
                        : "QUEUED",
                aiProperties.getProvider(),
                aiProperties.getModel(),
                workerResponse.message() != null && !workerResponse.message().isBlank()
                        ? workerResponse.message()
                        : "AI request accepted by external worker."
        );
    }

    private WorkerExplanationRequest toWorkerRequest(String localJobId, ExplainResultRequest request) {
        return new WorkerExplanationRequest(
                localJobId,
                "EXPLAIN_RESULT",
                aiProperties.getModel(),
                request.patientId(),
                request.documentId(),
                request.requestedBy(),
                request.language(),
                request.forceRefresh(),
                request.sourceText(),
                request.contextFragments(),
                buildCallbackUrl()
        );
    }

    private String buildCallbackUrl() {
        String baseUrl = aiProperties.getCallbackBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return baseUrl + "/api/ai/callbacks/explanations";
    }
}
