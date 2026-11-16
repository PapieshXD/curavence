package com.ralek.backend.imaging.application;

import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudyDetails;
import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudySummary;
import com.ralek.backend.imaging.infrastructure.ImagingPreviewWorkerClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ImagingPreviewServiceTest {
    private final ImagingStudyService studies = mock(ImagingStudyService.class);
    private final ImagingPreviewWorkerClient worker = mock(ImagingPreviewWorkerClient.class);
    private final ImagingPreviewService service = new ImagingPreviewService(studies, worker, mock(JdbcClient.class));
    private final Jwt jwt = mock(Jwt.class);

    @Test
    void everyPreviewAndFrameRechecksAccessBeforeReadingWorkerCache() {
        UUID id = UUID.randomUUID();
        when(studies.details(jwt, "DOCTOR", id)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> service.preview(jwt, "DOCTOR", id));
        assertThrows(ResponseStatusException.class, () -> service.overview(jwt, "DOCTOR", id, null));
        assertThrows(ResponseStatusException.class, () -> service.image(jwt, "DOCTOR", id, UUID.randomUUID(), 0, false));
        assertThrows(ResponseStatusException.class, () -> service.image(jwt, "DOCTOR", id, UUID.randomUUID(), null, true));
        verify(studies, times(4)).details(jwt, "DOCTOR", id);
        verifyNoInteractions(worker);
    }

    @Test
    void aSeriesFromAnotherStudyCannotBeRequested() {
        UUID id = UUID.randomUUID();
        ImagingStudySummary study = new ImagingStudySummary(id, UUID.randomUUID(), "Patient", "1.2.3", "MR", "MRI", "BRAIN", null, 0, 0, "ORTHANC", "DICOM", "AVAILABLE", null);
        when(studies.details(jwt, "PATIENT", id)).thenReturn(new ImagingStudyDetails(study, List.of()));
        assertThrows(ResponseStatusException.class, () -> service.image(jwt, "PATIENT", id, UUID.randomUUID(), 0, false));
        verifyNoInteractions(worker);
    }
}
