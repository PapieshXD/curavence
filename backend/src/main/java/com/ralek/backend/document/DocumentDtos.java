package com.ralek.backend.document;

import java.util.UUID;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record DocumentUploadResponse(
            UUID id,
            String status,
            String processingStatus,
            int pages,
            int extractedCharacters,
            boolean textTruncated, java.util.List<UUID> duplicateIds
    ) {
    }
}
