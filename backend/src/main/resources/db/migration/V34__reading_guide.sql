ALTER TABLE imaging_studies ADD COLUMN report_document_id UUID REFERENCES studies(id);

CREATE TABLE personal_reminders (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    document_id UUID NOT NULL REFERENCES studies(id),
    recommendation_id VARCHAR(64) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    source_quote TEXT NOT NULL,
    due_date DATE NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (patient_id, document_id, recommendation_id)
);
CREATE INDEX idx_personal_reminders_due ON personal_reminders(patient_id, completed, due_date);
