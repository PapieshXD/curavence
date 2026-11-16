package com.ralek.backend.imaging.api;

import com.ralek.backend.imaging.api.ImagingDtos.ImagingAnnotationDto;
import com.ralek.backend.imaging.api.ImagingDtos.SaveImagingAnnotationRequest;
import com.ralek.backend.imaging.application.ImagingAnnotationService;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imaging-studies/{studyId}/annotations")
public class ImagingAnnotationController {

    private final ImagingAnnotationService annotations;

    public ImagingAnnotationController(ImagingAnnotationService annotations) {
        this.annotations = annotations;
    }

    @GetMapping
    public List<ImagingAnnotationDto> list(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId) {
        return annotations.list(jwt, role, studyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ImagingAnnotationDto create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId, @RequestBody SaveImagingAnnotationRequest request) {
        return annotations.create(jwt, role, studyId, request);
    }

    @PutMapping("/{annotationId}")
    public ImagingAnnotationDto update(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId, @PathVariable UUID annotationId,
            @RequestBody SaveImagingAnnotationRequest request) {
        return annotations.update(jwt, role, studyId, annotationId, request);
    }

    @DeleteMapping("/{annotationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId, @PathVariable UUID annotationId) {
        annotations.delete(jwt, role, studyId, annotationId);
    }
}
