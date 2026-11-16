package com.ralek.backend.workspace;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UserContextService {

    private final JdbcClient jdbc;

    public UserContextService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UserContext resolve(Jwt jwt, String requestedView) {
        DatabaseUser user = jdbc.sql("""
                        SELECT id, email, first_name, last_name
                        FROM users
                        WHERE keycloak_subject = :subject AND status = 'ACTIVE'
                        """)
                .param("subject", jwt.getSubject())
                .query((rs, rowNum) -> new DatabaseUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("first_name"),
                        rs.getString("last_name")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Użytkownik nie jest aktywny w Curavence"));

        Set<String> roles = keycloakRoles(jwt);
        boolean superuser = roles.contains("SUPERUSER");
        List<String> views = availableViews(roles, superuser);
        if (views.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Konto nie ma obsługiwanej roli");
        }
        String effectiveView = requestedView != null && views.contains(requestedView.toLowerCase())
                ? requestedView.toLowerCase()
                : views.getFirst();
        UUID patientId = jdbc.sql("SELECT id FROM patients WHERE user_id = :userId")
                .param("userId", user.id())
                .query(UUID.class)
                .optional()
                .orElse(null);
        return new UserContext(user.id(), user.email(), user.firstName(), user.lastName(),
                roles, views, superuser, effectiveView, patientId);
    }

    private Set<String> keycloakRoles(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> access && access.get("roles") instanceof Collection<?> values) {
            values.stream().map(Object::toString).forEach(roles::add);
        }
        return Set.copyOf(roles);
    }

    private List<String> availableViews(Set<String> roles, boolean superuser) {
        if (superuser) {
            return List.of("admin", "patient", "doctor", "therapy");
        }
        List<String> result = new ArrayList<>();
        if (roles.contains("ADMINISTRATOR")) result.add("admin");
        if (roles.contains("PATIENT")) result.add("patient");
        if (roles.contains("DOCTOR") || roles.contains("DIAGNOSTICIAN")) result.add("doctor");
        if (roles.contains("PHYSIOTHERAPIST") || roles.contains("TRAINER")) result.add("therapy");
        return List.copyOf(result);
    }

    private record DatabaseUser(UUID id, String email, String firstName, String lastName) {
    }

    public record UserContext(
            UUID id,
            String email,
            String firstName,
            String lastName,
            Set<String> roles,
            List<String> availableViews,
            boolean superuser,
            String effectiveView,
            UUID patientId
    ) {
        public String displayName() {
            return firstName + " " + lastName;
        }

        public boolean administrator() {
            return effectiveView.equals("admin") && (superuser || roles.contains("ADMINISTRATOR"));
        }

        public boolean patientView() {
            return effectiveView.equals("patient");
        }

        public boolean practitionerView() {
            return effectiveView.equals("doctor") || effectiveView.equals("therapy");
        }
    }
}
