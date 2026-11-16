package com.ralek.backend.document;

import com.ralek.backend.document.DocumentDtos.DocumentUploadResponse;
import com.ralek.backend.document.ClinicalResultDtos.ClinicalResultSet;
import com.ralek.backend.document.DocumentQuestionDtos.DocumentQuestionRequest;
import com.ralek.backend.document.DocumentQuestionDtos.DocumentQuestionResponse;
import com.ralek.backend.workspace.WorkspaceDtos.UpdateDocumentRequest;
import com.ralek.backend.workspace.WorkspaceService;
import jakarta.validation.Valid;
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

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;
    private final DocumentQuestionService questionService;
    private final WorkspaceService workspaceService;

    public DocumentController(DocumentService service, DocumentQuestionService questionService,
                              WorkspaceService workspaceService) {
        this.service = service;
        this.questionService = questionService;
        this.workspaceService = workspaceService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "patientId", required = false) UUID patientId,
            @RequestParam(value = "performedAt", required = false) Instant performedAt,
            @RequestParam(value = "typeCode", required = false) String typeCode
    ) {
        return ResponseEntity.status(201).body(service.upload(jwt, role, file, title, patientId, performedAt, typeCode));
    }

    @PutMapping("/{documentId}")
    public ResponseEntity<Void> update(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID documentId,
            @Valid @RequestBody UpdateDocumentRequest request
    ) {
        workspaceService.updateDocument(jwt, role, documentId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID documentId
    ) {
        workspaceService.archiveDocument(jwt, role, documentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{documentId}/clinical-results")
    public ResponseEntity<ClinicalResultSet> clinicalResults(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID documentId
    ) {
        return service.clinicalResult(jwt, role, documentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{documentId}/questions")
    public DocumentQuestionResponse ask(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID documentId,
            @Valid @RequestBody DocumentQuestionRequest request
    ) {
        return questionService.ask(jwt, role, documentId, request);
    }
}
