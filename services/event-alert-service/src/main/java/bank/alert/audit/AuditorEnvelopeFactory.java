package bank.alert.audit;

import bank.alert.domain.AuditOutbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class AuditorEnvelopeFactory {

    private final ObjectMapper mapper;

    public AuditorEnvelopeFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuditorEnvelope create(AuditOutbox event) {
        try {
            JsonNode originalPayload = mapper.readTree(event.getPayload());
            ObjectNode payload = mapper.createObjectNode();
            payload.put("event_id", event.getEventId().toString());
            payload.put("event_version", event.getEventVersion());
            payload.put("occurred_at", event.getCreatedAt().toString());
            payload.set("data", originalPayload);
            String actor = originalPayload.path("actorSubject").asText("event-alert-service");
            return new AuditorEnvelope("EVENT_ALERT", action(event.getEventType()),
                    "EVENT_ALERT_SERVICE", actor, payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("audit outbox payload is not valid JSON", exception);
        }
    }

    private static String action(String eventType) {
        if (eventType == null || !eventType.startsWith("alert.")) {
            throw new IllegalArgumentException("unsupported alert audit event type: " + eventType);
        }
        return eventType.substring("alert.".length())
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }
}
