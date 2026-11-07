package bank.engagement.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** The message the auditor reads from the {@code audit-insert} queue. */
public record AuditorEnvelope(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        String action,
        @JsonProperty("source_service") String sourceService,
        @JsonProperty("actor_id") String actorId,
        JsonNode payload) {
}
