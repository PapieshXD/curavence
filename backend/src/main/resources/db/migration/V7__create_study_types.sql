CREATE TABLE study_types (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    domain VARCHAR(80) NOT NULL,
    default_viewer VARCHAR(80) NOT NULL
);
