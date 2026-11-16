package com.ralek.backend.imaging.application;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImagingAccessServiceTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID practitioner = UUID.randomUUID();
    private final UUID technicalAdministrator = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID study = UUID.randomUUID();
    private JdbcClient jdbc;
    private ImagingAccessService access;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:access-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE TABLE patients (id UUID PRIMARY KEY, user_id UUID NOT NULL)").update();
        jdbc.sql("CREATE TABLE imaging_studies (id UUID PRIMARY KEY, patient_id UUID NOT NULL)").update();
        jdbc.sql("""
                CREATE TABLE access_grants (
                  id UUID PRIMARY KEY, patient_id UUID, recipient_user_id UUID,
                  scope VARCHAR(40), status VARCHAR(20), expires_at TIMESTAMP WITH TIME ZONE
                )
                """).update();
        jdbc.sql("CREATE TABLE access_grant_studies (grant_id UUID, study_id UUID)").update();
        jdbc.sql("INSERT INTO patients (id, user_id) VALUES (:patient, :owner)")
                .param("patient", patient).param("owner", owner).update();
        jdbc.sql("INSERT INTO imaging_studies (id, patient_id) VALUES (:study, :patient)")
                .param("study", study).param("patient", patient).update();
        access = new ImagingAccessService(jdbc);
    }

    @Test
    void ownerCanViewButTechnicalAdministratorHasNoImplicitAccess() {
        assertThat(access.canView(owner, study)).isTrue();
        assertThat(access.canView(technicalAdministrator, study)).isFalse();
    }

    @Test
    void activeFullStudyGrantAllowsPractitionerToView() {
        jdbc.sql("""
                INSERT INTO access_grants (id, patient_id, recipient_user_id, scope, status)
                VALUES (:id, :patient, :recipient, 'FULL_STUDY', 'ACTIVE')
                """)
                .param("id", UUID.randomUUID()).param("patient", patient)
                .param("recipient", practitioner).update();

        assertThat(access.canView(practitioner, study)).isTrue();
        assertThat(access.canViewArtifacts(practitioner, study)).isTrue();
    }

    @Test
    void rehabilitationSummaryDoesNotGrantRawArtifactsAndRevocationRemovesAccess() {
        UUID grant = UUID.randomUUID();
        jdbc.sql("INSERT INTO access_grants (id, patient_id, recipient_user_id, scope, status) VALUES (:id, :patient, :recipient, 'REHABILITATION_SUMMARY', 'ACTIVE')")
                .param("id", grant).param("patient", patient).param("recipient", practitioner).update();
        jdbc.sql("INSERT INTO access_grant_studies VALUES (:grant, :study)")
                .param("grant", grant).param("study", study).update();
        assertThat(access.canView(practitioner, study)).isTrue();
        assertThat(access.canViewArtifacts(practitioner, study)).isFalse();
        assertThat(access.canViewArtifacts(owner, study)).isTrue();
        jdbc.sql("UPDATE access_grants SET status = 'REVOKED' WHERE id = :id").param("id", grant).update();
        assertThat(access.canView(practitioner, study)).isFalse();
        assertThat(access.canViewArtifacts(practitioner, study)).isFalse();
    }
}
