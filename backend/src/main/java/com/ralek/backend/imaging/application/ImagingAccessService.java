package com.ralek.backend.imaging.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ImagingAccessService {

    private final JdbcClient jdbc;

    public ImagingAccessService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean canView(UUID authenticatedUserId, UUID imagingStudyId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM imaging_studies imaging
                        WHERE imaging.id = :study
                          AND (
                            EXISTS (
                              SELECT 1 FROM patients owner
                              WHERE owner.id = imaging.patient_id AND owner.user_id = :user
                            )
                            OR EXISTS (
                              SELECT 1
                              FROM access_grants grant_record
                              WHERE grant_record.patient_id = imaging.patient_id
                                AND grant_record.recipient_user_id = :user
                                AND grant_record.status = 'ACTIVE'
                                AND (grant_record.expires_at IS NULL OR grant_record.expires_at > CURRENT_TIMESTAMP)
                                AND (
                                  grant_record.scope = 'FULL_STUDY'
                                  OR (
                                    grant_record.scope IN ('SELECTED_STUDIES', 'REHABILITATION_SUMMARY')
                                    AND EXISTS (
                                      SELECT 1 FROM access_grant_studies selected
                                      WHERE selected.grant_id = grant_record.id
                                        AND selected.study_id = imaging.id
                                    )
                                  )
                                )
                            )
                          )
                        """)
                .param("study", imagingStudyId)
                .param("user", authenticatedUserId)
                .query(Integer.class)
                .single() > 0;
    }

    public boolean canViewArtifacts(UUID authenticatedUserId, UUID imagingStudyId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM imaging_studies imaging
                        WHERE imaging.id = :study
                          AND (
                            EXISTS (
                              SELECT 1 FROM patients owner
                              WHERE owner.id = imaging.patient_id AND owner.user_id = :user
                            )
                            OR EXISTS (
                              SELECT 1
                              FROM access_grants grant_record
                              WHERE grant_record.patient_id = imaging.patient_id
                                AND grant_record.recipient_user_id = :user
                                AND grant_record.status = 'ACTIVE'
                                AND (grant_record.expires_at IS NULL OR grant_record.expires_at > CURRENT_TIMESTAMP)
                                AND (
                                  grant_record.scope = 'FULL_STUDY'
                                  OR (
                                    grant_record.scope IN ('SELECTED_STUDIES')
                                    AND EXISTS (
                                      SELECT 1 FROM access_grant_studies selected
                                      WHERE selected.grant_id = grant_record.id
                                        AND selected.study_id = imaging.id
                                    )
                                  )
                                )
                            )
                          )
                        """)
                .param("study", imagingStudyId)
                .param("user", authenticatedUserId)
                .query(Integer.class)
                .single() > 0;
    }

    public boolean canAccessPatient(UUID authenticatedUserId, UUID patientId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM patients patient
                        WHERE patient.id = :patient
                          AND (
                            patient.user_id = :user
                            OR EXISTS (
                              SELECT 1 FROM access_grants grant_record
                              WHERE grant_record.patient_id = patient.id
                                AND grant_record.recipient_user_id = :user
                                AND grant_record.status = 'ACTIVE'
                                AND (grant_record.expires_at IS NULL OR grant_record.expires_at > CURRENT_TIMESTAMP)
                                AND grant_record.scope IN ('FULL_STUDY', 'SELECTED_STUDIES', 'REHABILITATION_SUMMARY')
                            )
                          )
                        """)
                .param("patient", patientId)
                .param("user", authenticatedUserId)
                .query(Integer.class)
                .single() > 0;
    }
}
