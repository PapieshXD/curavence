package com.ralek.backend.sharing;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.workspace.UserContextService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static com.ralek.backend.sharing.SharingDtos.*;

@Service
public class SharingService {
    private static final Set<String> SCOPES = Set.of("FULL_STUDY", "LAB_RESULTS", "IMAGING_STUDIES", "SELECTED_STUDIES");
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private final JdbcClient jdbc;
    private final UserContextService contexts;
    private final AuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SharingService(JdbcClient jdbc, UserContextService contexts, AuditService audit) {
        this(jdbc, contexts, audit, Clock.systemUTC());
    }

    SharingService(JdbcClient jdbc, UserContextService contexts, AuditService audit, Clock clock) {
        this.jdbc = jdbc; this.contexts = contexts; this.audit = audit; this.clock = clock;
    }

    @Transactional
    public CodeDto generate(Jwt jwt, String role) {
        UserContext actor = requirePatient(jwt, role);
        lockActor(actor.id());
        Instant now = clock.instant();
        int recent = jdbc.sql("SELECT count(*) FROM sharing_codes WHERE created_by = :actor AND created_at > :since")
                .param("actor", actor.id()).param("since", time(now.minusSeconds(60))).query(Integer.class).single();
        if (recent >= 5) throw error(HttpStatus.TOO_MANY_REQUESTS, "Wygenerowano kilka kodów. Spróbuj ponownie za minutę.");
        jdbc.sql("UPDATE sharing_codes SET status = 'REVOKED' WHERE patient_id = :patient AND status = 'ACTIVE'")
                .param("patient", actor.patientId()).update();
        char[] value = new char[10];
        for (int i = 0; i < value.length; i++) value[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        String code = new String(value);
        UUID id = UUID.randomUUID();
        Instant expiry = now.plusSeconds(60);
        jdbc.sql("""
                INSERT INTO sharing_codes (id, patient_id, created_by, code_hash, created_at, expires_at, status)
                VALUES (:id, :patient, :actor, :hash, :now, :expiry, 'ACTIVE')
                """).param("id", id).param("patient", actor.patientId()).param("actor", actor.id())
                .param("hash", hash(code)).param("now", time(now)).param("expiry", time(expiry)).update();
        audit.record(actor.id(), actor.patientId(), "SHARING_CODE", id, "CREATE", true, null, Map.of("validForSeconds", 60));
        return new CodeDto(id, code, expiry, now);
    }

    @Transactional
    public void cancel(Jwt jwt, String role, UUID id) {
        UserContext actor = requirePatient(jwt, role);
        int changed = jdbc.sql("UPDATE sharing_codes SET status = 'REVOKED' WHERE id = :id AND patient_id = :patient AND status = 'ACTIVE'")
                .param("id", id).param("patient", actor.patientId()).update();
        if (changed > 0) audit.record(actor.id(), actor.patientId(), "SHARING_CODE", id, "REVOKE_ACCESS", true, null, Map.of());
    }

    // Invalid guesses must keep their rate-limit entry and audit record after the response.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public RequestDto redeem(Jwt jwt, String role, CreateRequest request) {
        UserContext actor = requirePractitioner(jwt, role);
        String practitionerRole = practitionerRole(actor);
        validateRequest(request);
        lockActor(actor.id());
        Instant now = clock.instant();
        jdbc.sql("DELETE FROM sharing_code_attempts WHERE actor_id = :actor AND attempted_at < :since")
                .param("actor", actor.id()).param("since", time(now.minusSeconds(300))).update();
        int attempts = jdbc.sql("SELECT count(*) FROM sharing_code_attempts WHERE actor_id = :actor AND attempted_at > :since")
                .param("actor", actor.id()).param("since", time(now.minusSeconds(60))).query(Integer.class).single();
        if (attempts >= 5) throw error(HttpStatus.TOO_MANY_REQUESTS, "Za dużo prób. Odczekaj minutę i poproś o nowy kod.");
        jdbc.sql("INSERT INTO sharing_code_attempts (id, actor_id, attempted_at) VALUES (:id, :actor, :now)")
                .param("id", UUID.randomUUID()).param("actor", actor.id()).param("now", time(now)).update();
        String normalized = request.code().replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        CodeRow code = jdbc.sql("SELECT id, patient_id, created_by, expires_at, status FROM sharing_codes WHERE code_hash = :hash FOR UPDATE")
                .param("hash", hash(normalized)).query((rs, row) -> new CodeRow(rs.getObject("id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getObject("created_by", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("status"))).optional().orElse(null);
        now = clock.instant();
        if (code == null || !code.status().equals("ACTIVE") || !code.expiresAt().isAfter(now) || code.createdBy().equals(actor.id())) {
            audit.record(actor.id(), null, "SHARING_CODE", null, "ACCESS_DENIED", false,
                    "Kod nieważny lub wykorzystany", Map.of());
            throw error(HttpStatus.BAD_REQUEST, "Kod jest nieprawidłowy, wygasł lub został już użyty. Poproś pacjenta o nowy kod.");
        }
        jdbc.sql("UPDATE sharing_codes SET status = 'REDEEMED' WHERE id = :id").param("id", code.id()).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO sharing_requests (id, code_id, patient_id, requester_id, requester_role,
                    scope, purpose, duration_days, status, created_at, expires_at)
                VALUES (:id, :code, :patient, :actor, :role, :scope, :purpose, :days, 'PENDING', :now, :expiry)
                """).param("id", id).param("code", code.id()).param("patient", code.patientId()).param("actor", actor.id())
                .param("role", practitionerRole).param("scope", request.scope()).param("purpose", request.purpose().trim())
                .param("days", request.durationDays()).param("now", time(now)).param("expiry", time(now.plusSeconds(600))).update();
        audit.record(actor.id(), code.patientId(), "SHARING_REQUEST", id, "CREATE", true, null,
                Map.of("scope", request.scope(), "durationDays", request.durationDays()));
        return dto(findRequest(id, false), actor, now);
    }

    @Transactional
    public List<RequestDto> requests(Jwt jwt, String role) {
        UserContext actor = contexts.resolve(jwt, role);
        if (!actor.patientView() && !actor.practitionerView()) return List.of();
        if (actor.patientView() && actor.patientId() == null) return List.of();
        Instant now = clock.instant();
        String owner = actor.patientView() ? "patient_id = :owner" : "requester_id = :owner";
        UUID ownerId = actor.patientView() ? actor.patientId() : actor.id();
        jdbc.sql("UPDATE sharing_requests SET status = 'EXPIRED' WHERE " + owner + " AND status = 'PENDING' AND expires_at <= :now")
                .param("owner", ownerId).param("now", time(now)).update();
        return jdbc.sql("SELECT id FROM sharing_requests WHERE " + owner + " ORDER BY created_at DESC LIMIT 50")
                .param("owner", ownerId).query(UUID.class).list().stream()
                .map(id -> dto(findRequest(id, false), actor, now)).toList();
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public RequestDto decide(Jwt jwt, String role, UUID id, DecisionRequest decision) {
        UserContext actor = requirePatient(jwt, role);
        RequestRow request = findRequest(id, true);
        if (!request.patientId().equals(actor.patientId())) throw error(HttpStatus.NOT_FOUND, "Nie znaleziono prośby.");
        Instant now = clock.instant();
        if (!request.status().equals("PENDING")) throw error(HttpStatus.CONFLICT, "Ta prośba została już rozpatrzona.");
        if (!request.expiresAt().isAfter(now)) {
            jdbc.sql("UPDATE sharing_requests SET status = 'EXPIRED' WHERE id = :id").param("id", id).update();
            throw error(HttpStatus.CONFLICT, "Prośba wygasła. Wygeneruj nowy kod i poproś specjalistę o ponowienie.");
        }
        if (decision.approve() == null) throw error(HttpStatus.BAD_REQUEST, "Wybierz decyzję.");
        UUID grantId = null;
        if (decision.approve()) {
            Set<UUID> selected = new LinkedHashSet<>(decision.studyIds() == null ? List.of() : decision.studyIds());
            Set<UUID> eligible = new HashSet<>(studyOptions(request).stream().map(StudyOption::id).toList());
            if (selected.isEmpty() || selected.size() > 2000 || selected.contains(null) || !eligible.containsAll(selected))
                throw error(HttpStatus.BAD_REQUEST, "Zaznacz dostępne badania w zakresie tej prośby. Odśwież listę, jeśli badanie zostało usunięte.");
            boolean active = jdbc.sql("SELECT count(*) FROM users WHERE id = :id AND status = 'ACTIVE'")
                    .param("id", request.requesterId()).query(Integer.class).single() == 1;
            if (!active) throw error(HttpStatus.CONFLICT, "Konto odbiorcy nie jest aktywne.");
            grantId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO access_grants (id, patient_id, recipient_user_id, granted_by, scope, purpose, expires_at, status)
                    VALUES (:id, :patient, :recipient, :actor, 'SELECTED_STUDIES', :purpose, :expiry, 'ACTIVE')
                    """).param("id", grantId).param("patient", actor.patientId()).param("recipient", request.requesterId())
                    .param("actor", actor.id()).param("purpose", request.purpose())
                    .param("expiry", time(now.plusSeconds(request.durationDays() * 86400L))).update();
            for (UUID studyId : selected) jdbc.sql("INSERT INTO access_grant_studies (grant_id, study_id) VALUES (:grant, :study)")
                    .param("grant", grantId).param("study", studyId).update();
            audit.record(actor.id(), actor.patientId(), "ACCESS_GRANT", grantId, "SHARE", true, null,
                    Map.of("requestId", id, "recipientUserId", request.requesterId(), "studyIds", selected, "durationDays", request.durationDays()));
        }
        jdbc.sql("UPDATE sharing_requests SET status = :status, decided_at = :now, grant_id = :grant WHERE id = :id")
                .param("status", decision.approve() ? "APPROVED" : "REJECTED").param("now", time(now))
                .param("grant", grantId).param("id", id).update();
        audit.record(actor.id(), actor.patientId(), "SHARING_REQUEST", id, "UPDATE", true, null,
                Map.of("decision", decision.approve() ? "APPROVED" : "REJECTED"));
        return dto(findRequest(id, false), actor, now);
    }

    private UserContext requirePatient(Jwt jwt, String role) {
        UserContext actor = contexts.resolve(jwt, role);
        if (!actor.patientView() || actor.patientId() == null || !actor.roles().contains("PATIENT"))
            throw error(HttpStatus.FORBIDDEN, "Kod i zgodę może utworzyć tylko właściciel konta pacjenta.");
        return actor;
    }

    private UserContext requirePractitioner(Jwt jwt, String role) {
        UserContext actor = contexts.resolve(jwt, role);
        if (!actor.practitionerView() || Collections.disjoint(actor.roles(), Set.of("DOCTOR", "DIAGNOSTICIAN", "PHYSIOTHERAPIST", "TRAINER")))
            throw error(HttpStatus.FORBIDDEN, "Kod może wpisać zalogowany lekarz, diagnosta, fizjoterapeuta lub trener.");
        return actor;
    }

    private String practitionerRole(UserContext actor) {
        for (String role : actor.effectiveView().equals("doctor") ? List.of("DOCTOR", "DIAGNOSTICIAN") : List.of("PHYSIOTHERAPIST", "TRAINER"))
            if (actor.roles().contains(role)) return role;
        throw error(HttpStatus.FORBIDDEN, "Konto nie ma uprawnień wybranego specjalisty.");
    }

    private void validateRequest(CreateRequest request) {
        if (request == null || request.code() == null || request.code().length() > 32 || request.scope() == null || !SCOPES.contains(request.scope())
                || request.purpose() == null || request.purpose().isBlank() || request.purpose().length() > 500
                || request.durationDays() < 1 || request.durationDays() > 365)
            throw error(HttpStatus.BAD_REQUEST, "Uzupełnij kod, zakres, cel i czas dostępu od 1 do 365 dni.");
    }

    private void lockActor(UUID id) {
        jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).single();
    }

