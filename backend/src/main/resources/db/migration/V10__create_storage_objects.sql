CREATE TABLE storage_objects (
    id UUID PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    bucket_name VARCHAR(120) NOT NULL,
    object_key VARCHAR(1024) NOT NULL UNIQUE,
    original_filename VARCHAR(512) NOT NULL,
    mime_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 VARCHAR(64),
    storage_status VARCHAR(20) NOT NULL CHECK (storage_status IN ('PENDING', 'STORED', 'FAILED', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_storage_objects_object_key ON storage_objects(object_key);
