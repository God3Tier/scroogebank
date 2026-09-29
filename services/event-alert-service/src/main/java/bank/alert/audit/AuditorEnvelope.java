package bank.alert.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AuditorEnvelope(
        @JsonProperty("event_type") String eventType,
        String action,
        @JsonProperty("source_service") String sourceService,
        @JsonProperty("actor_id") String actorId,
        JsonNode payload) {
}
