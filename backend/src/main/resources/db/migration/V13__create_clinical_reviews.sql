CREATE TABLE clinical_reviews (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES studies(id),
    reviewer_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'SIGNED', 'AMENDED')),
    summary TEXT,
    conclusion TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    signed_at TIMESTAMPTZ
);

CREATE INDEX idx_clinical_reviews_study ON clinical_reviews(study_id);
