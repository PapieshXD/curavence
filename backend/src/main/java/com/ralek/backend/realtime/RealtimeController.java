package com.ralek.backend.realtime;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class RealtimeController {
    private final RealtimeService service;
    public RealtimeController(RealtimeService service) {this.service=service;}

    @GetMapping("/sync/changes")
    public ResponseEntity<RealtimeService.Changes> changes(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Workspace-Role",required=false) String role,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.changes(jwt,role,after,limit));
    }
    @GetMapping(value="/events/stream",produces="text/event-stream")
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="X-Workspace-Role",required=false) String role,
            @RequestHeader(value="Last-Event-ID",defaultValue="0") long after) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no")
            .body(service.connect(jwt,role,after));
    }
}
