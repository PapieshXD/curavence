-- Transactional, recipient-scoped invalidations. No clinical payloads.
CREATE TABLE realtime_events (
    id BIGSERIAL PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES users(id),
    event_type VARCHAR(80) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_realtime_recipient_cursor ON realtime_events(recipient_id, id);

CREATE TABLE revoked_sessions (
    subject VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(subject, session_id)
);

CREATE FUNCTION curavence_emit(recipient UUID, kind TEXT, resource TEXT, resource_uuid UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF recipient IS NULL THEN RETURN; END IF;
    -- nextval alone is NOT commit ordered: serialize allocation until commit.
    PERFORM pg_advisory_xact_lock(731946281);
    INSERT INTO realtime_events(recipient_id,event_type,resource_type,resource_id)
    VALUES(recipient,kind,resource,resource_uuid);
END;
$$;

CREATE FUNCTION curavence_patient_event(patient UUID, kind TEXT, resource TEXT, resource_uuid UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE target UUID;
BEGIN
    SELECT user_id INTO target FROM patients WHERE id=patient;
    PERFORM curavence_emit(target,kind,resource,resource_uuid);
    FOR target IN SELECT DISTINCT recipient_user_id FROM access_grants
        WHERE patient_id=patient AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
    LOOP
        -- A grant to one study must not reveal identifiers of other studies.
        PERFORM curavence_emit(target,'workspace.changed','workspace',target);
    END LOOP;
END;
$$;

CREATE FUNCTION curavence_study_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE kind TEXT := 'study.updated';
BEGIN
    IF TG_OP='DELETE' THEN
        PERFORM curavence_patient_event(OLD.patient_id,'study.deleted','study',OLD.id);
        RETURN OLD;
    END IF;
    IF TG_OP='INSERT' THEN kind := 'study.created';
    ELSIF NEW.archived_at IS DISTINCT FROM OLD.archived_at AND NEW.archived_at IS NOT NULL THEN kind := 'study.deleted';
    ELSIF NEW.ingestion_status IS DISTINCT FROM OLD.ingestion_status THEN
        kind := CASE NEW.ingestion_status WHEN 'PROCESSING' THEN 'study.processing.started'
          WHEN 'READY' THEN 'study.processing.completed' WHEN 'FAILED' THEN 'study.processing.failed' ELSE 'study.updated' END;
    END IF;
    PERFORM curavence_patient_event(NEW.patient_id,kind,'study',NEW.id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER studies_realtime AFTER INSERT OR UPDATE OR DELETE ON studies
FOR EACH ROW EXECUTE FUNCTION curavence_study_event();

CREATE FUNCTION curavence_share_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE row_data access_grants; kind TEXT; owner_id UUID;
BEGIN
    IF TG_OP='DELETE' THEN row_data:=OLD; ELSE row_data:=NEW; END IF;
    kind := CASE WHEN TG_OP='DELETE' OR row_data.status IN ('REVOKED','EXPIRED') THEN 'share.revoked' ELSE 'share.created' END;
    SELECT user_id INTO owner_id FROM patients WHERE id=row_data.patient_id;
    PERFORM curavence_emit(owner_id,kind,'share',row_data.id);
    PERFORM curavence_emit(row_data.recipient_user_id,kind,'share',row_data.id);
    RETURN row_data;
END;
$$;
CREATE TRIGGER grants_realtime AFTER INSERT OR UPDATE OR DELETE ON access_grants
FOR EACH ROW EXECUTE FUNCTION curavence_share_event();

CREATE FUNCTION curavence_grant_study_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE grant_uuid UUID; grant_row access_grants;
BEGIN
    IF TG_OP='DELETE' THEN grant_uuid:=OLD.grant_id; ELSE grant_uuid:=NEW.grant_id; END IF;
    SELECT * INTO grant_row FROM access_grants WHERE id=grant_uuid;
    IF FOUND THEN
        PERFORM curavence_emit(grant_row.recipient_user_id,'workspace.changed','workspace',grant_row.recipient_user_id);
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER grant_studies_realtime AFTER INSERT OR UPDATE OR DELETE ON access_grant_studies
FOR EACH ROW EXECUTE FUNCTION curavence_grant_study_event();

CREATE FUNCTION curavence_analysis_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE patient UUID;
BEGIN
    SELECT patient_id INTO patient FROM studies WHERE id=NEW.study_id;
    PERFORM curavence_patient_event(patient,
      CASE WHEN NEW.status IN ('SUCCEEDED','DEGRADED') THEN 'analysis.completed' ELSE 'study.updated' END,
      'study',NEW.study_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER analyses_realtime AFTER INSERT OR UPDATE ON imaging_analyses
FOR EACH ROW EXECUTE FUNCTION curavence_analysis_event();

CREATE FUNCTION curavence_imaging_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM curavence_patient_event(NEW.patient_id,
      CASE NEW.status WHEN 'AVAILABLE' THEN 'study.processing.completed' WHEN 'FAILED' THEN 'study.processing.failed'
      ELSE 'study.processing.started' END,'study',NEW.id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER imaging_realtime AFTER INSERT OR UPDATE ON imaging_studies
FOR EACH ROW EXECUTE FUNCTION curavence_imaging_event();

CREATE FUNCTION curavence_review_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE patient UUID;
BEGIN
    SELECT patient_id INTO patient FROM studies WHERE id=NEW.study_id;
    PERFORM curavence_patient_event(patient,'study.updated','study',NEW.study_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER reviews_realtime AFTER INSERT OR UPDATE ON clinical_reviews
FOR EACH ROW EXECUTE FUNCTION curavence_review_event();
