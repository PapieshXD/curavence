CREATE TABLE sharing_codes (
    id UUID PRIMARY KEY,
    patient_id UUID NOT NULL REFERENCES patients(id),
    created_by UUID NOT NULL REFERENCES users(id),
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'REDEEMED', 'REVOKED')),
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_sharing_codes_patient ON sharing_codes(patient_id, status);

CREATE TABLE sharing_requests (
    id UUID PRIMARY KEY,
    code_id UUID NOT NULL UNIQUE REFERENCES sharing_codes(id),
    patient_id UUID NOT NULL REFERENCES patients(id),
    requester_id UUID NOT NULL REFERENCES users(id),
    requester_role VARCHAR(32) NOT NULL,
    scope VARCHAR(32) NOT NULL CHECK (scope IN ('FULL_STUDY', 'LAB_RESULTS', 'IMAGING_STUDIES', 'SELECTED_STUDIES')),
    purpose VARCHAR(500) NOT NULL,
    duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 365),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE,
    grant_id UUID REFERENCES access_grants(id),
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_sharing_requests_patient ON sharing_requests(patient_id, created_at DESC);
CREATE INDEX idx_sharing_requests_requester ON sharing_requests(requester_id, created_at DESC);

CREATE TABLE sharing_code_attempts (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id),
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_sharing_attempts_actor_time ON sharing_code_attempts(actor_id, attempted_at);
