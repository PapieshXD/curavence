ALTER TABLE studies ADD COLUMN metadata_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE studies ADD COLUMN archived_ingestion_status VARCHAR(20);
CREATE TABLE document_metadata_history (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES studies(id),
    actor_id UUID NOT NULL REFERENCES users(id),
    old_title VARCHAR(255) NOT NULL,
    new_title VARCHAR(255) NOT NULL,
    old_performed_at TIMESTAMPTZ,
    new_performed_at TIMESTAMPTZ,
    old_type_code VARCHAR(80) NOT NULL,
    new_type_code VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_document_history ON document_metadata_history(study_id,created_at);
CREATE TABLE document_search_text (
    study_id UUID PRIMARY KEY REFERENCES studies(id),
    source_asset_id UUID REFERENCES study_assets(id),
    content TEXT NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','READY','FAILED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO document_search_text(study_id,source_asset_id)
SELECT DISTINCT ON (sa.study_id) sa.study_id,sa.id FROM study_assets sa
WHERE sa.asset_type='EXTRACTED_TEXT' ORDER BY sa.study_id,sa.created_at DESC,sa.id;
CREATE INDEX idx_document_search_pending ON document_search_text(status);
CREATE TABLE patient_exports (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id),
    patient_id UUID NOT NULL REFERENCES patients(id),
    request_key UUID NOT NULL,
    request_json TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
      CHECK (status IN ('QUEUED','GENERATING','READY','FAILED','EXPIRED','INVALIDATED')),
    error_message VARCHAR(500),
    bucket_name VARCHAR(120),
    object_key VARCHAR(1024),
    byte_size BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '24 hours',
    UNIQUE(actor_id,request_key)
);
CREATE INDEX idx_patient_exports_queue ON patient_exports(status,created_at);
