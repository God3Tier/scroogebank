package bank.user.activity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** One entry from the complete administrator-only audit trail. */
public record AuditLogResponse(
        String id,
        String eventType,
        String sourceService,
        String action,
        String actorId,
        String actorName,
        Instant timestamp,
        JsonNode payload
) {
}
