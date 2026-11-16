package com.ralek.backend.document;

import com.ralek.backend.document.ClinicalResultDtos.ClinicalResultHistoryPoint;
import com.ralek.backend.document.ClinicalResultDtos.ClinicalResultParameter;
import com.ralek.backend.document.ClinicalResultDtos.ClinicalResultReferenceRange;
import com.ralek.backend.document.ClinicalResultDtos.ClinicalResultSection;
import com.ralek.backend.document.ClinicalResultDtos.ClinicalResultSet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ClinicalResultParser {

    private static final Pattern COLLECTED_AT = Pattern.compile(
            "Pobranie\\s+(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_FLAG = Pattern.compile("\\s+([HL])$");
    private static final Pattern NUMERIC_VALUE = Pattern.compile("^[+-]?\\d[\\d ]*(?:[.,]\\d+)?$");
    private static final String NUMBER = "[+-]?\\d[\\d ]*(?:[.,]\\d+)?";
    private static final Pattern TWO_SIDED_RANGE = Pattern.compile(
            "^\\s*(" + NUMBER + ")\\s*(?:-|–|—|do)\\s*(" + NUMBER + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPPER_BOUND = Pattern.compile("^\\s*[<≤]\\s*(" + NUMBER + ")");
    private static final Pattern LOWER_BOUND = Pattern.compile("^\\s*[>≥]\\s*(" + NUMBER + ")");
    private static final List<String> STOP_LINES = List.of(
            "Komentarz / interpretacja techniczna", "Metoda:", "DOKUMENT TESTOWY",
            "Autoryzował:", "Data autoryzacji:", "Nie do użytku klinicznego");

    private final List<CatalogExam> exams;

    public ClinicalResultParser(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource("clinical-result-catalog.json").getInputStream()) {
            CatalogRoot root = objectMapper.readValue(input, CatalogRoot.class);
            this.exams = root.exams().stream()
                    .sorted(Comparator.comparingInt((CatalogExam exam) -> exam.title().length()).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Nie udało się wczytać katalogu wyników badań", exception);
        }
    }

    public Optional<ClinicalResultSet> parse(String text, String titleHint) {
        if (text == null || text.isBlank()) return Optional.empty();
        CatalogExam exam = match(text, titleHint).orElse(null);
        if (exam == null) return Optional.empty();

        List<String> lines = text.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
        String observedAt = observedAt(text);
        String comment = blockAfter(lines, "Komentarz / interpretacja techniczna", List.of("Metoda:"));
        String material = material(text).orElse(exam.material());
        List<ClinicalResultParameter> parameters = "parameters".equals(exam.presentation())
                ? parseParameters(linesAfter(lines, "Wyniki"), exam, observedAt)
                : List.of();
        List<ClinicalResultSection> sections = "report".equals(exam.presentation())
                ? parseSections(linesAfter(lines, "Opis badania"), exam)
                : List.of();

        return Optional.of(new ClinicalResultSet(
                exam.title(), exam.category(), exam.categoryLabel(), exam.presentation(),
                material, emptyToNull(comment), parameters, sections));
    }

    int supportedExamCount() {
        return exams.size();
    }

    private Optional<CatalogExam> match(String text, String titleHint) {
        String searchableText = searchKey(text);
        String searchableTitle = searchKey(titleHint == null ? "" : titleHint);
        Optional<CatalogExam> textMatch = exams.stream()
                .filter(exam -> searchableText.contains(searchKey(exam.title())))
                .findFirst();
        if (textMatch.isPresent() || searchableTitle.length() < 5) return textMatch;
        return exams.stream()
                .filter(exam -> {
                    String catalogTitle = searchKey(exam.title());
                    return catalogTitle.contains(searchableTitle) || searchableTitle.contains(catalogTitle);
                })
                .findFirst();
    }

    private List<ClinicalResultParameter> parseParameters(
            List<String> lines, CatalogExam exam, String observedAt) {
        List<String> names = exam.parameters().stream().map(CatalogParameter::name).toList();
        List<ClinicalResultParameter> result = new ArrayList<>();
        for (int index = 0; index < exam.parameters().size(); index++) {
            CatalogParameter definition = exam.parameters().get(index);
            String block = valueBlock(lines, definition.name(), names);
            if (block.isBlank()) continue;
            String sourceExcerpt = definition.name() + " " + block;
            boolean rangeInDocument = definition.reference() != null && !definition.reference().isBlank()
                    && block.contains(definition.reference());

            String flag = null;
            Matcher flagMatcher = TRAILING_FLAG.matcher(block);
            if (flagMatcher.find()) {
                flag = flagMatcher.group(1);
                block = block.substring(0, flagMatcher.start()).trim();
            }
            block = removeSuffix(block, definition.reference());
            block = removeSuffix(block, definition.unit());
            Object value = typedValue(block);
            Double numericValue = value instanceof Double number ? number : null;
            ClinicalResultReferenceRange range = referenceRange(definition.reference());
            String interpretation = interpretation(flag, value, numericValue, range);
            result.add(new ClinicalResultParameter(
                    slug(definition.code().isBlank() ? definition.name() : definition.code()) + "-" + index,
                    emptyToNull(definition.code()), definition.name(), value, numericValue,
                    emptyToNull(definition.unit()), interpretation, range, observedAt,
                    List.<ClinicalResultHistoryPoint>of(), sourceExcerpt, "document",
                    rangeInDocument ? "document" : "catalog"));
        }
        return List.copyOf(result);
    }

    private List<ClinicalResultSection> parseSections(List<String> lines, CatalogExam exam) {
        List<ClinicalResultSection> result = new ArrayList<>();
        for (int index = 0; index < exam.sections().size(); index++) {
            String title = exam.sections().get(index);
            String content = valueBlock(lines, title, exam.sections(), "\n");
            if (!content.isBlank()) {
                result.add(new ClinicalResultSection(slug(title) + "-" + index, title, content));
            }
        }
        return List.copyOf(result);
    }

    private static String valueBlock(List<String> lines, String heading, List<String> peerHeadings) {
        return valueBlock(lines, heading, peerHeadings, " ");
    }

    private static String valueBlock(List<String> lines, String heading, List<String> peerHeadings, String separator) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!startsWithHeading(line, heading)) continue;
            StringBuilder value = new StringBuilder(line.substring(Math.min(heading.length(), line.length())).trim());
            for (int next = index + 1; next < lines.size(); next++) {
                String following = lines.get(next);
                if (isStop(following) || peerHeadings.stream().anyMatch(peer -> startsWithHeading(following, peer))) {
                    break;
                }
                if (!value.isEmpty()) value.append(separator);
                value.append(following);
            }
            return value.toString().trim();
        }
        return "";
    }

    private static List<String> linesAfter(List<String> lines, String marker) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).equalsIgnoreCase(marker)) return lines.subList(index + 1, lines.size());
        }
        return lines;
    }

    private static String blockAfter(List<String> lines, String marker, List<String> stops) {
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).equalsIgnoreCase(marker)) continue;
            StringBuilder value = new StringBuilder();
            for (int next = index + 1; next < lines.size(); next++) {
                String line = lines.get(next);
                if (stops.stream().anyMatch(line::startsWith)) break;
                if (!value.isEmpty()) value.append(' ');
                value.append(line);
            }
            return value.toString().trim();
        }
        return "";
    }

    private static Optional<String> material(String text) {
        Matcher matcher = Pattern.compile("Materiał\\s+(.+?)\\s+Pobranie", Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }

    private static String observedAt(String text) {
        Matcher matcher = COLLECTED_AT.matcher(text);
        if (!matcher.find()) return null;
        try {
            LocalDateTime value = LocalDateTime.parse(matcher.group(1), DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
            return value.toString();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static Object typedValue(String value) {
        String normalized = value.trim();
        if (!NUMERIC_VALUE.matcher(normalized).matches()) return normalized;
        try {
            return Double.parseDouble(normalized.replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException exception) {
            return normalized;
        }
    }

    private static ClinicalResultReferenceRange referenceRange(String label) {
        if (label == null || label.isBlank()) return null;
        Matcher twoSided = TWO_SIDED_RANGE.matcher(label);
        if (twoSided.find()) {
            return new ClinicalResultReferenceRange(number(twoSided.group(1)), number(twoSided.group(2)), label);
        }
        Matcher upper = UPPER_BOUND.matcher(label);
        if (upper.find()) return new ClinicalResultReferenceRange(null, number(upper.group(1)), label);
        Matcher lower = LOWER_BOUND.matcher(label);
        if (lower.find()) return new ClinicalResultReferenceRange(number(lower.group(1)), null, label);
        return new ClinicalResultReferenceRange(null, null, label);
    }

    private static String interpretation(
            String flag, Object value, Double numericValue, ClinicalResultReferenceRange range) {
        if ("H".equals(flag)) return numericValue == null ? "ABNORMAL" : "HIGH";
        if ("L".equals(flag)) return numericValue == null ? "ABNORMAL" : "LOW";
        if (numericValue != null && range != null) {
            if (range.low() != null && numericValue < range.low()) return "LOW";
            if (range.high() != null && numericValue > range.high()) return "HIGH";
            if (range.low() != null || range.high() != null) return "NORMAL";
        }
        if (range != null && range.label() != null && value instanceof String text) {
            String expected = searchKey(range.label());
            String actual = searchKey(text);
            if (expected.equals(actual)) return "NORMAL";
            if (List.of("ujemny", "nie wykryto", "nieobecne").contains(expected)) return "ABNORMAL";
        }
        return "UNKNOWN";
    }

    private static String removeSuffix(String value, String suffix) {
        if (suffix == null || suffix.isBlank()) return value.trim();
        String trimmed = value.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))) {
            return trimmed.substring(0, trimmed.length() - suffix.length()).trim();
        }
        return trimmed;
    }

    private static boolean startsWithHeading(String line, String heading) {
        if (!line.regionMatches(true, 0, heading, 0, heading.length())) return false;
        return line.length() == heading.length() || Character.isWhitespace(line.charAt(heading.length()));
    }

    private static boolean isStop(String line) {
        return STOP_LINES.stream().anyMatch(line::startsWith);
    }

    private static Double number(String value) {
        try {
            return Double.parseDouble(value.replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String slug(String value) {
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return ascii.isBlank() ? "result" : ascii;
    }

    private static String searchKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record CatalogRoot(int version, int sourcePages, List<CatalogExam> exams) {
    }

    private record CatalogExam(
            int page,
            String title,
            String category,
            String categoryLabel,
            String presentation,
            String material,
            List<CatalogParameter> parameters,
            List<String> sections
    ) {
    }

    private record CatalogParameter(String name, String code, String unit, String reference) {
    }
}
