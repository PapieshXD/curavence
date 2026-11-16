package com.ralek.backend.document;

import com.ralek.backend.document.ReadingGuideDtos.*;
import com.ralek.backend.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ReadingGuideService {
    private final WorkspaceService workspace;
    private final ClinicalResultParser parser;
    private final Catalog catalog;
    private final boolean enabled;
    public ReadingGuideService(WorkspaceService workspace, ClinicalResultParser parser, ObjectMapper mapper,
        @Value("${ralek.reading-guide.enabled:true}") boolean enabled) {
        this.workspace = workspace; this.parser = parser; this.enabled = enabled;
        try (var stream = new ClassPathResource("medical-glossary.json").getInputStream()) {
            catalog = mapper.readValue(stream, Catalog.class);
            Set<String> ids = new HashSet<>();
            for (Term term : catalog.terms()) {
                if (!ids.add(term.id()) || term.definition() == null || term.definition().isBlank())
                    throw new IllegalStateException("Nieprawidłowy katalog pojęć");
                if ("APPROVED".equals(term.reviewStatus()) && (term.reviewedAt() == null || term.reviewedAt().isBlank() ||
                    term.reviewedBy() == null || term.reviewedBy().isBlank() || term.sources().isEmpty()))
                    throw new IllegalStateException("Brak przeglądu medycznego: " + term.id());
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Nie można odczytać słownika", e); }
    }
    public boolean enabled() { return enabled; }
    public void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Objaśnienia są wyłączone");
    }
    public Guide guide(Jwt jwt, String role, UUID id) {
        requireEnabled();
        var document = workspace.documentContent(jwt, role, id);
        String category = parser.parse(document.text(), document.title()).map(ReadingGuideService::category)
            .orElse("unknown");
        return build(id, document.text(), category, catalog);
    }
    private static String category(ClinicalResultDtos.ClinicalResultSet result) {
        String name = result.name() == null ? "" : result.name().toLowerCase(Locale.ROOT);
        if (name.contains("elektrokardiogram") || name.contains("holter ekg") || "ecg".equals(result.category())) return "ecg";
        if (name.matches("^(rtg|tk|mr|ct|usg|mammografia|densytometria|rezonans|tomografia).*")) return "imaging";
        return "diagnostics".equals(result.category()) || "report".equals(result.presentation()) ? "unknown" : "laboratory";
    }
    static Guide build(UUID id, String text, String category, Catalog catalog) {
        List<Term> approved = catalog.terms().stream().filter(t -> "APPROVED".equals(t.reviewStatus())).toList();
        List<Block> blocks = new ArrayList<>(); Set<String> found = new LinkedHashSet<>();
        // Preserve every character. Java and JavaScript offsets both use UTF-16.
        var paragraphs = Pattern.compile("[^\\r\\n]+(?:\\r?\\n|\\r)*|[\\r\\n]+").matcher(text);
        int index = 0;
        while (paragraphs.find()) {
            String value = paragraphs.group();
            var occurrences = match(value, category, approved);
            occurrences.forEach(o -> found.add(o.termId()));
            blocks.add(new Block("b" + index++, value, occurrences));
        }
        return new Guide(true, id.toString(), version(text), catalog.version(), blocks,
            approved.stream().filter(t -> found.contains(t.id())).toList());
    }
    static List<Occurrence> match(String text, String category, List<Term> terms) {
        List<Occurrence> candidates = new ArrayList<>();
        for (Term term : terms) {
            Set<String> aliases = new LinkedHashSet<>(term.aliases()); aliases.add(term.name());
            for (String alias : aliases) {
                if (alias.isBlank()) continue;
                if (term.contextualAliases() != null && term.contextualAliases().stream().anyMatch(alias::equalsIgnoreCase)
                    && !term.category().equals(category)) continue;
                var matcher = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(alias) + "(?![\\p{L}\\p{N}_])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
                while (matcher.find()) candidates.add(new Occurrence(matcher.start(), matcher.end(), term.id()));
            }
        }
        candidates.sort(Comparator.<Occurrence>comparingInt(o -> o.end() - o.start()).reversed()
            .thenComparingInt(Occurrence::start).thenComparing(Occurrence::termId));
        List<Occurrence> accepted = new ArrayList<>();
        for (var candidate : candidates) {
            if (accepted.stream().noneMatch(o -> candidate.start() < o.end() && candidate.end() > o.start()))
                accepted.add(candidate);
        }
        accepted.sort(Comparator.comparingInt(Occurrence::start));
        Set<String> seen = new HashSet<>();
        return accepted.stream().filter(o -> seen.add(o.termId())).toList();
    }
    static String version(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
