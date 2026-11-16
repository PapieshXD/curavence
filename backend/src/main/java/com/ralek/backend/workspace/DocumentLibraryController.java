package com.ralek.backend.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
public class DocumentLibraryController {
    private final DocumentLibraryService library;
    @Value("${ralek.exports.enabled:true}") boolean exportsEnabled=true;
    public DocumentLibraryController(DocumentLibraryService library) {this.library=library;}
    @GetMapping("/api/library/config")
    public Object config() {return Map.of("searchEnabled",library.isEnabled(),"exportsEnabled",exportsEnabled);}
    @GetMapping("/api/documents/search")
    public Object search(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,
        @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="all") String category,
        @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
        @RequestParam(defaultValue="all") String text,@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="25") int pageSize,@RequestParam(defaultValue="false") boolean archived) {
        return library.search(jwt,role,query,category,from,to,text,page,pageSize,archived);
    }
    @GetMapping("/api/documents/{id}/metadata-history")
    public Object history(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,@PathVariable UUID id) {
        return library.history(jwt,role,id);
    }
    @GetMapping("/api/documents/{id}/restore-preview")
    public Object preview(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,@PathVariable UUID id) {
        return library.preview(jwt,role,id);
    }
    public record RestoreInput(@PositiveOrZero long revision,@NotNull List<@NotNull UUID> grantIds,@AssertTrue boolean confirmed) {}
    @PostMapping("/api/documents/{id}/restore")
    public void restore(@AuthenticationPrincipal Jwt jwt,@RequestHeader("X-Workspace-Role") String role,@PathVariable UUID id,@Valid @RequestBody RestoreInput input) {
        library.restore(jwt,role,id,input.revision(),input.grantIds(),input.confirmed());
    }
}
