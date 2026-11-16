package com.ralek.backend.workspace;

import com.ralek.backend.workspace.WorkspaceDtos.CreateGrantRequest;
import com.ralek.backend.workspace.WorkspaceDtos.ReviewRequest;
import com.ralek.backend.workspace.WorkspaceDtos.UpdatePatientRequest;
import com.ralek.backend.workspace.WorkspaceDtos.WorkspaceResponse;
import com.ralek.backend.workspace.ReviewRewriteService.RewriteRequest;
import com.ralek.backend.workspace.ReviewRewriteService.RewriteResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class WorkspaceController {

    private final WorkspaceService service;
    private final ReviewRewriteService rewriteService;

    public WorkspaceController(WorkspaceService service, ReviewRewriteService rewriteService) {
        this.service = service;
        this.rewriteService = rewriteService;
    }

    @GetMapping("/workspace")
    public WorkspaceResponse workspace(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role
    ) {
        return service.workspace(jwt, role);
    }

    @PostMapping(value = "/studies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadStudy(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @RequestParam MultipartFile file,
            @RequestParam String title,
            @RequestParam(defaultValue = "MEDICAL_DOCUMENT") String studyTypeCode,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) Instant performedAt
    ) {
        UUID id = service.uploadStudy(jwt, role, file, title, studyTypeCode, patientId, performedAt);
        return ResponseEntity.status(201).body(Map.of("id", id, "status", "READY"));
    }

    @GetMapping("/assets/{assetId}/content")
    public ResponseEntity<InputStreamResource> asset(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID assetId,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        WorkspaceService.DownloadedAsset asset = service.downloadAsset(jwt, role, assetId, download);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(asset.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.mimeType()))
                .contentLength(asset.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(asset.stream()));
    }

    @PostMapping("/access-grants")
    public ResponseEntity<Map<String, UUID>> createGrant(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @Valid @RequestBody CreateGrantRequest request
    ) {
        return ResponseEntity.status(201).body(Map.of("id", service.createGrant(jwt, role, request)));
    }

    @DeleteMapping("/access-grants/{grantId}")
    public ResponseEntity<Void> revokeGrant(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID grantId
    ) {
        service.revokeGrant(jwt, role, grantId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/studies/{studyId}/reviews")
    public ResponseEntity<Map<String, UUID>> review(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId,
            @Valid @RequestBody ReviewRequest request
    ) {
        return ResponseEntity.status(201).body(Map.of("id", service.addReview(jwt, role, studyId, request)));
    }

    @PostMapping("/studies/{studyId}/reviews/rewrite")
    public RewriteResponse rewriteReview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId,
            @Valid @RequestBody RewriteRequest request
    ) {
        return rewriteService.rewrite(jwt, role, studyId, request);
    }

    @PutMapping("/patients/me")
    public ResponseEntity<Void> updatePatient(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @Valid @RequestBody UpdatePatientRequest request
    ) {
        service.updatePatient(jwt, role, request);
        return ResponseEntity.noContent().build();
    }
}
