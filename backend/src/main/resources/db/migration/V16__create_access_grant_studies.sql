CREATE TABLE access_grant_studies (
    grant_id UUID NOT NULL REFERENCES access_grants(id) ON DELETE CASCADE,
    study_id UUID NOT NULL REFERENCES studies(id),
    PRIMARY KEY (grant_id, study_id)
);
