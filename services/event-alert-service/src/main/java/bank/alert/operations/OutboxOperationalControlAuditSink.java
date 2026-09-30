package bank.alert.operations;

import bank.alert.domain.AuditOutbox;
import bank.alert.repository.AuditOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OutboxOperationalControlAuditSink implements OperationalControlAuditSink {
    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "FAILED");

    private final AuditOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final int maxPendingAuditEvents;

    public OutboxOperationalControlAuditSink(AuditOutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            @Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    @Override
    @Transactional
    public void record(ControlAuditEvent event) {
        try {
            if (outboxRepository.countByStatusIn(BACKLOG_STATUSES) >= maxPendingAuditEvents) {
                throw new OperationalControlAuditUnavailableException(
                        "audit outbox is at capacity");
            }
            outboxRepository.saveAndFlush(new AuditOutbox(
                    UUID.randomUUID(), "alert.operational-control-changed", 1,
                    payload(event).toString(), event.occurredAt()));
        } catch (DataAccessException exception) {
            throw new OperationalControlAuditUnavailableException(
                    "durable operational-control audit persistence is unavailable", exception);
        }
    }

    private ObjectNode payload(ControlAuditEvent event) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("capability", event.capability().name());
        payload.put("action", event.action());
        payload.put("actorSubject", event.actorSubject());
        payload.put("actorRole", "OPERATOR");
        payload.put("correlationId", event.correlationId());
        if (event.previousReason() != null) {
            payload.put("previousReason", event.previousReason().name());
        }
        payload.put("newState", "ENABLED");
        payload.put("occurredAt", event.occurredAt().toString());
        return payload;
    }
}
