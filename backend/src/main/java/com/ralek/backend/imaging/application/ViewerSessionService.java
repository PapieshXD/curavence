package com.ralek.backend.imaging.application;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudySummary;
import com.ralek.backend.imaging.api.ImagingDtos.ViewerSessionResponse;
import com.ralek.backend.imaging.infrastructure.ImagingProperties;
import com.ralek.backend.workspace.UserContextService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class ViewerSessionService {

    static final String COOKIE_NAME = "mednode_viewer";
    private final SecureRandom random = new SecureRandom();
    private final ImagingStudyService studies;
    private final ImagingAccessService access;
    private final UserContextService contexts;
    private final ImagingProperties properties;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public ViewerSessionService(ImagingStudyService studies, ImagingAccessService access,
                                UserContextService contexts, ImagingProperties properties,
                                JdbcClient jdbc, AuditService audit) {
        this.studies = studies;
        this.access = access;
        this.contexts = contexts;
        this.properties = properties;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public ViewerSessionResponse create(Jwt jwt, String requestedView, UUID studyId,
                                        boolean advanced, HttpServletRequest request,
                                        HttpServletResponse response) {
        UserContext actor = contexts.resolve(jwt, requestedView);
        ImagingStudySummary study = studies.requireAccessible(actor.id(), studyId);
        if (!"AVAILABLE".equals(study.status()) || study.studyInstanceUid() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Badanie obrazowe nie jest jeszcze dostępne");
        }
        purgeExpired();
        UUID sessionId = UUID.randomUUID();
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        Instant expiresAt = Instant.now().plus(properties.viewerSessionTtl());
        jdbc.sql("""
                        INSERT INTO imaging_viewer_sessions (
                          id, study_id, user_id, secret_hash, expires_at
                        ) VALUES (:id, :study, :user, :secret, :expires)
                        """)
                .param("id", sessionId).param("study", studyId).param("user", actor.id())
                .param("secret", hash(secret)).param("expires", expiresAt.atOffset(ZoneOffset.UTC)).update();

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, secret)
                .httpOnly(true).secure(request.isSecure()).sameSite("Strict")
                .path("/api/v1/viewer/" + sessionId + "/dicomweb")
                .maxAge(properties.viewerSessionTtl()).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        boolean simplified = actor.patientView() && !advanced;
        String dicomWebRoot = properties.publicApiBaseUrl().replaceAll("/+$", "")
                + "/api/v1/viewer/" + sessionId + "/dicomweb";
        String viewerUrl = viewerUrl(properties.ohifBaseUrl(), properties.publicApiBaseUrl(),
                sessionId, study.studyInstanceUid(), simplified);
        audit.record(actor.id(), study.patientId(), "IMAGING_STUDY", studyId, "VIEW", true,
                null, Map.of("event", "VIEWER_SESSION_CREATED"));
        return new ViewerSessionResponse(sessionId, studyId, study.studyInstanceUid(), expiresAt,
                dicomWebRoot, viewerUrl);
    }

    public ViewerSession requireForConfig(UUID sessionId) {
        return requireStored(sessionId, false, null);
    }

    public ViewerSession require(UUID sessionId, HttpServletRequest request) {
        String secret = cookie(request, COOKIE_NAME);
        if (secret == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak bezpiecznej sesji viewera");
        }
        return requireStored(sessionId, true, secret);
    }

    private ViewerSession requireStored(UUID sessionId, boolean verifySecret, String secret) {
        ViewerSession session = jdbc.sql("""
                        SELECT session.id, session.study_id, session.user_id, session.secret_hash,
                               session.expires_at, imaging.study_instance_uid, imaging.storage_provider,
                               imaging.patient_id, session.open_audited
                        FROM imaging_viewer_sessions session
                        JOIN imaging_studies imaging ON imaging.id = session.study_id
                        WHERE session.id = :id AND session.revoked_at IS NULL
                        """)
                .param("id", sessionId).query(this::session).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesja viewera nie istnieje"));
        if (session.expiresAt().isBefore(Instant.now())) {
            revoke(sessionId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesja viewera wygasła");
        }
        if (verifySecret && !constantTimeEquals(session.secretHash(), hash(secret))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nieprawidłowa sesja viewera");
        }
        if (!access.canView(session.userId(), session.studyId())) {
            revoke(sessionId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dostęp do badania został cofnięty");
        }
        jdbc.sql("UPDATE imaging_viewer_sessions SET last_access_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", sessionId).update();
        if (verifySecret && !session.openAudited()) auditOpen(session);
        return session;
    }

    public void validateDicomWebRequest(ViewerSession session, String path, HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod())) {
            throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Viewer może wyłącznie odczytywać DICOM");
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String uid = session.studyInstanceUid();
        if (normalized.startsWith("studies/")) {
            String requestedUid = normalized.substring("studies/".length()).split("/", 2)[0];
            if (uid.equals(requestedUid)) return;
        }
        String queryUid = firstParameter(request, "StudyInstanceUID", "StudyInstanceUIDs",
                "studyInstanceUID", "studyUID", "0020000D");
        if (queryUid == null && normalized.equals("studies")) return;
        if (uid.equals(queryUid) && (normalized.equals("studies") || normalized.equals("series")
                || normalized.equals("instances") || normalized.equals("wado"))) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Sesja viewera nie pozwala odczytać innego badania");
    }

    private void auditOpen(ViewerSession session) {
        int changed = jdbc.sql("""
                        UPDATE imaging_viewer_sessions SET open_audited = TRUE
                        WHERE id = :id AND open_audited = FALSE
                        """).param("id", session.id()).update();
        if (changed > 0) {
            audit.record(session.userId(), session.patientId(), "IMAGING_STUDY", session.studyId(),
                    "DOWNLOAD", true, null, Map.of("event", "DICOMWEB_FIRST_READ"));
        }
    }

    private ViewerSession session(ResultSet rs, int rowNum) throws SQLException {
        return new ViewerSession(rs.getObject("id", UUID.class), rs.getObject("study_id", UUID.class),
                rs.getString("study_instance_uid"), rs.getObject("user_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("storage_provider"),
                rs.getString("secret_hash"), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("open_audited"));
    }

    private String firstParameter(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getParameter(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private void revoke(UUID sessionId) {
        jdbc.sql("UPDATE imaging_viewer_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", sessionId).update();
    }

    private void purgeExpired() {
        jdbc.sql("DELETE FROM imaging_viewer_sessions WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 day'")
                .update();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    static String viewerUrl(String ohifBaseUrl, String publicApiBaseUrl,
                            UUID sessionId, String studyInstanceUid, boolean simplified) {
        String configUrl = publicApiBaseUrl.replaceAll("/+$", "")
                + "/api/v1/viewer/" + sessionId + "/config?simplified=" + simplified;
        String viewerUrl = ohifBaseUrl.replaceAll("/+$", "")
                + "/viewer/dicomweb?configUrl=" + URLEncoder.encode(configUrl, StandardCharsets.UTF_8)
                + "&StudyInstanceUIDs=" + URLEncoder.encode(studyInstanceUid, StandardCharsets.UTF_8)
                + "&mednodeConfig=2";
        return simplified ? viewerUrl + "&hangingProtocolId=%40ohif%2FmnGrid8" : viewerUrl;
    }

    public record ViewerSession(UUID id, UUID studyId, String studyInstanceUid, UUID userId,
                                UUID patientId, String storageProvider, String secretHash,
                                Instant expiresAt, boolean openAudited) { }
}
