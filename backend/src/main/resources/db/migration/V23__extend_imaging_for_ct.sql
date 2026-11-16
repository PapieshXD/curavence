INSERT INTO study_types (id, code, name, domain, default_viewer)
VALUES ('70000000-0000-0000-0000-000000000007', 'CT', 'Tomografia komputerowa', 'IMAGING', 'IMAGE')
ON CONFLICT (code) DO NOTHING;

ALTER TABLE imaging_studies
    DROP CONSTRAINT IF EXISTS imaging_studies_status_check;

ALTER TABLE imaging_studies
    ADD COLUMN provider_reference VARCHAR(512),
    ADD COLUMN source_format VARCHAR(30),
    ADD COLUMN is_deidentified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN total_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN verified_at TIMESTAMPTZ,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT imaging_studies_status_check CHECK (status IN (
        'UPLOADING', 'VALIDATING', 'STORING', 'VERIFYING', 'AVAILABLE', 'FAILED', 'CANCELLED'
    ));

ALTER TABLE imaging_series
    ADD COLUMN modality VARCHAR(16),
    ADD COLUMN rows_count INTEGER,
    ADD COLUMN columns_count INTEGER,
    ADD COLUMN pixel_spacing_x DOUBLE PRECISION,
    ADD COLUMN pixel_spacing_y DOUBLE PRECISION,
    ADD COLUMN slice_thickness DOUBLE PRECISION,
    ADD COLUMN calculated_spacing DOUBLE PRECISION,
    ADD COLUMN orientation_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN frame_of_reference_uid VARCHAR(128),
    ADD COLUMN transfer_syntax_uid VARCHAR(128),
    ADD COLUMN rescale_slope DOUBLE PRECISION,
    ADD COLUMN rescale_intercept DOUBLE PRECISION,
    ADD COLUMN window_center JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN window_width JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN photometric_interpretation VARCHAR(32),
    ADD COLUMN bits_stored INTEGER,
    ADD COLUMN pixel_representation INTEGER,
    ADD COLUMN stack_eligible BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN mpr_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN volume_rendering_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN eligibility_reason VARCHAR(500);

CREATE TABLE imaging_instances (
    id UUID PRIMARY KEY,
    series_id UUID NOT NULL REFERENCES imaging_series(id) ON DELETE CASCADE,
    sop_instance_uid VARCHAR(128) NOT NULL,
    sop_class_uid VARCHAR(128),
    instance_number INTEGER,
    position_x DOUBLE PRECISION,
    position_y DOUBLE PRECISION,
    position_z DOUBLE PRECISION,
    orientation_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    projected_slice_position DOUBLE PRECISION,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    transfer_syntax_uid VARCHAR(128),
    UNIQUE (series_id, sop_instance_uid)
);

CREATE INDEX idx_imaging_instances_series_position
    ON imaging_instances(series_id, projected_slice_position, instance_number);

ALTER TABLE imaging_uploads
    DROP CONSTRAINT IF EXISTS imaging_uploads_status_check;

ALTER TABLE imaging_uploads
    ADD COLUMN requested_by UUID REFERENCES users(id),
    ADD COLUMN staging_directory VARCHAR(1024),
    ADD COLUMN received_files INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN validated_files INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN stored_files INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN failed_files INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN received_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD CONSTRAINT imaging_uploads_status_check CHECK (status IN (
        'UPLOADING', 'VALIDATING', 'STORING', 'VERIFYING', 'AVAILABLE', 'FAILED', 'CANCELLED'
    ));

CREATE TABLE imaging_viewer_sessions (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES imaging_studies(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    secret_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_access_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    open_audited BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_imaging_viewer_sessions_expiry
    ON imaging_viewer_sessions(expires_at) WHERE revoked_at IS NULL;

CREATE TABLE imaging_annotations (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES imaging_studies(id) ON DELETE CASCADE,
    series_id UUID REFERENCES imaging_series(id) ON DELETE CASCADE,
    sop_instance_uid VARCHAR(128),
    frame_of_reference_uid VARCHAR(128),
    author_id UUID NOT NULL REFERENCES users(id),
    annotation_type VARCHAR(50) NOT NULL,
    geometry JSONB NOT NULL,
    measurement JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_imaging_annotations_study
    ON imaging_annotations(study_id, updated_at DESC) WHERE deleted_at IS NULL;
