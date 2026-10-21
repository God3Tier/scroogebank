package bank.engagement.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Wraps an outbox row in the envelope the auditor validates for
 * {@code ENGAGEMENT}: event type {@code ENGAGEMENT}, and a payload of
 * {@code event_id}, {@code event_version}, {@code occurred_at} and {@code data},
 * the same shape event-alert-service sends.
 */
@Component
public class AuditorEnvelopeFactory {

    static final String SOURCE_SERVICE = "ENGAGEMENT";
    static final String EVENT_TYPE = "ENGAGEMENT";

    private final ObjectMapper mapper;

    public AuditorEnvelopeFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuditorEnvelope create(AuditOutbox event) {
        try {
            ObjectNode data = (ObjectNode) mapper.readTree(event.getPayload());
            String actor = data.path(AuditTrail.ACTOR_KEY).asText(AuditTrail.SYSTEM);
            data.remove(AuditTrail.ACTOR_KEY);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("event_id", event.getEventId().toString());
            payload.put("event_version", event.getEventVersion());
            payload.put("occurred_at", event.getCreatedAt().toString());
            payload.set("data", data);
            return new AuditorEnvelope(event.getEventId().toString(), EVENT_TYPE,
                    AuditAction.fromOutboxType(event.getEventType()).name(), SOURCE_SERVICE, actor, payload);
        } catch (JsonProcessingException | ClassCastException exception) {
            throw new IllegalArgumentException("audit outbox payload is not a JSON object", exception);
        }
    }
}
