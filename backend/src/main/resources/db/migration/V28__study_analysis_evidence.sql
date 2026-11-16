ALTER TABLE imaging_analyses DROP CONSTRAINT imaging_analyses_status_check;
ALTER TABLE imaging_analyses ADD CONSTRAINT imaging_analyses_status_check
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','DEGRADED','UNSUPPORTED','FAILED','CANCELLED'));
ALTER TABLE imaging_analyses
    ADD COLUMN analysis_version INTEGER,
    ADD COLUMN parent_analysis_id UUID REFERENCES imaging_analyses(id),
    ADD COLUMN analysis_scope JSONB NOT NULL DEFAULT '{"selectionMode":"ALL_ELIGIBLE"}',
    ADD COLUMN canonical_evidence JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN evidence_hash VARCHAR(64),
    ADD COLUMN explanations JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN prompt_version VARCHAR(100);
WITH versions AS (
    SELECT id, row_number() OVER (PARTITION BY study_id ORDER BY created_at, id) AS version,
           lag(id) OVER (PARTITION BY study_id ORDER BY created_at, id) AS parent
    FROM imaging_analyses
)
UPDATE imaging_analyses a SET analysis_version = v.version, parent_analysis_id = v.parent
FROM versions v WHERE a.id = v.id;
ALTER TABLE imaging_analyses ALTER COLUMN analysis_version SET NOT NULL;
CREATE UNIQUE INDEX uq_imaging_analysis_version ON imaging_analyses(study_id, analysis_version);
CREATE TABLE imaging_analysis_series (
    analysis_id UUID NOT NULL REFERENCES imaging_analyses(id) ON DELETE CASCADE,
    series_instance_uid TEXT NOT NULL,
    participation_status VARCHAR(40) NOT NULL,
    role VARCHAR(30) NOT NULL,
    PRIMARY KEY (analysis_id, series_instance_uid)
);
