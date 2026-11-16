package com.ralek.backend.ai.controller;

import com.ralek.backend.ai.dto.AiCapabilityResponse;
import com.ralek.backend.ai.dto.ExplainResultRequest;
import com.ralek.backend.ai.dto.ExplainResultResponse;
import com.ralek.backend.ai.exception.AiIntegrationException;
import com.ralek.backend.ai.service.AiOrchestrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiOrchestrationService aiOrchestrationService;

    public AiController(AiOrchestrationService aiOrchestrationService) {
        this.aiOrchestrationService = aiOrchestrationService;
    }

    @GetMapping("/capabilities")
    public AiCapabilityResponse capabilities() {
        return aiOrchestrationService.getCapabilities();
    }

    @PostMapping("/explanations")
    public ResponseEntity<?> submitExplanation(@Valid @RequestBody ExplainResultRequest request) {
        try {
            ExplainResultResponse response = aiOrchestrationService.submitExplanation(request);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (AiIntegrationException exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error", "AI_PROVIDER_UNAVAILABLE",
                            "message", exception.getMessage()
                    ));
        }
    }
}
