CREATE TABLE imaging_studies (
    id UUID PRIMARY KEY REFERENCES studies(id) ON DELETE CASCADE,
    patient_id UUID NOT NULL REFERENCES patients(id),
    study_instance_uid VARCHAR(128),
    storage_provider VARCHAR(30) NOT NULL DEFAULT 'ORTHANC',
    modality VARCHAR(16),
    study_description VARCHAR(255),
    body_part VARCHAR(100),
    performed_at TIMESTAMPTZ,
    series_count INTEGER NOT NULL DEFAULT 0,
    instance_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL CHECK (status IN ('UPLOADING', 'VALIDATING', 'STORING', 'AVAILABLE', 'FAILED')),
    error_message TEXT,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (storage_provider, study_instance_uid)
);

CREATE INDEX idx_imaging_studies_patient_date ON imaging_studies(patient_id, performed_at DESC);
CREATE INDEX idx_imaging_studies_status ON imaging_studies(status);

CREATE TABLE imaging_series (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES imaging_studies(id) ON DELETE CASCADE,
    series_instance_uid VARCHAR(128) NOT NULL,
    series_number INTEGER,
    series_description VARCHAR(255),
    sequence_name VARCHAR(100),
    image_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE (study_id, series_instance_uid)
);

CREATE TABLE imaging_uploads (
    id UUID PRIMARY KEY,
    study_id UUID REFERENCES imaging_studies(id) ON DELETE CASCADE,
    original_filename VARCHAR(255),
    status VARCHAR(30) NOT NULL CHECK (status IN ('UPLOADING', 'VALIDATING', 'STORING', 'AVAILABLE', 'FAILED')),
    progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_imaging_uploads_study ON imaging_uploads(study_id, created_at DESC);
