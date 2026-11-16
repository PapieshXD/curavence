package com.ralek.backend.imaging.analysis;

import com.ralek.backend.imaging.analysis.ImagingAnalysisDtos.AnalysisCreated;
import com.ralek.backend.imaging.analysis.ImagingAnalysisDtos.AnalysisDto;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import com.ralek.backend.imaging.analysis.ImagingAnalysisDtos.ScopeRequest;

@RestController
@RequestMapping({"/api/v1/imaging-studies/{studyId}/analyses", "/api/v1/imaging-studies/{studyId}/analysis"})
public class ImagingAnalysisController {

    private final ImagingAnalysisService analyses;

    public ImagingAnalysisController(ImagingAnalysisService analyses) {
        this.analyses = analyses;
    }

    @PostMapping
    public ResponseEntity<AnalysisCreated> start(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId,
            @RequestBody(required = false) ScopeRequest scope
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(analyses.start(jwt, role, studyId, scope));
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID studyId) {
        return analyses.capabilities(jwt, role, studyId);
    }

    @GetMapping("/latest")
    public ResponseEntity<AnalysisDto> latest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId,
            @RequestParam(required = false) String audience
    ) {
        analyses.validateAudience(jwt, role, audience);
        return analyses.latest(jwt, role, studyId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{analysisId}")
    public AnalysisDto get(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId,
            @PathVariable UUID analysisId
    ) {
        return analyses.get(jwt, role, studyId, analysisId);
    }

    @GetMapping("/{analysisId}/artifacts/{artifactId}/content")
    public ResponseEntity<InputStreamResource> artifact(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId,
            @PathVariable UUID analysisId,
            @PathVariable UUID artifactId
    ) {
        ImagingAnalysisService.DownloadedArtifact artifact =
                analyses.downloadArtifact(jwt, role, studyId, analysisId, artifactId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(artifact.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.mimeType()))
                .contentLength(artifact.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(artifact.stream()));
    }
}
