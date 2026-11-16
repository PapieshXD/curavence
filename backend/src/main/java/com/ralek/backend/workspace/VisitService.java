package com.ralek.backend.workspace;

import com.ralek.backend.audit.AuditService;
import com.ralek.backend.workspace.UserContextService.UserContext;
import com.ralek.backend.workspace.VisitController.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Types;
import java.time.*;
import java.util.*;

@Service
public class VisitService {
    private final JdbcClient jdbc;
    private final UserContextService contexts;
    private final WorkspaceService workspace;
    private final AuditService audit;
    public VisitService(JdbcClient jdbc, UserContextService contexts, WorkspaceService workspace, AuditService audit) {
        this.jdbc = jdbc; this.contexts = contexts; this.workspace = workspace; this.audit = audit;
    }
    public record Action(UUID id, String description, LocalDate dueDate, String responsible, boolean completed,
        String patientStatus, LocalDate performedOn, String patientNote, String obstacle,
        UUID reportedBy, Instant reportedAt, UUID discussedBy, Instant discussedAt, long revision,
        List<UUID> studyIds, String reportedName, String discussedName) {}
    public record Visit(UUID id, UUID patientId, String patientName, String reason, String symptoms, String questions,
        LocalDate visitDate, UUID grantId, String accessStatus, String recipientEmail, Instant expiresAt,
        String summary, Instant approvedAt, String approvedBy, Instant createdAt,
        List<UUID> studyIds, List<Action> actions, UUID previousVisitId, boolean includePreviousPlan, PreviousPlan previousPlan, List<UUID> newStudyIds) {}
    public record PreviousPlan(UUID id, String summary, Instant approvedAt, String approvedBy, List<Action> actions) {}
    private static final String ACTIVE = "ag.status = 'ACTIVE' AND (ag.expires_at IS NULL OR ag.expires_at > CURRENT_TIMESTAMP)";

