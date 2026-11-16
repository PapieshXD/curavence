package com.ralek.backend.imaging.api;

import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudyCreated;
import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudyDetails;
import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudyStatus;
import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudySummary;
import com.ralek.backend.imaging.api.ImagingDtos.ViewerSessionResponse;
import com.ralek.backend.imaging.application.ImagingStudyService;
import com.ralek.backend.imaging.application.ViewerSessionService;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1")
public class ImagingStudyController {

    private final ImagingStudyService studies;
    private final ViewerSessionService viewerSessions;

    public ImagingStudyController(ImagingStudyService studies, ViewerSessionService viewerSessions) {
        this.studies = studies;
        this.viewerSessions = viewerSessions;
    }

    @PostMapping(value = "/patients/{patientId}/imaging-studies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImagingStudyCreated> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID patientId,
            @RequestParam("file") MultipartFile[] files,
            @RequestParam String description,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant performedAt
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(studies.create(jwt, role, patientId, files, description, performedAt));
    }

    @GetMapping("/patients/{patientId}/imaging-studies")
    public List<ImagingStudySummary> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID patientId
    ) {
        return studies.list(jwt, role, patientId);
    }

    @GetMapping("/imaging-studies/{studyId}")
    public ImagingStudyDetails details(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId
    ) {
        return studies.details(jwt, role, studyId);
    }

    @GetMapping("/imaging-studies/{studyId}/status")
    public ImagingStudyStatus status(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId
    ) {
        return studies.status(jwt, role, studyId);
    }

    @PostMapping("/imaging-studies/{studyId}/viewer-session")
    public ViewerSessionResponse viewerSession(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId,
            @RequestParam(defaultValue = "false") boolean advanced,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        return viewerSessions.create(jwt, role, studyId, advanced, request, response);
    }

    @PostMapping("/imaging-studies/{studyId}/retry")

    public ImagingStudyCreated retry(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId
    ) {
        return studies.retry(jwt, role, studyId);
    }
}
