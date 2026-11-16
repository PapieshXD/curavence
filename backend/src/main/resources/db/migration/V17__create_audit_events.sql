CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES users(id),
    patient_id UUID REFERENCES patients(id),
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID,
    action VARCHAR(30) NOT NULL CHECK (action IN (
        'VIEW', 'DOWNLOAD', 'UPLOAD', 'CREATE', 'UPDATE', 'DELETE', 'SHARE',
        'REVOKE_ACCESS', 'SIGN', 'EXPORT', 'LOGIN', 'ACCESS_DENIED'
    )),
    success BOOLEAN NOT NULL,
    denial_reason VARCHAR(500),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_events_patient_created ON audit_events(patient_id, created_at DESC);
