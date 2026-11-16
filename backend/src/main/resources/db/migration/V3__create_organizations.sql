CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    organization_type VARCHAR(40) NOT NULL CHECK (organization_type IN (
        'CLINIC', 'HOSPITAL', 'LABORATORY', 'PRIVATE_PRACTICE',
        'REHABILITATION_CENTER', 'SPORT_CENTER'
    )),
    nip VARCHAR(20),
    address JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
