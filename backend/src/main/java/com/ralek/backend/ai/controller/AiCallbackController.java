package com.ralek.backend.ai.controller;

import com.ralek.backend.ai.dto.WorkerExplanationCallbackRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/callbacks")
public class AiCallbackController {

    private static final Logger log = LoggerFactory.getLogger(AiCallbackController.class);

    @PostMapping("/explanations")
    public ResponseEntity<Map<String, String>> explanationCallback(
            @Valid @RequestBody WorkerExplanationCallbackRequest request
    ) {
        log.info("Received AI callback for jobId={}, status={}", request.jobId(), request.status());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "status", "RECEIVED",
                        "jobId", request.jobId()
                ));
    }
}
