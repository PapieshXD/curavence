package com.ralek.backend.document;
import com.ralek.backend.document.ReadingGuideDtos.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents/{id}")
public class ReadingGuideController {
    private final ReadingGuideService guides;
    private final ResultAssistantService assistant;
    public ReadingGuideController(ReadingGuideService guides, ResultAssistantService assistant) {
        this.guides = guides; this.assistant = assistant;
    }
    @GetMapping("/reading-guide")
    public Guide guide(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value="X-Workspace-Role", required=false) String role,
        @PathVariable UUID id) { return guides.guide(jwt, role, id); }
    @PostMapping("/assistant-summary")
    public Summary summary(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value="X-Workspace-Role", required=false) String role,
        @PathVariable UUID id) { return assistant.summarize(jwt, role, id); }
}
