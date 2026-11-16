package com.ralek.backend.workspace;

import tools.jackson.databind.ObjectMapper;
import com.ralek.backend.document.DocumentQuestionClient;
import com.ralek.backend.document.DocumentQuestionClient.Answer;
import com.ralek.backend.document.DocumentQuestionClient.ChatMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewRewriteServiceTest {
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final DocumentQuestionClient client = mock(DocumentQuestionClient.class);
    private final Jwt jwt = mock(Jwt.class);
    private final UUID studyId = UUID.randomUUID();
    private final ReviewRewriteService service = new ReviewRewriteService(workspace, client, new ObjectMapper());

    @Test
    @SuppressWarnings("unchecked")
    void returnsEditableSuggestionWithoutSavingOrPatientIdentifiers() {
        when(client.answerJson(anyList())).thenReturn(new Answer(
                "{\"summary\":\"W badaniu widoczna jest zmiana o średnicy 6 mm.\",\"conclusion\":\"Zaleca się kontrolę.\"}",
                "qwen3.5:4b"));
        var response = service.rewrite(jwt, "doctor", studyId,
                new ReviewRewriteService.RewriteRequest("widac zmiane 6mm", "kontrola"));

        verify(workspace).requireReviewAccess(jwt, "doctor", studyId);
        verify(workspace, never()).addReview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).answerJson(messages.capture());
        assertThat(messages.getValue().getFirst().content()).contains("Zachowaj liczby", "niezaufaną");
        assertThat(messages.getValue().getLast().content()).contains("widac zmiane 6mm").doesNotContain(studyId.toString());
        assertThat(response.summary()).contains("6 mm");
        assertThat(response.conclusion()).isEqualTo("Zaleca się kontrolę.");
    }

    @Test
    void rejectsEmptyDraftBeforeCallingModel() {
        assertThatThrownBy(() -> service.rewrite(jwt, "doctor", studyId,
                new ReviewRewriteService.RewriteRequest(" ", "")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Wpisz tekst");
        verify(client, never()).answerJson(anyList());
    }

    @Test
    void rejectsIncompleteModelAnswer() {
        when(client.answerJson(anyList())).thenReturn(new Answer("{\"summary\":\"Nowy tekst\",\"conclusion\":\"\"}", "model"));
        assertThatThrownBy(() -> service.rewrite(jwt, "doctor", studyId,
                new ReviewRewriteService.RewriteRequest("Opis", "Wniosek")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Szkic pozostał bez zmian");
    }

    @Test
    void rejectsChangedNumericMeasurements() {
        when(client.answerJson(anyList())).thenReturn(new Answer(
                "{\"summary\":\"Widoczna zmiana 8 mm.\",\"conclusion\":\"\"}", "model"));
        assertThatThrownBy(() -> service.rewrite(jwt, "doctor", studyId,
                new ReviewRewriteService.RewriteRequest("Widoczna zmiana 6 mm.", "")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Szkic pozostał bez zmian");
    }
}
