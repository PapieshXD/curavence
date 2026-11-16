CREATE TABLE practitioners (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    organization_id UUID REFERENCES organizations(id),
    professional_type VARCHAR(30) NOT NULL CHECK (professional_type IN (
        'DOCTOR', 'DIAGNOSTICIAN', 'PHYSIOTHERAPIST', 'TRAINER'
    )),
    license_number VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
