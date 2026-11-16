package com.ralek.backend.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(UUID actorId, UUID patientId, String resourceType, UUID resourceId,
                       String action, boolean success, String denialReason, Map<String, ?> metadata) {
        jdbc.sql("""
                        INSERT INTO audit_events (
                            id, actor_user_id, patient_id, resource_type, resource_id, action,
                            success, denial_reason, metadata
                        ) VALUES (:id, :actor, :patient, :resourceType, :resourceId, :action,
                                  :success, :denialReason, CAST(:metadata AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("actor", actorId)
                .param("patient", patientId)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("action", action)
                .param("success", success)
                .param("denialReason", denialReason)
                .param("metadata", json(metadata))
                .update();
    }

    public void recordLogin(String email, boolean success, String denialReason, String remoteAddress) {
        UUID actorId = jdbc.sql("SELECT id FROM users WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(UUID.class)
                .optional()
                .orElse(null);
        UUID patientId = actorId == null ? null : jdbc.sql("SELECT id FROM patients WHERE user_id = :userId")
                .param("userId", actorId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        jdbc.sql("""
                        INSERT INTO audit_events (
                            id, actor_user_id, patient_id, resource_type, action, success,
                            denial_reason, metadata, ip_hash
                        ) VALUES (:id, :actor, :patient, 'AUTH', :action, :success,
                                  :denialReason, '{}'::jsonb, :ipHash)
                        """)
                .param("id", UUID.randomUUID())
                .param("actor", actorId)
                .param("patient", patientId)
                .param("action", success ? "LOGIN" : "ACCESS_DENIED")
                .param("success", success)
                .param("denialReason", denialReason)
                .param("ipHash", hash(remoteAddress))
                .update();
    }

    private String json(Map<String, ?> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (RuntimeException exception) {
            return "{}";
        }
    }

    private String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "unknown" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
