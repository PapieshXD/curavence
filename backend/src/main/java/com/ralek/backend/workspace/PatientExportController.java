package com.ralek.backend.workspace;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.UUID;

@RestController
@RequestMapping("/api/exports")
public class PatientExportController {
    private final PatientExportService service;
    public PatientExportController(PatientExportService service){this.service=service;}
    @PostMapping public PatientExportService.Job create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,@RequestBody PatientExportService.Request input) {
        return service.create(jwt,role,input);
    }
    @GetMapping("/{id}") public PatientExportService.Job status(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,@PathVariable UUID id) {
        return service.status(jwt,role,id);
    }
    @GetMapping("/{id}/download") public ResponseEntity<StreamingResponseBody> download(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,@PathVariable UUID id) {
        var result=service.download(jwt,role,id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(result.contentType()))
            .contentLength(result.size()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(result.filename()).build().toString())
            .body(output->{try(var stream=result.stream()){stream.transferTo(output);}});
    }
}
