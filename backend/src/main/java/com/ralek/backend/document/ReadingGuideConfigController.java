package com.ralek.backend.document;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class ReadingGuideConfigController {
    private final ReadingGuideService service;
    public ReadingGuideConfigController(ReadingGuideService service) { this.service = service; }
    @GetMapping("/api/reading-guide/config")
    public Map<String, Boolean> config() { return Map.of("enabled", service.enabled()); }
}
