CREATE TABLE visit_packages (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    reason VARCHAR(240) NOT NULL,
    symptoms TEXT NOT NULL DEFAULT '',
    questions TEXT NOT NULL DEFAULT '',
    visit_date DATE,
    grant_id UUID REFERENCES access_grants(id),
    summary TEXT NOT NULL DEFAULT '',
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_visit_patient ON visit_packages(patient_id);
CREATE TABLE visit_studies (
    visit_id UUID NOT NULL REFERENCES visit_packages(id) ON DELETE CASCADE,
    study_id UUID NOT NULL REFERENCES studies(id),
    PRIMARY KEY(visit_id, study_id)
);
CREATE TABLE visit_actions (
    id UUID PRIMARY KEY,
    visit_id UUID NOT NULL REFERENCES visit_packages(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    description VARCHAR(1000) NOT NULL,
    due_date DATE NOT NULL,
    responsible VARCHAR(120) NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE
);
