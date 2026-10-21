package bank.engagement.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes an audit event to the outbox in the caller's transaction, so the
 * event and the change it records are saved together (ADR-0051 decision 7).
 *
 * <p>{@code data} holds IDs, statuses and changed field names only: never a
 * title, description, email body or address (agent-engagement plan §11).
 */
@Component
public class AuditTrail {

    /** The actor for what the dispatcher does on its own. */
    public static final String SYSTEM = "system";

    static final String ACTOR_KEY = "actorId";

    private final AuditOutboxRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuditTrail(AuditOutboxRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void record(AuditAction action, String actorId, Map<String, ?> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put(ACTOR_KEY, Objects.requireNonNull(actorId));
        try {
            repository.save(new AuditOutbox(UUID.randomUUID(), action.outboxType(), 1,
                    mapper.writeValueAsString(payload), clock.instant()));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("audit data is not serialisable", ex);
        }
    }
}
