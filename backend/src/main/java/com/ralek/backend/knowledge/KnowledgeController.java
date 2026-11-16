package com.ralek.backend.knowledge;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import com.ralek.backend.knowledge.KnowledgeDtos.*;
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    public KnowledgeController(KnowledgeService service){this.service=service;}
    @GetMapping("/config") public Map<String,Boolean> config(){return Map.of("enabled",service.enabled);}
    @GetMapping public View view(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,@RequestParam(required=false) UUID visitId){return service.view(jwt,role,visitId);}
    @PostMapping("/rebuild") @ResponseStatus(HttpStatus.ACCEPTED)
    public View rebuild(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,@RequestParam(required=false) UUID visitId){service.rebuild(jwt,role,visitId);return service.view(jwt,role,visitId);}
    @PostMapping("/questions") public Reply ask(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,@RequestBody Question input){return service.ask(jwt,role,input);}
}
