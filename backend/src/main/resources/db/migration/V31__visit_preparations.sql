CREATE TABLE visit_preparations (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    grant_id UUID REFERENCES access_grants(id),
    reason VARCHAR(500) NOT NULL,
    symptoms TEXT NOT NULL DEFAULT '',
    questions TEXT NOT NULL DEFAULT '',
    appointment_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE visit_preparation_studies (
    preparation_id UUID NOT NULL REFERENCES visit_preparations(id) ON DELETE CASCADE,
    study_id UUID NOT NULL REFERENCES studies(id),
    PRIMARY KEY (preparation_id, study_id)
);

CREATE INDEX idx_visit_preparations_patient ON visit_preparations(patient_id, updated_at DESC);
CREATE INDEX idx_visit_preparations_grant ON visit_preparations(grant_id);
