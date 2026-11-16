package com.ralek.backend.imaging.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against PostgreSQL in an isolated schema; all changes are rolled back. */
@EnabledIfEnvironmentVariable(named = "IMAGING_TEST_DB_URL", matches = ".+")
class StudyAnalysisMigrationTest {
    @Test
    void upgradesExistingAnalysesAndPersistsStudyWorkspaceWithoutMutatingSource() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("IMAGING_TEST_DB_URL"),
                System.getenv("IMAGING_TEST_DB_USER"), System.getenv("IMAGING_TEST_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                String schema = "imaging_test_" + UUID.randomUUID().toString().replace("-", "");
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("SET LOCAL search_path TO " + schema);
                statement.execute("CREATE TABLE users (id UUID PRIMARY KEY); CREATE TABLE imaging_studies (id UUID PRIMARY KEY); CREATE TABLE imaging_series (id UUID PRIMARY KEY); CREATE TABLE storage_objects (id UUID PRIMARY KEY)");
                statement.execute(migration("V24__create_imaging_analyses.sql"));
                String user = UUID.randomUUID().toString(), study = UUID.randomUUID().toString(), first = UUID.randomUUID().toString();
                statement.execute("INSERT INTO users VALUES ('" + user + "'); INSERT INTO imaging_studies VALUES ('" + study + "')");
                statement.execute("INSERT INTO imaging_analyses (id,study_id,requested_by,status) VALUES ('" + first + "','" + study + "','" + user + "','SUCCEEDED')");
                statement.execute(migration("V28__study_analysis_evidence.sql"));
                statement.execute(migration("V29__study_workspace_decisions.sql"));
                String second = UUID.randomUUID().toString();
                try (var insert = connection.prepareStatement("""
                        INSERT INTO imaging_analyses (id, study_id, requested_by, status, analysis_version, parent_analysis_id)
                        SELECT ?, ?, ?, 'DEGRADED', COALESCE(MAX(analysis_version), 0) + 1,
                          (SELECT id FROM imaging_analyses WHERE study_id = ? ORDER BY analysis_version DESC LIMIT 1)
                        FROM imaging_analyses WHERE study_id = ?
                        """)) {
                    insert.setObject(1, UUID.fromString(second)); insert.setObject(2, UUID.fromString(study));
                    insert.setObject(3, UUID.fromString(user)); insert.setObject(4, UUID.fromString(study)); insert.setObject(5, UUID.fromString(study));
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
                try (var rows = statement.executeQuery("SELECT analysis_version, parent_analysis_id FROM imaging_analyses ORDER BY analysis_version DESC")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2);
                    assertThat(rows.getString(2)).isEqualTo(first);
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
                    assertThat(rows.getString(2)).isNull();
                }
                statement.execute("INSERT INTO imaging_analysis_series VALUES ('" + second + "','test-uid','ANALYZED','DIAGNOSTIC')");
                statement.execute("INSERT INTO imaging_view_preferences VALUES ('" + user + "','" + study + "','[\"test-uid\"]','study')");
                try (var rows = statement.executeQuery("SELECT count(*) FROM imaging_analysis_series WHERE participation_status = 'ANALYZED'")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private String migration(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
