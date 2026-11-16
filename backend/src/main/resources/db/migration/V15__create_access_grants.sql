CREATE TABLE access_grants (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    recipient_user_id UUID NOT NULL REFERENCES users(id),
    granted_by UUID NOT NULL REFERENCES users(id),
    scope VARCHAR(40) NOT NULL CHECK (scope IN (
        'FULL_STUDY', 'SELECTED_STUDIES', 'LAB_RESULTS', 'DIAGNOSTIC_REPORT',
        'REHABILITATION_SUMMARY', 'TRAINING_RESTRICTIONS'
    )),
    purpose VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX idx_access_grants_patient_recipient ON access_grants(patient_id, recipient_user_id);
CREATE INDEX idx_access_grants_expires ON access_grants(expires_at);
