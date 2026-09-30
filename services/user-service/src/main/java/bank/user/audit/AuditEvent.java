package bank.user.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuditEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("action") String action,
        @JsonProperty("source_service") String sourceService,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("payload") UserAuditPayload payload
) {
}
