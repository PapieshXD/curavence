CREATE TABLE imaging_analysis_feedback (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES imaging_analyses(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id),
    finding_id VARCHAR(100) NOT NULL,
    action VARCHAR(30) NOT NULL CHECK (action IN ('CONFIRMED','REJECTED','NEEDS_REVIEW','CORRECTED')),
    comment VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE imaging_view_preferences (
    user_id UUID NOT NULL REFERENCES users(id),
    study_id UUID NOT NULL REFERENCES imaging_studies(id) ON DELETE CASCADE,
    hidden_series_uids JSONB NOT NULL DEFAULT '[]',
    last_layout VARCHAR(30) NOT NULL DEFAULT 'study',
    PRIMARY KEY (user_id, study_id)
);
