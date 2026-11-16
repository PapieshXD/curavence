package com.ralek.backend.sharing;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.ralek.backend.sharing.SharingDtos.*;

@RestController
@RequestMapping("/api/sharing")
public class SharingController {
    private final SharingService service;
    public SharingController(SharingService service) { this.service = service; }

    @PostMapping("/codes")
    public ResponseEntity<CodeDto> generate(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.generate(jwt, role));
    }

    @DeleteMapping("/codes/{id}")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role, @PathVariable UUID id) {
        service.cancel(jwt, role, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/requests")
    public ResponseEntity<RequestDto> redeem(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.redeem(jwt, role, request));
    }

    @GetMapping("/requests")
    public ResponseEntity<List<RequestDto>> requests(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.requests(jwt, role));
    }

    @PostMapping("/requests/{id}/decision")
    public ResponseEntity<RequestDto> decide(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Workspace-Role", required = false) String role,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest decision) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.decide(jwt, role, id, decision));
    }
}
