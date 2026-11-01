package bank.alert.worker;

import bank.alert.config.ReplayProperties;
import bank.alert.domain.AuditOutbox;
import bank.alert.operations.OperationalControlAuditUnavailableException;
import bank.alert.repository.AuditOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes the audit event for a replay before any search runs. If the outbox is
 * full or unavailable the replay is refused: an unaudited replay never starts.
 */
@Component
public class ReplayRequestAudit {
    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "FAILED");

    private final AuditOutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final int maxPendingAuditEvents;

    public ReplayRequestAudit(AuditOutboxRepository outbox, ObjectMapper objectMapper,
            @Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    public void record(ReplayProperties replay, Instant occurredAt) {
        try {
            if (outbox.countByStatusIn(BACKLOG_STATUSES) >= maxPendingAuditEvents) {
                throw new OperationalControlAuditUnavailableException("audit outbox is at capacity");
            }
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("action", "REPLAY_REQUESTED");
            payload.put("actorSubject", replay.operator());
            payload.put("actorRole", "OPERATOR");
            payload.put("correlationId", replay.correlationId());
            payload.put("windowFrom", replay.windowFrom().toString());
            payload.put("windowTo", replay.windowTo().toString());
            payload.put("occurredAt", occurredAt.toString());
            outbox.saveAndFlush(new AuditOutbox(UUID.randomUUID(), "alert.replay-requested", 1,
                    payload.toString(), occurredAt));
        } catch (DataAccessException exception) {
            throw new OperationalControlAuditUnavailableException(
                    "durable replay audit persistence is unavailable", exception);
        }
    }
}
