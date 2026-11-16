CREATE TABLE imaging_analyses (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES imaging_studies(id) ON DELETE CASCADE,
    requested_by UUID NOT NULL REFERENCES users(id),
    pipeline VARCHAR(50) NOT NULL DEFAULT 'AUTO',
    engine VARCHAR(100),
    status VARCHAR(30) NOT NULL CHECK (status IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'UNSUPPORTED', 'FAILED', 'CANCELLED'
    )),
    progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    classification JSONB NOT NULL DEFAULT '{}'::jsonb,
    quality JSONB NOT NULL DEFAULT '{}'::jsonb,
    measurements JSONB NOT NULL DEFAULT '[]'::jsonb,
    observations JSONB NOT NULL DEFAULT '[]'::jsonb,
    candidate_findings JSONB NOT NULL DEFAULT '[]'::jsonb,
    model_versions JSONB NOT NULL DEFAULT '{}'::jsonb,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    summary TEXT,
    disclaimer TEXT NOT NULL DEFAULT 'Wynik automatyczny nie jest diagnozą i wymaga oceny wykwalifikowanego specjalisty.',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_imaging_analyses_study_created
    ON imaging_analyses(study_id, created_at DESC);

CREATE UNIQUE INDEX uq_imaging_analyses_active_study
    ON imaging_analyses(study_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE imaging_analysis_artifacts (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES imaging_analyses(id) ON DELETE CASCADE,
    series_id UUID REFERENCES imaging_series(id) ON DELETE SET NULL,
    storage_object_id UUID NOT NULL REFERENCES storage_objects(id),
    kind VARCHAR(50) NOT NULL,
    label VARCHAR(255) NOT NULL,
    slice_index INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_imaging_analysis_artifacts_analysis
    ON imaging_analysis_artifacts(analysis_id, created_at);

