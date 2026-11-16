-- Functional recordings and ultrasound reports retain their original PDF presentation.
-- DICOM image availability is determined by a linked imaging_studies record, not the type name.
INSERT INTO study_types (id, code, name, domain, default_viewer) VALUES
('70000000-0000-0000-0000-000000000011', 'EKG', 'EKG', 'FUNCTIONAL', 'PDF'),
('70000000-0000-0000-0000-000000000012', 'EEG', 'EEG', 'FUNCTIONAL', 'PDF')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, domain = EXCLUDED.domain, default_viewer = EXCLUDED.default_viewer;

INSERT INTO study_types (id, code, name, domain, default_viewer)
VALUES ('70000000-0000-0000-0000-000000000009', 'ULTRASOUND', 'USG', 'IMAGING', 'IMAGE')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name;
