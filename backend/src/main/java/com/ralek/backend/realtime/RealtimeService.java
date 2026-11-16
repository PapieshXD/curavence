package com.ralek.backend.realtime;

import com.ralek.backend.workspace.UserContextService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static org.springframework.http.HttpStatus.*;

@Service
public class RealtimeService {
    private final JdbcClient jdbc;
    private final UserContextService contexts;
    private final ConcurrentHashMap<UUID, Connection> connections = new ConcurrentHashMap<>();

    public RealtimeService(JdbcClient jdbc, UserContextService contexts) {
        this.jdbc = jdbc; this.contexts = contexts;
    }
    public record Event(long id, String type, String resource, UUID resourceId, long version) {}
    public record Changes(List<Event> events, long cursor, boolean hasMore) {}

    public Changes changes(Jwt jwt, String role, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 200) throw new ResponseStatusException(BAD_REQUEST);
        return read(contexts.resolve(jwt, role).id(), after, limit);
    }
    Changes read(UUID user, long after, int limit) {
        List<Event> rows = jdbc.sql("""
            SELECT id,event_type,resource_type,resource_id FROM realtime_events
            WHERE recipient_id=:user AND id>:after ORDER BY id LIMIT :limit
            """).param("user",user).param("after",after).param("limit",limit+1)
            .query((rs,n)->new Event(rs.getLong("id"),rs.getString("event_type"),rs.getString("resource_type"),
                rs.getObject("resource_id",UUID.class),rs.getLong("id"))).list();
        boolean more=rows.size()>limit;
        List<Event> events=more ? rows.subList(0,limit) : rows;
        return new Changes(events,events.isEmpty()?after:events.getLast().id(),more);
    }
    public SseEmitter connect(Jwt jwt, String role, long after) {
        if(after<0) throw new ResponseStatusException(BAD_REQUEST);
        UUID user=contexts.resolve(jwt,role).id();
        if (connections.size() >= 2000 || connections.values().stream().filter(c->c.user.equals(user)).count()>=8)
            throw new ResponseStatusException(TOO_MANY_REQUESTS);
        UUID id=UUID.randomUUID();
        // Bound lifetime forces JWT revalidation and refresh; events survive this transport.
        SseEmitter emitter=new SseEmitter(55_000L);
        Connection connection=new Connection(user,jwt,emitter,after);
        emitter.onCompletion(()->connections.remove(id));
        emitter.onTimeout(()->{connections.remove(id);emitter.complete();});
        emitter.onError(error->connections.remove(id));
        connections.put(id,connection);
        try { emitter.send(SseEmitter.event().comment("connected").reconnectTime(1500)); }
        catch(Exception error) {connections.remove(id);emitter.completeWithError(error);}
        return emitter;
    }
    public void dispatch() {
        // Expiry is also an invalidation; reads already check the timestamp themselves.
        jdbc.sql("UPDATE access_grants SET status='EXPIRED' WHERE status='ACTIVE' AND expires_at<=CURRENT_TIMESTAMP").update();
        connections.forEach((id,c)->{
            try {
                if(c.jwt.getExpiresAt()==null || !c.jwt.getExpiresAt().isAfter(Instant.now()) ||
                    jdbc.sql("SELECT count(*) FROM users WHERE id=:id AND status='ACTIVE'").param("id",c.user).query(Integer.class).single()!=1 ||
                    revoked(c.jwt)) {connections.remove(id);c.emitter.complete();return;}
                Changes changes=read(c.user,c.cursor,100);
                for(Event event:changes.events()) {
                    c.emitter.send(SseEmitter.event().id(Long.toString(event.id())).name("change").data(event));
                    c.cursor=event.id();
                }
                if(++c.ticks%15==0) c.emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch(Exception error) {connections.remove(id);c.emitter.completeWithError(error);}
        });
    }
    private boolean revoked(Jwt jwt) {
        String session=jwt.getClaimAsString("sid");
        if(session==null) session=jwt.getId();
        if(session==null) return false;
        return jdbc.sql("SELECT count(*) FROM revoked_sessions WHERE subject=:subject AND session_id=:session")
            .param("subject",jwt.getSubject()).param("session",session).query(Integer.class).single()>0;
    }
    private static class Connection {
        final UUID user; final Jwt jwt; final SseEmitter emitter; long cursor; int ticks;
        Connection(UUID user,Jwt jwt,SseEmitter emitter,long cursor) {this.user=user;this.jwt=jwt;this.emitter=emitter;this.cursor=cursor;}
    }
}
