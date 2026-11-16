package com.ralek.backend.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/visits")
public class VisitController {
    private final VisitService service;
    public VisitController(VisitService service) { this.service = service; }

    public record PackageInput(@NotBlank @Size(max=240) String reason,
        @NotNull @Size(max=10000) String symptoms, @NotNull @Size(max=10000) String questions,
        LocalDate visitDate, @NotNull @Size(max=100) List<@NotNull UUID> studyIds, UUID previousVisitId, boolean includePreviousPlan) {
        public PackageInput(String reason, String symptoms, String questions, LocalDate date, List<UUID> studies) {
            this(reason, symptoms, questions, date, studies, null, false);
        }
    }
    public record ShareInput(@NotBlank @Email String recipientEmail, @NotNull @Future Instant expiresAt,
        @AssertTrue boolean confirmed) {}
    public record ActionInput(@NotBlank @Size(max=1000) String description, @NotNull LocalDate dueDate,
        @NotBlank @Size(max=120) String responsible) {}
    public record PlanInput(@NotBlank @Size(max=10000) String summary,
        @NotNull @Size(max=50) List<@NotNull @Valid ActionInput> actions, boolean approved) {}
    public record CompletionInput(boolean completed) {}
    public record ProgressInput(@NotBlank @Pattern(regexp="TODO|DONE|DIFFICULTY") String status,
        LocalDate performedOn, @NotNull @Size(max=2000) String note,
        @Pattern(regexp="NO_APPOINTMENT|COST|UNCLEAR|OTHER") String obstacle,
        @NotNull @Size(max=100) List<@NotNull UUID> studyIds, @PositiveOrZero long revision) {}
    public record DiscussionInput(boolean discussed, @PositiveOrZero long revision) {}
    public record ExtendInput(@NotNull @Size(max=100) List<@NotNull UUID> studyIds, @AssertTrue boolean confirmed) {}

    @PutMapping("/{id}/actions/{actionId}/progress")
    public void progress(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role,
        @PathVariable UUID id, @PathVariable UUID actionId, @Valid @RequestBody ProgressInput input) {
        service.progress(jwt, role, id, actionId, input);
    }
    @PutMapping("/{id}/actions/{actionId}/discussion")
    public void discuss(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role,
        @PathVariable UUID id, @PathVariable UUID actionId, @Valid @RequestBody DiscussionInput input) {
        service.discuss(jwt, role, id, actionId, input);
    }
    @PutMapping("/{id}/scope")
    public void extend(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role,
        @PathVariable UUID id, @Valid @RequestBody ExtendInput input) { service.extend(jwt, role, id, input); }
    @GetMapping("/notifications")
    public Object notifications(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role) {
        return service.notifications(jwt, role);
    }
    @PutMapping("/notifications/{id}/read")
    public void read(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role, @PathVariable UUID id) {
        service.readNotification(jwt, role, id);
    }

    @GetMapping
    public Object list(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role) {
        return service.list(jwt, role);
    }
    @PostMapping
    public Object create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role,
        @Valid @RequestBody PackageInput input) { return Map.of("id", service.save(jwt, role, null, input)); }
    @PutMapping("/{id}")
    public Object save(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role,
        @PathVariable UUID id, @Valid @RequestBody PackageInput input) {
        return Map.of("id", service.save(jwt, role, id, input));
    }
    @PostMapping("/{id}/share")
    public void share(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role,
        @PathVariable UUID id, @Valid @RequestBody ShareInput input) { service.share(jwt, role, id, input); }
    @PutMapping("/{id}/plan")
    public void plan(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role,
        @PathVariable UUID id, @Valid @RequestBody PlanInput input) { service.plan(jwt, role, id, input); }
    @PutMapping("/{id}/actions/{actionId}")
    public void complete(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Workspace-Role") String role,
        @PathVariable UUID id, @PathVariable UUID actionId, @RequestBody CompletionInput input) {
        service.complete(jwt, role, id, actionId, input.completed());
    }
}
