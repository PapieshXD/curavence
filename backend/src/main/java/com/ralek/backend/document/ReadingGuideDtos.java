package com.ralek.backend.document;

import java.util.List;

public final class ReadingGuideDtos {
    private ReadingGuideDtos() {}
    public record Source(String title, String url) {}
    public record Term(String id, String name, List<String> aliases, String category,
        List<String> contextualAliases, String definition, List<Source> sources,
        String reviewStatus, String reviewedAt, String reviewedBy) {}
    public record Catalog(String version, List<Term> terms) {}
    public record Occurrence(int start, int end, String termId) {}
    public record Block(String id, String text, List<Occurrence> occurrences) {}
    public record Guide(boolean enabled, String documentId, String sourceVersion, String dictionaryVersion,
        List<Block> blocks, List<Term> terms) {}
    public record Evidence(String blockId, String quote) {}
    public record Item(String id, String text, String deadlineText, Evidence source) {}
    public record Summary(String sourceVersion, String generatorVersion, String model,
        boolean contextTruncated, int analyzedCharacters, int totalCharacters,
        List<Item> points, List<Item> recommendations, List<Item> questions) {}
}
