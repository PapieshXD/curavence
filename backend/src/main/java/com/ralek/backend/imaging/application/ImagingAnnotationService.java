package com.ralek.backend.imaging.application;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.imaging.api.ImagingDtos.ImagingAnnotationDto;
import com.ralek.backend.imaging.api.ImagingDtos.SaveImagingAnnotationRequest;
import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudySummary;
import com.ralek.backend.workspace.UserContextService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ImagingAnnotationService {

    private final JdbcClient jdbc;
    private final UserContextService contexts;
    private final ImagingStudyService studies;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public ImagingAnnotationService(JdbcClient jdbc, UserContextService contexts,
                                    ImagingStudyService studies, AuditService audit,
                                    ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.studies = studies;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<ImagingAnnotationDto> list(Jwt jwt, String requestedView, UUID studyId) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = studies.requireAccessible(actor.id(), studyId);
        List<ImagingAnnotationDto> result = jdbc.sql(baseSql() + """
                        WHERE annotation.study_id = :study AND annotation.deleted_at IS NULL
                        ORDER BY annotation.updated_at, annotation.id
                        """)
                .param("study", studyId).query(this::annotation).list();
        audit.record(actor.id(), study.patientId(), "IMAGING_ANNOTATION", studyId, "VIEW", true,
                null, Map.of("count", result.size()));
        return result;
    }

    public ImagingAnnotationDto create(Jwt jwt, String requestedView, UUID studyId,
                                       SaveImagingAnnotationRequest request) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = studies.requireAccessible(actor.id(), studyId);
        validate(studyId, request);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO imaging_annotations (
                          id, study_id, series_id, sop_instance_uid, frame_of_reference_uid,
                          author_id, annotation_type, geometry, measurement
                        ) VALUES (
                          :id, :study, :series, :sop, :frame, :author, :type,
                          CAST(:geometry AS jsonb), CAST(:measurement AS jsonb)
                        )
                        """)
                .param("id", id).param("study", studyId).param("series", request.seriesId())
                .param("sop", request.sopInstanceUid()).param("frame", request.frameOfReferenceUid())
                .param("author", actor.id()).param("type", request.type().trim())
                .param("geometry", json(request.geometry())).param("measurement", jsonOrEmpty(request.measurement()))
                .update();
        audit.record(actor.id(), study.patientId(), "IMAGING_ANNOTATION", id, "CREATE", true,
                null, Map.of("studyId", studyId));
        return require(id);
    }

    public ImagingAnnotationDto update(Jwt jwt, String requestedView, UUID studyId, UUID annotationId,
                                       SaveImagingAnnotationRequest request) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = studies.requireAccessible(actor.id(), studyId);
        validate(studyId, request);
        if (request.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wersja adnotacji jest wymagana");
        }
        int changed = jdbc.sql("""
                        UPDATE imaging_annotations
                        SET series_id = :series, sop_instance_uid = :sop, frame_of_reference_uid = :frame,
                            annotation_type = :type, geometry = CAST(:geometry AS jsonb),
                            measurement = CAST(:measurement AS jsonb), version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND study_id = :study AND author_id = :author
                          AND version = :version AND deleted_at IS NULL
                        """)
                .param("series", request.seriesId()).param("sop", request.sopInstanceUid())
                .param("frame", request.frameOfReferenceUid()).param("type", request.type().trim())
                .param("geometry", json(request.geometry())).param("measurement", jsonOrEmpty(request.measurement()))
                .param("id", annotationId).param("study", studyId).param("author", actor.id())
                .param("version", request.version()).update();
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Adnotacja została zmieniona albo nie należy do bieżącego użytkownika");
        audit.record(actor.id(), study.patientId(), "IMAGING_ANNOTATION", annotationId, "UPDATE", true,
                null, Map.of("studyId", studyId));
        return require(annotationId);
    }

    public void delete(Jwt jwt, String requestedView, UUID studyId, UUID annotationId) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = studies.requireAccessible(actor.id(), studyId);
        int changed = jdbc.sql("""
                        UPDATE imaging_annotations SET deleted_at = CURRENT_TIMESTAMP,
                          updated_at = CURRENT_TIMESTAMP, version = version + 1
                        WHERE id = :id AND study_id = :study AND author_id = :author AND deleted_at IS NULL
                        """)
                .param("id", annotationId).param("study", studyId).param("author", actor.id()).update();
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono adnotacji");
        audit.record(actor.id(), study.patientId(), "IMAGING_ANNOTATION", annotationId, "DELETE", true,
                null, Map.of("studyId", studyId));
    }

    private void validate(UUID studyId, SaveImagingAnnotationRequest request) {
        if (request == null || request.type() == null || request.type().isBlank() || request.geometry() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Typ i geometria adnotacji są wymagane");
        }
        if (request.seriesId() != null) {
            int count = jdbc.sql("SELECT count(*) FROM imaging_series WHERE id = :series AND study_id = :study")
                    .param("series", request.seriesId()).param("study", studyId).query(Integer.class).single();
            if (count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Seria adnotacji nie należy do wskazanego badania");
        }
    }

    private ImagingAnnotationDto require(UUID id) {
        return jdbc.sql(baseSql() + " WHERE annotation.id = :id")
                .param("id", id).query(this::annotation).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono adnotacji"));
    }

    private String baseSql() {
        return """
                SELECT annotation.id, annotation.study_id, annotation.series_id,
                       annotation.sop_instance_uid, annotation.frame_of_reference_uid,
                       annotation.author_id, annotation.annotation_type, annotation.geometry,
                       annotation.measurement, annotation.version, annotation.created_at,
                       annotation.updated_at
                FROM imaging_annotations annotation
                """;
    }

    private ImagingAnnotationDto annotation(ResultSet rs, int rowNum) throws SQLException {
        return new ImagingAnnotationDto(rs.getObject("id", UUID.class), rs.getObject("study_id", UUID.class),
                rs.getObject("series_id", UUID.class), rs.getString("sop_instance_uid"),
                rs.getString("frame_of_reference_uid"), rs.getObject("author_id", UUID.class),
                rs.getString("annotation_type"), tree(rs.getString("geometry")), tree(rs.getString("measurement")),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private String json(JsonNode value) { return objectMapper.writeValueAsString(value); }
    private String jsonOrEmpty(JsonNode value) { return value == null ? "{}" : json(value); }
    private JsonNode tree(String value) { return objectMapper.readTree(value == null ? "{}" : value); }
}
