CREATE TABLE studies (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    study_type_id UUID NOT NULL REFERENCES study_types(id),
    created_by UUID NOT NULL REFERENCES users(id),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    source_name VARCHAR(255),
    performed_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ingestion_status VARCHAR(20) NOT NULL CHECK (ingestion_status IN (
        'CREATED', 'UPLOADING', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'ARCHIVED'
    )),
    clinical_status VARCHAR(30) NOT NULL CHECK (clinical_status IN (
        'DRAFT', 'AWAITING_REVIEW', 'REVIEWED', 'SIGNED', 'AMENDED'
    )),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ
);

CREATE INDEX idx_studies_patient_performed ON studies(patient_id, performed_at DESC);
CREATE INDEX idx_studies_patient_clinical ON studies(patient_id, clinical_status);
