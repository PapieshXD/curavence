package com.ralek.backend.imaging.analysis;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.imaging.application.ImagingStudyService;
import com.ralek.backend.storage.ObjectStorageService;
import com.ralek.backend.workspace.UserContextService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ImagingAnalysisPolicyTest {
    private final UserContextService contexts = mock(UserContextService.class);
    private final ImagingStudyService studies = mock(ImagingStudyService.class);
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final ImagingAnalysisProperties properties = new ImagingAnalysisProperties();
    private final Jwt jwt = mock(Jwt.class);

    private ImagingAnalysisService service() {
        properties.setEnabled(true);
        return new ImagingAnalysisService(jdbc, contexts, studies, mock(com.ralek.backend.imaging.application.ImagingAccessService.class), mock(ImagingAnalysisWorkerClient.class),
                properties, mock(ObjectStorageService.class), mock(AuditService.class), new ObjectMapper(),
                mock(TaskExecutor.class), mock(PlatformTransactionManager.class));
    }

    private void actor(String role) {
        when(contexts.resolve(jwt, role)).thenReturn(new UserContextService.UserContext(UUID.randomUUID(),
                "test@example.test", "Test", "User", Set.of(), List.of(role), false, role, null));
    }

    @Test
    void patientAndPhysiotherapyCannotStartOrChangeScope() {
        var service = service();
        for (String role : List.of("patient", "therapy")) {
            actor(role);
            assertThatThrownBy(() -> service.start(jwt, role, UUID.randomUUID()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                            org.assertj.core.api.Assertions.assertThat(e.getStatusCode().value()).isEqualTo(403));
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void audienceQueryCannotElevateAnAuthenticatedPatient() {
        var service = service();
        actor("patient");
        service.validateAudience(jwt, "patient", "PATIENT");
        assertThatThrownBy(() -> service.validateAudience(jwt, "patient", "CLINICIAN"))
                .isInstanceOf(ResponseStatusException.class);
        actor("therapy");
        service.validateAudience(jwt, "therapy", "PHYSIOTHERAPY");
        assertThatThrownBy(() -> service.validateAudience(jwt, "therapy", "CLINICIAN"))
                .isInstanceOf(ResponseStatusException.class);
        actor("doctor");
        service.validateAudience(jwt, "doctor", "CLINICIAN");
    }
}
