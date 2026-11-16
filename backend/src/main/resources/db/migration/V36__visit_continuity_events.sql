CREATE FUNCTION curavence_visit_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE visit_uuid UUID; patient_uuid UUID; target RECORD;
BEGIN
    IF TG_TABLE_NAME='visit_packages' THEN visit_uuid:=NEW.id;
    ELSIF TG_TABLE_NAME='visit_notifications' THEN
        PERFORM curavence_emit(NEW.recipient_id,'visits.changed','workspace',NEW.recipient_id);
        RETURN NEW;
    ELSE visit_uuid:=NEW.visit_id;
    END IF;
    SELECT patient_id INTO patient_uuid FROM visit_packages WHERE id=visit_uuid;
    PERFORM curavence_patient_event(patient_uuid,'visits.changed','workspace',patient_uuid);
    FOR target IN SELECT DISTINCT ag.recipient_user_id FROM visit_packages v
        JOIN access_grants ag ON ag.id=v.grant_id
        WHERE (v.id=visit_uuid OR (v.previous_visit_id=visit_uuid AND v.include_previous_plan=TRUE))
          AND ag.status='ACTIVE' AND (ag.expires_at IS NULL OR ag.expires_at>CURRENT_TIMESTAMP)
    LOOP
        PERFORM curavence_emit(target.recipient_user_id,'visits.changed','workspace',target.recipient_user_id);
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER visit_packages_realtime AFTER INSERT OR UPDATE ON visit_packages
FOR EACH ROW EXECUTE FUNCTION curavence_visit_event();
CREATE TRIGGER visit_actions_realtime AFTER INSERT OR UPDATE ON visit_actions
FOR EACH ROW EXECUTE FUNCTION curavence_visit_event();
CREATE TRIGGER visit_notifications_realtime AFTER INSERT ON visit_notifications
FOR EACH ROW EXECUTE FUNCTION curavence_visit_event();
