package com.ralek.backend.imaging.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DicomImportServiceTest {

    @Test
    void mapsDicomMrModalityToMriStudyType() {
        assertThat(DicomImportService.studyTypeCode("MR")).isEqualTo("MRI");
    }

    @Test
    void keepsCtStudyTypeCode() {
        assertThat(DicomImportService.studyTypeCode("CT")).isEqualTo("CT");
    }

    @Test
    void mapsUltrasoundToCatalogType() {
        assertThat(DicomImportService.studyTypeCode("US")).isEqualTo("ULTRASOUND");
    }

    @Test
    void rejectsUnsupportedModality() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DicomImportService.studyTypeCode("ECG"));
    }
}
