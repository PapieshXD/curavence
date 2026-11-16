package com.ralek.backend.knowledge;
import java.util.*;
public final class KnowledgeDtos {
    private KnowledgeDtos() {}
    public record Source(String id, String kind, UUID targetId, String title, String text, String version, String limitation) {}
    public record Point(String kind, String sourceId, String quote) {}
    public record Page(String id, String title, List<Point> points) {}
    public record Content(List<Source> sources, List<Page> pages) {}
    public record View(UUID id, String status, String updatedAt, String error, List<Source> sources, List<Page> pages) {}
    public record Question(String question, UUID visitId) {}
    public record Reply(List<Point> points, String message, String model, boolean contextLimited) {}
}
