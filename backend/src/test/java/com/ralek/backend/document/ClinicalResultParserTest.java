package com.ralek.backend.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ClinicalResultParserTest {

    private ClinicalResultParser parser;

    @BeforeEach
    void setUp() {
        parser = new ClinicalResultParser(new ObjectMapper());
    }

    @Test
    void loadsAllExamsFromTheProvidedCorpus() {
        assertThat(parser.supportedExamCount()).isEqualTo(120);
    }

    @Test
    void parsesNumericParametersAndReferenceRanges() {
        var result = parser.parse("""
                Morfologia krwi obwodowej z płytkami
                Kategoria: Hematologia
                Nr zlecenia ZL-1 Materiał Krew pełna EDTA
                Pobranie 01.07.2026 07:00 Przyjęcie 01.07.2026 08:00
                Wyniki
                Parametr Wynik Jednostka Zakres / wartość oczekiwana Flaga
                WBC 12,4 10^9/L 4,0-10,0 H
                RBC 4,62 10^12/L 4,20-5,70
                HGB 13,1 g/dL 13,5-17,5 L
                Komentarz / interpretacja techniczna
                Leukocytoza.
                Metoda: testowa
                """, "Morfologia").orElseThrow();

        assertThat(result.category()).isEqualTo("blood");
        assertThat(result.parameters()).hasSize(3);
        assertThat(result.parameters().getFirst().numericValue()).isEqualTo(12.4);
        assertThat(result.parameters().getFirst().interpretation()).isEqualTo("HIGH");
        assertThat(result.parameters().getFirst().referenceRange().low()).isEqualTo(4.0);
        assertThat(result.parameters().getFirst().referenceRange().high()).isEqualTo(10.0);
        assertThat(result.parameters().getFirst().referenceSource()).isEqualTo("document");
        assertThat(result.parameters().getFirst().sourceExcerpt()).contains("WBC 12,4");
    }

    @Test
    void parsesQualitativeImmunologyResults() {
        var result = parser.parse("""
                ANA metodą IIF
                Kategoria: Immunologia i serologia
                Pobranie 15.07.2026 08:47 Przyjęcie 15.07.2026 13:31
                Wyniki
                Parametr Wynik Jednostka Zakres / wartość oczekiwana Flaga
                ANA dodatni ujemny H
                Miano 1:320 < 1:160 H
                Typ świecenia ziarnisty drobny
                (AC-4)
                Komentarz / interpretacja techniczna
                Wynik syntetyczny.
                Metoda: testowa
                """, "ANA").orElseThrow();

        assertThat(result.category()).isEqualTo("immunology");
        assertThat(result.parameters().getFirst().value()).isEqualTo("dodatni");
        assertThat(result.parameters().getFirst().interpretation()).isEqualTo("ABNORMAL");
        assertThat(result.parameters().get(2).value()).isEqualTo("ziarnisty drobny (AC-4)");
    }

    @Test
    void doesNotPresentCatalogRangeAsConfirmedDocumentRange() {
        var result = parser.parse("""
                Morfologia krwi obwodowej z płytkami
                Wyniki
                WBC 12,4 10^9/L
                Komentarz / interpretacja techniczna
                Brak zakresu w dokumencie.
                """, "Morfologia").orElseThrow();

        assertThat(result.parameters()).hasSize(1);
        assertThat(result.parameters().getFirst().referenceSource()).isEqualTo("catalog");
    }

    @Test
    void parsesNarrativeImagingReportsIntoSections() {
        var result = parser.parse("""
                USG jamy brzusznej
                Kategoria: Badania czynnościowe, obrazowe i inne
                Pobranie 21.07.2026 08:47 Przyjęcie 21.07.2026 13:31
                Opis badania
                Wątroba Powiększona do 165 mm, bez zmian ogniskowych.
                Pęcherzyk żółciowy Złóg 8 mm.
                Trzustka Bez uchwytnych zmian ogniskowych.
                Nerki Prawidłowej wielkości.
                Wnioski Stłuszczenie wątroby.
                Metoda: testowa
                """, "USG jamy brzusznej").orElseThrow();

        assertThat(result.presentation()).isEqualTo("report");
        assertThat(result.sections()).hasSize(5);
        assertThat(result.sections().getLast().title()).isEqualTo("Wnioski");
        assertThat(result.sections().getLast().content()).isEqualTo("Stłuszczenie wątroby.");
    }

    @Test
    void ignoresOrdinaryMedicalDocuments() {
        assertThat(parser.parse("Wypis ze szpitala po konsultacji.", "Wypis")).isEmpty();
    }
}
