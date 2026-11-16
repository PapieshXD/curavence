package com.ralek.backend.sharing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SharingDtos {
    private SharingDtos() { }

    public record CodeDto(UUID id, String code, Instant expiresAt, Instant serverNow) { }
    public record CreateRequest(@NotBlank @Size(max = 32) String code,
                                @NotBlank String scope,
                                @NotBlank @Size(max = 500) String purpose,
                                @Min(1) @Max(365) int durationDays) { }
    public record DecisionRequest(@NotNull Boolean approve, @Size(max = 2000) List<UUID> studyIds) { }
    public record StudyOption(UUID id, String title, String typeName) { }
    public record RequestDto(UUID id, String status, String requesterName, String requesterEmail,
                             String requesterRole, UUID patientId, String patientName, String scope,
                             String purpose, int durationDays, Instant createdAt, Instant expiresAt,
                             UUID grantId, List<StudyOption> studies, Instant serverNow) { }
}
