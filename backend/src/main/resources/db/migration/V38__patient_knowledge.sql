CREATE TABLE patient_knowledge (
 id UUID PRIMARY KEY,
 actor_id UUID NOT NULL REFERENCES users(id),
 patient_id UUID NOT NULL REFERENCES patients(id),
 visit_id UUID REFERENCES visit_packages(id) ON DELETE CASCADE,
 owner_view BOOLEAN NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','GENERATING','READY','FAILED','INVALIDATED')),
 fingerprint VARCHAR(64) NOT NULL DEFAULT '',
 content_json TEXT NOT NULL DEFAULT '{"sources":[],"pages":[]}',
 attempts INTEGER NOT NULL DEFAULT 0,
 error_message VARCHAR(300),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 started_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX idx_knowledge_scope ON patient_knowledge(actor_id,patient_id,COALESCE(visit_id,'00000000-0000-0000-0000-000000000000'::uuid),owner_view);
CREATE INDEX idx_knowledge_queue ON patient_knowledge(status,updated_at);
CREATE TABLE patient_knowledge_pages (
 scope_id UUID NOT NULL REFERENCES patient_knowledge(id) ON DELETE CASCADE,
 source_id VARCHAR(100) NOT NULL,
 version VARCHAR(64) NOT NULL,
 points_json TEXT NOT NULL,
 PRIMARY KEY(scope_id,source_id)
);
