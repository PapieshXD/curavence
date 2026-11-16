package com.ralek.backend.document;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents/{documentId}/ecg")
public class EcgController {
    private final EcgStudyService service;
    public EcgController(EcgStudyService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<JsonNode> preview(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Workspace-Role", required=false) String role,
            @PathVariable UUID documentId, @RequestParam(required=false) Double samplingRate,
            @RequestParam(required=false) String unit) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(service.preview(jwt, role, documentId, samplingRate, unit));
    }

    @PostMapping("/calibration-assessment")
    public DocumentQuestionDtos.DocumentQuestionResponse assessCalibration(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Workspace-Role", required=false) String role, @PathVariable UUID documentId) {
        return service.assessCalibration(jwt, role, documentId);
    }

    @GetMapping("/morphology")
    public ResponseEntity<JsonNode> morphology(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Workspace-Role", required=false) String role,
            @PathVariable UUID documentId, @RequestParam(required=false) Double samplingRate,
            @RequestParam(required=false) String unit, @RequestParam(defaultValue="0") int channelIndex,
            @RequestParam(defaultValue="0") int startSample, @RequestParam(defaultValue="0") int sampleCount) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(service.morphology(jwt, role, documentId, samplingRate, unit, channelIndex, startSample, sampleCount));
    }

    @PostMapping("/questions")
    public DocumentQuestionDtos.DocumentQuestionResponse ask(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Workspace-Role", required=false) String role,
            @PathVariable UUID documentId, @RequestParam(required=false) Double samplingRate,
            @RequestParam(required=false) String unit,
            @Valid @RequestBody DocumentQuestionDtos.DocumentQuestionRequest request,
            @RequestParam(defaultValue="0") int channelIndex, @RequestParam(defaultValue="0") int startSample,
            @RequestParam(defaultValue="0") int sampleCount, @RequestParam(defaultValue="patient") String audience) {
        return service.askAdvanced(jwt, role, documentId, samplingRate, unit, request, channelIndex, startSample, sampleCount, audience);
    }
}
