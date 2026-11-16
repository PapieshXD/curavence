CREATE TABLE study_assets (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES studies(id),
    storage_object_id UUID NOT NULL REFERENCES storage_objects(id),
    asset_type VARCHAR(30) NOT NULL CHECK (asset_type IN (
        'ORIGINAL', 'PREVIEW', 'EXTRACTED_TEXT', 'NORMALIZED_DATA', 'REPORT', 'DERIVED_IMAGE'
    )),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    version_number INTEGER NOT NULL DEFAULT 1 CHECK (version_number > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (study_id, storage_object_id)
);

CREATE INDEX idx_study_assets_study ON study_assets(study_id);
