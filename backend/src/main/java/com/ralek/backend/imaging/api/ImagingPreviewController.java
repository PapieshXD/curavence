package com.ralek.backend.imaging.api;

import com.ralek.backend.imaging.application.ImagingPreviewService;
import com.ralek.backend.imaging.application.ImagingPreviewService.Overview;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imaging-studies/{studyId}")
public class ImagingPreviewController {
    private final ImagingPreviewService previews;

    public ImagingPreviewController(ImagingPreviewService previews) { this.previews = previews; }

    @GetMapping("/overview")
    public ResponseEntity<Overview> overview(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId, @RequestParam(required = false) UUID seriesId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(previews.overview(jwt, role, studyId, seriesId));
    }

    @GetMapping(value = "/preview", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> preview(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID studyId) {
        return png(previews.preview(jwt, role, studyId));
    }

    @GetMapping(value = "/series/{seriesId}/contact-sheet", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> sheet(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId, @PathVariable UUID seriesId,
            @RequestParam(defaultValue = "false") boolean overlay) {
        return png(previews.image(jwt, role, studyId, seriesId, null, overlay));
    }

    @GetMapping(value = "/series/{seriesId}/frames/{index}/{layer:image|overlay}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> frame(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID studyId, @PathVariable UUID seriesId, @PathVariable int index,
            @PathVariable String layer) {
        return png(previews.image(jwt, role, studyId, seriesId, index, "overlay".equals(layer)));
    }

    private ResponseEntity<byte[]> png(byte[] image) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").header("Pragma", "no-cache").body(image);
    }
}
