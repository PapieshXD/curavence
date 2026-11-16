package com.ralek.backend.document;

import com.ralek.backend.document.ReadingGuideDtos.*;
import com.ralek.backend.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadingGuideServiceTest {
    private Term term(String id, String name, List<String> aliases, List<String> contextual) {
        return new Term(id, name, aliases, "ecg", contextual, "Definicja testowa", List.of(), "APPROVED", "2026-11-01", "Test reviewer");
    }
    @Test void preservesEveryCharacterAndUsesUtf16Offsets() {
        String text = "\n😀 Bez TORBIELI.\r\n\r\nTorbieli nie stwierdzono.\n";
        var guide = ReadingGuideService.build(UUID.randomUUID(), text, "imaging",
            new Catalog("v1", List.of(term("cyst", "Torbiel", List.of("torbieli"), List.of()))));
        assertThat(guide.blocks().stream().map(Block::text).collect(java.util.stream.Collectors.joining())).isEqualTo(text);
        var block = guide.blocks().get(1);
        var occurrence = block.occurrences().getFirst();
        assertThat(block.text().substring(occurrence.start(), occurrence.end())).isEqualTo("TORBIELI");
    }
    @Test void longestPhraseWinsAndOnlyFirstOccurrenceInParagraphIsMarked() {
        var terms = List.of(term("fluid", "płyn", List.of(), List.of()),
            term("pleural", "płyn w jamie opłucnej", List.of(), List.of()));
        var matches = ReadingGuideService.match("płyn w jamie opłucnej; płyn; płyn; przypłynął", "imaging", terms);
        assertThat(matches).extracting(Occurrence::termId).containsExactly("pleural", "fluid");
    }
    @Test void contextualAbbreviationsRequireCategoryAndUnicodeWordBoundaries() {
        var terms = List.of(term("pr", "odstęp PR", List.of("PR"), List.of("PR")));
        assertThat(ReadingGuideService.match("PR prawidłowy, APR, PRóba", "unknown", terms)).isEmpty();
        assertThat(ReadingGuideService.match("PR prawidłowy, APR, PRóba", "ecg", terms)).hasSize(1);
    }
    @Test void draftsAreNeverPublished() {
        var draft = new Term("d", "Torbiel", List.of(), "imaging", List.of(), "Robocze", List.of(), "DRAFT", null, null);
        var guide = ReadingGuideService.build(UUID.randomUUID(), "Torbiel", "imaging", new Catalog("v", List.of(draft)));
        assertThat(guide.terms()).isEmpty(); assertThat(guide.blocks().getFirst().occurrences()).isEmpty();
    }
    @Test void everyRequestRechecksDocumentAccessAndFlagPreventsReads() {
        var workspace = mock(WorkspaceService.class); var jwt = mock(Jwt.class); UUID id = UUID.randomUUID();
        var parser = mock(ClinicalResultParser.class);
        when(parser.parse(anyString(), anyString())).thenReturn(Optional.empty());
        when(workspace.documentContent(jwt, "doctor", id)).thenReturn(new WorkspaceService.DocumentContent(id, UUID.randomUUID(), "Opis", "Treść"));
        var service = new ReadingGuideService(workspace, parser, new ObjectMapper(), true);
        service.guide(jwt, "doctor", id);
        when(workspace.documentContent(jwt, "doctor", id)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.guide(jwt, "doctor", id)).isInstanceOf(ResponseStatusException.class);
        verify(workspace, times(2)).documentContent(jwt, "doctor", id);
        clearInvocations(workspace);
        var disabled = new ReadingGuideService(workspace, parser, new ObjectMapper(), false);
        assertThatThrownBy(() -> disabled.guide(jwt, "doctor", id)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(workspace);
    }
}
