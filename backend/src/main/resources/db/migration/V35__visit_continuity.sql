ALTER TABLE visit_packages ADD COLUMN previous_visit_id UUID REFERENCES visit_packages(id);
ALTER TABLE visit_packages ADD COLUMN include_previous_plan BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE visit_actions ADD COLUMN patient_status VARCHAR(24) NOT NULL DEFAULT 'TODO';
UPDATE visit_actions SET patient_status='DONE' WHERE completed=TRUE;
ALTER TABLE visit_actions ADD COLUMN performed_on DATE;
ALTER TABLE visit_actions ADD COLUMN patient_note VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE visit_actions ADD COLUMN obstacle VARCHAR(24);
ALTER TABLE visit_actions ADD COLUMN reported_by UUID REFERENCES users(id);
ALTER TABLE visit_actions ADD COLUMN reported_at TIMESTAMPTZ;
ALTER TABLE visit_actions ADD COLUMN discussed_by UUID REFERENCES users(id);
ALTER TABLE visit_actions ADD COLUMN discussed_at TIMESTAMPTZ;
ALTER TABLE visit_actions ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE visit_actions ADD CONSTRAINT visit_patient_status CHECK (patient_status='TODO' OR patient_status='DONE' OR patient_status='DIFFICULTY');
CREATE TABLE visit_action_studies (
    action_id UUID NOT NULL REFERENCES visit_actions(id) ON DELETE CASCADE,
    study_id UUID NOT NULL REFERENCES studies(id),
    PRIMARY KEY(action_id,study_id)
);
CREATE TABLE visit_notifications (
    id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES users(id),
    visit_id UUID NOT NULL REFERENCES visit_packages(id),
    kind VARCHAR(40) NOT NULL,
    dedup_key VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,
    UNIQUE(recipient_id,dedup_key)
);
CREATE INDEX idx_visit_notifications_recipient ON visit_notifications(recipient_id,created_at);
CREATE INDEX idx_visit_previous ON visit_packages(previous_visit_id);