    public List<Visit> list(Jwt jwt, String role) {
        UserContext actor = contexts.resolve(jwt, role);
        if (!actor.patientView() && !actor.effectiveView().equals("doctor")) return List.of();
        String condition = actor.patientView() ? "v.patient_id = :actor" : "ag.recipient_user_id = :actor AND " + ACTIVE;
        var visits = jdbc.sql("""
            SELECT v.*, u.first_name || ' ' || u.last_name AS patient_name,
              CASE WHEN ag.id IS NULL THEN 'DRAFT' WHEN ag.status <> 'ACTIVE' THEN 'REVOKED'
              WHEN ag.expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE 'ACTIVE' END AS access_status,
              recipient.email AS recipient_email, ag.expires_at,
              signer.first_name || ' ' || signer.last_name AS approved_name
            FROM visit_packages v JOIN patients p ON p.id = v.patient_id JOIN users u ON u.id = p.user_id
            LEFT JOIN access_grants ag ON ag.id = v.grant_id
            LEFT JOIN users recipient ON recipient.id = ag.recipient_user_id
            LEFT JOIN users signer ON signer.id = v.approved_by
            WHERE
            """ + condition + " ORDER BY v.created_at DESC")
            .param("actor", actor.patientView() ? actor.patientId() : actor.id(), Types.OTHER)
            .query((rs, n) -> {
                UUID id = rs.getObject("id", UUID.class);
                Instant approved = rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant();
                boolean visible = !actor.patientView() || approved != null;
                return new Visit(id, rs.getObject("patient_id", UUID.class), rs.getString("patient_name"),
                    rs.getString("reason"), rs.getString("symptoms"), rs.getString("questions"), rs.getObject("visit_date", LocalDate.class),
                    rs.getObject("grant_id", UUID.class), rs.getString("access_status"), rs.getString("recipient_email"),
                    rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                    visible ? rs.getString("summary") : "", approved, rs.getString("approved_name"),
                    rs.getTimestamp("created_at").toInstant(), visibleStudies(id, actor), visible ? actions(id, actor, id) : List.of(),
                    actor.patientView() || rs.getBoolean("include_previous_plan") ? rs.getObject("previous_visit_id", UUID.class) : null,
                    rs.getBoolean("include_previous_plan"), previousPlan(rs.getObject("previous_visit_id", UUID.class),
                        actor.patientView() || rs.getBoolean("include_previous_plan"), actor, id),
                    newStudies(id,rs.getObject("previous_visit_id",UUID.class),actor,actor.patientView() || rs.getBoolean("include_previous_plan")));
            }).list();
        for (Visit visit : visits) audit.record(actor.id(), visit.patientId(), "VISIT", visit.id(), "VIEW", true, null, Map.of());
        return visits;
    }
    private List<UUID> newStudies(UUID current, UUID previous, UserContext actor, boolean included) {
        if(previous==null || !included) return List.of();
        var old=new HashSet<>(studies(previous));
        return visibleStudies(current,actor).stream().filter(id->!old.contains(id)).toList();
    }
    private List<UUID> studies(UUID id) {
        return jdbc.sql("SELECT study_id FROM visit_studies WHERE visit_id = :id ORDER BY study_id")
            .param("id", id).query(UUID.class).list();
    }
    private List<Action> actions(UUID id, UserContext actor, UUID contextId) {
        Set<UUID> permitted=actor.patientView() ? Set.of() : new HashSet<>(visibleStudies(contextId,actor));
        Map<UUID,List<UUID>> evidence=new HashMap<>();
        for(var row:jdbc.sql("SELECT s.action_id,s.study_id FROM visit_action_studies s JOIN visit_actions a ON a.id=s.action_id WHERE a.visit_id=:id ORDER BY s.study_id")
            .param("id",id).query((rs,n)->new UUID[]{rs.getObject("action_id",UUID.class),rs.getObject("study_id",UUID.class)}).list()) {
            if(actor.patientView() || permitted.contains(row[1])) evidence.computeIfAbsent(row[0],key->new ArrayList<>()).add(row[1]);
        }
        return jdbc.sql("SELECT a.*, r.first_name || ' ' || r.last_name AS reported_name, d.first_name || ' ' || d.last_name AS discussed_name FROM visit_actions a LEFT JOIN users r ON r.id=a.reported_by LEFT JOIN users d ON d.id=a.discussed_by WHERE a.visit_id = :id ORDER BY a.position")
            .param("id", id).query((rs, n) -> new Action(rs.getObject("id", UUID.class), rs.getString("description"),
                rs.getObject("due_date", LocalDate.class), rs.getString("responsible"), rs.getBoolean("completed"),
                rs.getString("patient_status"), rs.getObject("performed_on", LocalDate.class), rs.getString("patient_note"),
                rs.getString("obstacle"), rs.getObject("reported_by", UUID.class), instant(rs, "reported_at"),
                rs.getObject("discussed_by", UUID.class), instant(rs, "discussed_at"), rs.getLong("revision"),
                evidence.getOrDefault(rs.getObject("id",UUID.class),List.of()), rs.getString("reported_name"), rs.getString("discussed_name"))).list();
    }
    private record Locked(UUID patientId, UUID grantId, Instant approvedAt) {}
    private Locked lock(UUID id) {
        return jdbc.sql("SELECT patient_id, grant_id, approved_at FROM visit_packages WHERE id = :id FOR UPDATE")
            .param("id", id).query((rs, n) -> new Locked(rs.getObject("patient_id", UUID.class),
                rs.getObject("grant_id", UUID.class), rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant()))
            .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private void owner(UserContext actor, Locked visit) {
        if (!actor.patientView() || actor.patientId() == null || !actor.patientId().equals(visit.patientId())) forbidden();
    }
    private void forbidden() { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do wizyty"); }
    private void editable(Locked visit) {
        if (visit.grantId() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Udostępniony pakiet jest zamknięty. Przygotuj nowy pakiet, aby zmienić zakres.");
    }

    @Transactional
    public UUID save(Jwt jwt, String role, UUID id, PackageInput input) {
        UserContext actor = contexts.resolve(jwt, role);
        if (!actor.patientView() || actor.patientId() == null) forbidden();
        if (id != null) { var visit = lock(id); owner(actor, visit); editable(visit); }
        for (UUID study : new HashSet<>(input.studyIds())) {
            if (jdbc.sql("SELECT count(*) FROM studies WHERE id = :id AND patient_id = :patient AND archived_at IS NULL")
                .param("id", study).param("patient", actor.patientId()).query(Integer.class).single() != 1) forbidden();
        }
        if (input.previousVisitId() != null && (input.previousVisitId().equals(id) || jdbc.sql(
            "SELECT count(*) FROM visit_packages WHERE id=:id AND patient_id=:patient AND approved_at IS NOT NULL")
            .param("id", input.previousVisitId()).param("patient", actor.patientId()).query(Integer.class).single() != 1)) forbidden();
        if (input.includePreviousPlan() && input.previousVisitId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wybierz poprzednią wizytę");
        boolean create = id == null;
        if (create) id = UUID.randomUUID();
        jdbc.sql(create ? """
            INSERT INTO visit_packages(id, patient_id, reason, symptoms, questions, visit_date)
            VALUES (:id, :patient, :reason, :symptoms, :questions, :date)
            """ : """
            UPDATE visit_packages SET reason=:reason, symptoms=:symptoms, questions=:questions,
              visit_date=:date, updated_at=CURRENT_TIMESTAMP WHERE id=:id AND patient_id=:patient
            """)
            .param("id", id).param("patient", actor.patientId()).param("reason", input.reason().trim())
            .param("symptoms", input.symptoms()).param("questions", input.questions())
            .param("date", input.visitDate(), Types.DATE).update();
        jdbc.sql("UPDATE visit_packages SET previous_visit_id=:previous, include_previous_plan=:include WHERE id=:id")
            .param("previous", input.previousVisitId(), Types.OTHER).param("include", input.includePreviousPlan()).param("id", id).update();
        jdbc.sql("DELETE FROM visit_studies WHERE visit_id=:id").param("id", id).update();
        for (UUID study : new HashSet<>(input.studyIds())) jdbc.sql("INSERT INTO visit_studies VALUES (:id, :study)")
            .param("id", id).param("study", study).update();
        audit.record(actor.id(), actor.patientId(), "VISIT", id, create ? "CREATE" : "UPDATE", true, null, Map.of());
        return id;
    }

    @Transactional
    public void share(Jwt jwt, String role, UUID id, ShareInput input) {
        UserContext actor = contexts.resolve(jwt, role);
        Locked visit = lock(id); owner(actor, visit); editable(visit);
        if (jdbc.sql("""
            SELECT count(*) FROM users u JOIN practitioners p ON p.user_id=u.id
            WHERE lower(u.email)=lower(:email) AND u.status='ACTIVE' AND p.professional_type='DOCTOR'
            """).param("email", input.recipientEmail()).query(Integer.class).single() != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wybierz aktywne konto lekarza");
        UUID grant = workspace.createVisitGrant(jwt, role, new WorkspaceDtos.CreateGrantRequest(input.recipientEmail(),
            "SELECTED_STUDIES", "Pakiet przed wizytą " + id, input.expiresAt(), studies(id)));
        jdbc.sql("UPDATE visit_packages SET grant_id=:grant, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("grant", grant).param("id", id).update();
        audit.record(actor.id(), visit.patientId(), "VISIT", id, "SHARE", true, null, Map.of("grantId", grant));
    }

    @Transactional
    public void plan(Jwt jwt, String role, UUID id, PlanInput input) {
        UserContext actor = contexts.resolve(jwt, role);
        Locked visit = lock(id);
        if (!actor.effectiveView().equals("doctor") || !actor.roles().contains("DOCTOR")) forbidden();
        if (jdbc.sql("SELECT count(*) FROM access_grants ag WHERE ag.id=:grant AND ag.recipient_user_id=:actor AND " + ACTIVE)
            .param("grant", visit.grantId(), Types.OTHER).param("actor", actor.id()).query(Integer.class).single() != 1) forbidden();
        if (visit.approvedAt() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Plan został już zatwierdzony");
        jdbc.sql("UPDATE visit_packages SET summary=:summary, approved_by=:actor, approved_at=:approved, updated_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("summary", input.summary()).param("actor", input.approved() ? actor.id() : null, Types.OTHER)
            .param("approved", input.approved() ? OffsetDateTime.now(ZoneOffset.UTC) : null, Types.TIMESTAMP_WITH_TIMEZONE)
            .param("id", id).update();
        jdbc.sql("DELETE FROM visit_actions WHERE visit_id=:id").param("id", id).update();
        int position = 0;
        for (ActionInput action : input.actions()) jdbc.sql("""
            INSERT INTO visit_actions(id, visit_id, position, description, due_date, responsible)
            VALUES (:id, :visit, :position, :description, :date, :responsible)
            """).param("id", UUID.randomUUID()).param("visit", id).param("position", position++)
            .param("description", action.description()).param("date", action.dueDate())
            .param("responsible", action.responsible()).update();
        if (input.approved()) notifyPatient(visit.patientId(), id, "PLAN_APPROVED", "plan:" + id);
        audit.record(actor.id(), visit.patientId(), "VISIT", id, input.approved() ? "SIGN" : "UPDATE", true, null, Map.of());
    }

    @Transactional
    public void complete(Jwt jwt, String role, UUID id, UUID actionId, boolean completed) {
        UserContext actor = contexts.resolve(jwt, role);
        Locked visit = lock(id); owner(actor, visit);
        Action action = actions(id, actor, id).stream().filter(a -> a.id().equals(actionId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        progress(jwt, role, id, actionId, new ProgressInput(completed ? "DONE" : "TODO", action.performedOn(),
            action.patientNote(), null, action.studyIds(), action.revision()));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {

        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();

    }

    private List<UUID> visibleStudies(UUID id, UserContext actor) {

        return jdbc.sql("""

            SELECT vs.study_id FROM visit_studies vs JOIN studies s ON s.id=vs.study_id

            WHERE vs.visit_id=:id AND s.archived_at IS NULL

            """ + (actor.patientView() ? "" : """

            AND EXISTS (SELECT 1 FROM visit_packages v JOIN access_grants ag ON ag.id=v.grant_id

              JOIN access_grant_studies gs ON gs.grant_id=ag.id AND gs.study_id=vs.study_id

              WHERE v.id=:id AND ag.recipient_user_id=:actor AND

            """ + ACTIVE + ")"))

            .param("id", id).param("actor", actor.id()).query(UUID.class).list();

    }

    private PreviousPlan previousPlan(UUID previous, boolean included, UserContext actor, UUID context) {

        if (previous == null || !included) return null;

        return jdbc.sql("""

            SELECT v.id,v.summary,v.approved_at,u.first_name || ' ' || u.last_name AS author

            FROM visit_packages v LEFT JOIN users u ON u.id=v.approved_by

            WHERE v.id=:id AND v.approved_at IS NOT NULL

            """).param("id", previous).query((rs,n) -> new PreviousPlan(previous, rs.getString("summary"),

                instant(rs,"approved_at"),rs.getString("author"),actions(previous,actor,context))).optional().orElse(null);

    }

    private void doctor(UserContext actor, Locked visit) {

        if (!actor.effectiveView().equals("doctor") || !actor.roles().contains("DOCTOR")) forbidden();

        if (jdbc.sql("SELECT count(*) FROM access_grants ag WHERE ag.id=:grant AND ag.recipient_user_id=:actor AND " + ACTIVE)

            .param("grant",visit.grantId(),Types.OTHER).param("actor",actor.id()).query(Integer.class).single()!=1) forbidden();

    }

    private void validateStudies(UUID patient, List<UUID> ids) {

        for (UUID id : new HashSet<>(ids)) {

            if (jdbc.sql("SELECT count(*) FROM studies WHERE id=:id AND patient_id=:patient AND archived_at IS NULL")

                .param("id",id).param("patient",patient).query(Integer.class).single()!=1) forbidden();

        }

    }

    private void approved(Locked visit) {

        if(visit.approvedAt()==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Plan wymaga zatwierdzenia lekarza");

    }

    private void conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT,"Dane zmieniły się. Odśwież widok przed zapisem."); }



    @Transactional

    public void progress(Jwt jwt, String role, UUID id, UUID actionId, ProgressInput input) {

        UserContext actor=contexts.resolve(jwt,role);

        Locked visit=lock(id); owner(actor,visit); approved(visit);

        if (!Set.of("TODO","DONE","DIFFICULTY").contains(input.status()) ||

            (input.status().equals("DIFFICULTY") && (input.obstacle()==null ||

             !Set.of("NO_APPOINTMENT","COST","UNCLEAR","OTHER").contains(input.obstacle()))))

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Wybierz przyczynę trudności");

        if(input.performedOn()!=null && input.performedOn().isAfter(warsawDate(Instant.now())))

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Data wykonania nie może być przyszła");

        validateStudies(visit.patientId(),input.studyIds());

        if(jdbc.sql("""

            UPDATE visit_actions SET patient_status=:status,completed=:done,performed_on=:date,patient_note=:note,

              obstacle=:obstacle,reported_by=:actor,reported_at=CURRENT_TIMESTAMP,

              discussed_by=NULL,discussed_at=NULL,revision=revision+1

            WHERE id=:action AND visit_id=:visit AND revision=:revision

            """).param("status",input.status()).param("done",input.status().equals("DONE"))

            .param("date",input.status().equals("DONE") ? input.performedOn() : null,Types.DATE)

            .param("note",input.note()).param("obstacle",input.status().equals("DIFFICULTY") ? input.obstacle() : null,Types.VARCHAR)

            .param("actor",actor.id()).param("action",actionId).param("visit",id).param("revision",input.revision()).update()!=1) conflict();

        jdbc.sql("DELETE FROM visit_action_studies WHERE action_id=:id").param("id",actionId).update();

        for(UUID study:new HashSet<>(input.studyIds())) jdbc.sql("INSERT INTO visit_action_studies(action_id,study_id) VALUES (:action,:study)")

            .param("action",actionId).param("study",study).update();

        // New evidence remains private until the patient explicitly adds it to a package's scope.

        notifyDoctors(id, actionId, input.status().equals("DIFFICULTY") ? "OBSTACLE" : "PROGRESS", input.revision()+1);

        audit.record(actor.id(),visit.patientId(),"VISIT",id,"UPDATE",true,null,Map.of("actionId",actionId));

    }



    @Transactional

    public void discuss(Jwt jwt,String role,UUID contextId,UUID actionId,DiscussionInput input) {

        UserContext actor=contexts.resolve(jwt,role); Locked context=lock(contextId); doctor(actor,context);

        // The endpoint is scoped to the shared package, never to arbitrary patient actions.

        if(jdbc.sql("""

            SELECT count(*) FROM visit_actions a JOIN visit_packages v ON v.id=:context

            JOIN visit_packages source ON source.id=a.visit_id

            WHERE a.id=:action AND source.approved_at IS NOT NULL AND source.patient_id=v.patient_id

              AND (a.visit_id=v.id OR (v.include_previous_plan=TRUE AND a.visit_id=v.previous_visit_id))

            """).param("context",contextId).param("action",actionId).query(Integer.class).single()!=1) forbidden();

        if(jdbc.sql("""

            UPDATE visit_actions SET discussed_by=:actor,discussed_at=:date,revision=revision+1

            WHERE id=:action AND revision=:revision

            """).param("actor",input.discussed()?actor.id():null,Types.OTHER)

            .param("date",input.discussed()?OffsetDateTime.now():null,Types.TIMESTAMP_WITH_TIMEZONE)

            .param("action",actionId).param("revision",input.revision()).update()!=1) conflict();

        audit.record(actor.id(),context.patientId(),"VISIT",contextId,"UPDATE",true,null,Map.of("actionId",actionId,"discussed",input.discussed()));

    }



    @Transactional

    public void extend(Jwt jwt,String role,UUID id,ExtendInput input) {

        UserContext actor=contexts.resolve(jwt,role); Locked visit=lock(id); owner(actor,visit);

        if(!input.confirmed()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Potwierdź zakres");

        if(jdbc.sql("SELECT count(*) FROM access_grants ag WHERE ag.id=:id AND "+ACTIVE)

            .param("id",visit.grantId(),Types.OTHER).query(Integer.class).single()!=1) forbidden();

        validateStudies(visit.patientId(),input.studyIds());

        for(UUID study:new HashSet<>(input.studyIds())) {

            jdbc.sql("INSERT INTO visit_studies(visit_id,study_id) SELECT :visit,:study WHERE NOT EXISTS (SELECT 1 FROM visit_studies WHERE visit_id=:visit AND study_id=:study)")

                .param("visit",id).param("study",study).update();

            jdbc.sql("INSERT INTO access_grant_studies(grant_id,study_id) SELECT :grant,:study WHERE NOT EXISTS (SELECT 1 FROM access_grant_studies WHERE grant_id=:grant AND study_id=:study)")

                .param("grant",visit.grantId()).param("study",study).update();

        }

        audit.record(actor.id(),visit.patientId(),"VISIT",id,"SHARE",true,null,Map.of("studyIds",input.studyIds()));

    }



    public record Notification(UUID id,UUID visitId,String kind,Instant createdAt,Instant readAt) {}

    private void notification(UUID recipient,UUID visit,String kind,String key) {

        jdbc.sql("""

            INSERT INTO visit_notifications(id,recipient_id,visit_id,kind,dedup_key)

            VALUES (:id,:recipient,:visit,:kind,:key) ON CONFLICT DO NOTHING

            """).param("id",UUID.randomUUID()).param("recipient",recipient).param("visit",visit)

            .param("kind",kind).param("key",key).update();

    }

    private void notifyPatient(UUID patient,UUID visit,String kind,String key) {

        notification(jdbc.sql("SELECT user_id FROM patients WHERE id=:id").param("id",patient).query(UUID.class).single(),visit,kind,key);

    }

    private void notifyDoctors(UUID source,UUID action,String kind,long revision) {

        var recipients=jdbc.sql("""

            SELECT v.id,ag.recipient_user_id FROM visit_packages v JOIN access_grants ag ON ag.id=v.grant_id

            WHERE (v.id=:source OR (v.previous_visit_id=:source AND v.include_previous_plan=TRUE)) AND

            """+ACTIVE).param("source",source).query((rs,n)->new UUID[]{rs.getObject("id",UUID.class),rs.getObject("recipient_user_id",UUID.class)}).list();

        for(var recipient:recipients) notification(recipient[1],recipient[0],kind,"progress:"+action+":"+revision+":"+recipient[0]);

    }

    public List<Notification> notifications(Jwt jwt,String role) {

        UserContext actor=contexts.resolve(jwt,role);

        if(!actor.patientView() && !actor.effectiveView().equals("doctor")) return List.of();

        String visibility=actor.patientView() ? "v.patient_id=:patient" : "ag.recipient_user_id=:actor AND "+ACTIVE;

        return jdbc.sql("""

            SELECT n.* FROM visit_notifications n JOIN visit_packages v ON v.id=n.visit_id

            LEFT JOIN access_grants ag ON ag.id=v.grant_id WHERE n.recipient_id=:actor AND

            """+visibility+" ORDER BY n.created_at DESC LIMIT 100")

            .param("actor",actor.id()).param("patient",actor.patientId(),Types.OTHER)

            .query((rs,n)->new Notification(rs.getObject("id",UUID.class),rs.getObject("visit_id",UUID.class),

                rs.getString("kind"),instant(rs,"created_at"),instant(rs,"read_at"))).list();

    }

    @Transactional

    public void readNotification(Jwt jwt,String role,UUID id) {

        UserContext actor=contexts.resolve(jwt,role);

        if(notifications(jwt,role).stream().noneMatch(n->n.id().equals(id))) forbidden();

        jdbc.sql("UPDATE visit_notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE id=:id AND recipient_id=:actor")

            .param("id",id).param("actor",actor.id()).update();

    }

    static LocalDate warsawDate(Instant instant) { return instant.atZone(ZoneId.of("Europe/Warsaw")).toLocalDate(); }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)

    @Transactional

    public void dueReminders() {

        LocalDate today=warsawDate(Instant.now());

        var rows=jdbc.sql("""

            SELECT a.id,a.visit_id,a.due_date,v.patient_id FROM visit_actions a JOIN visit_packages v ON v.id=a.visit_id

            WHERE v.approved_at IS NOT NULL AND a.patient_status<>'DONE' AND a.due_date BETWEEN :today AND :tomorrow

            """).param("today",today).param("tomorrow",today.plusDays(1))

            .query((rs,n)->new Object[]{rs.getObject("id",UUID.class),rs.getObject("visit_id",UUID.class),rs.getObject("patient_id",UUID.class)}).list();

        for(var row:rows) notifyPatient((UUID)row[2],(UUID)row[1],"DUE","due:"+row[0]+":"+today);

    }

}

