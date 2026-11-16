package com.ralek.backend.document;

import com.ralek.backend.document.ReadingGuideDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResultAssistantServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final List<Block> blocks = List.of(new Block("b0", "Nie stwierdzono zmian. Podejrzenie torbieli. Kontrola za 6 miesięcy.", List.of()));
    String json(String text, String quote, String deadline) {
        return mapper.writeValueAsString(List.of(Map.of("text", text, "deadlineText", deadline,
            "source", Map.of("blockId", "b0", "quote", quote))));
    }
    @Test void preservesNegationUncertaintyAndLiteralDeadline() {
        String quote = "Kontrola za 6 miesięcy.";
        var result = ResultAssistantService.validate(mapper.readTree(json(quote, quote, "za 6 miesięcy")), blocks, true, "recommendation", 6);
        assertThat(result.getFirst().text()).isEqualTo(quote);
        assertThat(result.getFirst().deadlineText()).isEqualTo("za 6 miesięcy");
        assertThat(ResultAssistantService.validate(mapper.readTree(json("Nie stwierdzono zmian.", "Nie stwierdzono zmian.", "Nie")), blocks, true, "point", 6)).hasSize(1);
    }
    @Test void rejectsDroppedNegationAndUncertaintyAndInventedDeadline() {
        for (String quote : List.of("stwierdzono zmian.", "torbieli."))
            assertThatThrownBy(() -> ResultAssistantService.validate(mapper.readTree(json(quote, quote, quote)), blocks, true, "point", 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResultAssistantService.validate(mapper.readTree(json("Kontrola za 6 miesięcy.", "Kontrola za 6 miesięcy.", "jutro")), blocks, true, "recommendation", 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResultAssistantService.validate(mapper.readTree(json("Rozpoznano torbiel.", "Podejrzenie torbieli.", "torbieli")), blocks, true, "point", 6)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsWrongShapeAndQuotesNotInSource() {
        assertThatThrownBy(() -> ResultAssistantService.validate(mapper.readTree("{}"), blocks, true, "point", 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResultAssistantService.validate(mapper.readTree(json("Pilna operacja.", "Pilna operacja.", "Pilna")), blocks, true, "point", 6)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void truncationIsDisclosedAndMalformedModelOutputIsNotPublished() {
        var guides = mock(ReadingGuideService.class); var client = mock(DocumentQuestionClient.class);
        var jwt = mock(Jwt.class); UUID id = UUID.randomUUID();
        when(guides.guide(jwt, "patient", id)).thenReturn(new Guide(true,id.toString(),"version","dict",
            List.of(new Block("b0", "a".repeat(100000), List.of())), List.of()));
        when(client.answerJson(anyList())).thenReturn(new DocumentQuestionClient.Answer("{\"points\":[],\"recommendations\":[],\"questions\":[]}", "test"));
        var properties = new DocumentChatProperties(); properties.setMaxContextCharacters(1000);
        var service = new ResultAssistantService(guides, client, properties, mapper);
        var summary = service.summarize(jwt,"patient",id);
        assertThat(summary.contextTruncated()).isTrue(); assertThat(summary.analyzedCharacters()).isEqualTo(1000);
        assertThat(summary.totalCharacters()).isEqualTo(100000);
        when(client.answerJson(anyList())).thenReturn(new DocumentQuestionClient.Answer("Not JSON", "test"));
        assertThatThrownBy(() -> service.summarize(jwt,"patient",id)).isInstanceOf(ResponseStatusException.class);
    }
}
