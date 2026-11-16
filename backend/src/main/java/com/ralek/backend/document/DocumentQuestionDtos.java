package com.ralek.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class DocumentQuestionDtos {

    private DocumentQuestionDtos() {
    }

    public record ConversationMessage(
            @NotBlank String role,
            @NotBlank @Size(max = 2000) String content
    ) {
    }

    public record DocumentQuestionRequest(
            @NotBlank @Size(max = 1000) String question,
            @Size(max = 6) List<@Valid ConversationMessage> history
    ) {
    }

    public record DocumentQuestionResponse(
            String answer,
            String model,
            boolean contextTruncated
    ) {
    }
}