    private RequestRow findRequest(UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM sharing_requests WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query((rs, row) -> new RequestRow(id, rs.getObject("patient_id", UUID.class),
                        rs.getObject("requester_id", UUID.class), rs.getString("requester_role"), rs.getString("scope"),
                        rs.getString("purpose"), rs.getInt("duration_days"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getObject("grant_id", UUID.class)))
                .optional().orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Nie znaleziono prośby."));
    }

    private RequestDto dto(RequestRow request, UserContext actor, Instant now) {
        String[] requester = jdbc.sql("SELECT first_name, last_name, email FROM users WHERE id = :id")
                .param("id", request.requesterId()).query((rs, row) -> new String[]{rs.getString("first_name") + " " + rs.getString("last_name"), rs.getString("email")}).single();
        boolean activeGrant = request.grantId() != null && jdbc.sql("SELECT count(*) FROM access_grants WHERE id = :id AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > :now)")
                .param("id", request.grantId()).param("now", time(now)).query(Integer.class).single() == 1;
        boolean disclose = actor.patientView() || activeGrant;
        String patientName = disclose ? jdbc.sql("SELECT u.first_name, u.last_name FROM patients p JOIN users u ON u.id = p.user_id WHERE p.id = :id")
                .param("id", request.patientId()).query((rs, row) -> rs.getString("first_name") + " " + rs.getString("last_name")).single() : null;
        List<StudyOption> studies = request.status().equals("APPROVED") && disclose ? jdbc.sql("""
                SELECT s.id, s.title, t.name FROM access_grant_studies gs JOIN studies s ON s.id = gs.study_id
                JOIN study_types t ON t.id = s.study_type_id WHERE gs.grant_id = :grant ORDER BY s.performed_at DESC
                """).param("grant", request.grantId()).query((rs, row) -> new StudyOption(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("name"))).list()
                : actor.patientView() && request.status().equals("PENDING") ? studyOptions(request) : List.of();
        return new RequestDto(request.id(), request.status(), requester[0], requester[1], request.requesterRole(),
                disclose ? request.patientId() : null, patientName, request.scope(), request.purpose(), request.durationDays(),
                request.createdAt(), request.expiresAt(), request.grantId(), studies, now);
    }

    private List<StudyOption> studyOptions(RequestRow request) {
        String condition = switch (request.scope()) {
            case "LAB_RESULTS" -> " AND (t.code = 'LABORATORY' OR s.metadata -> 'clinicalResult' ->> 'presentation' = 'parameters')";
            case "IMAGING_STUDIES" -> " AND (t.code IN ('MRI', 'CT', 'USG', 'ULTRASOUND', 'XRAY', 'RTG') OR EXISTS (SELECT 1 FROM imaging_studies i WHERE i.id = s.id))";
            default -> "";
        };
        return jdbc.sql("SELECT s.id, s.title, t.name FROM studies s JOIN study_types t ON t.id = s.study_type_id WHERE s.patient_id = :patient AND s.archived_at IS NULL" + condition + " ORDER BY s.performed_at DESC")
                .param("patient", request.patientId()).query((rs, row) -> new StudyOption(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("name"))).list();
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    private record CodeRow(UUID id, UUID patientId, UUID createdBy, Instant expiresAt, String status) { }
    private record RequestRow(UUID id, UUID patientId, UUID requesterId, String requesterRole, String scope, String purpose,
                              int durationDays, String status, Instant createdAt, Instant expiresAt, UUID grantId) { }
}
