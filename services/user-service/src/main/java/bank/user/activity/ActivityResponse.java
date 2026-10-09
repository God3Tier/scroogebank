package bank.user.activity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One audit entry as shown to an admin. actorId is the user id of whoever
 * performed the action, or SYSTEM. payload is the event's own detail,
 * whose shape depends on sourceService and action.
 */
public record ActivityResponse(
        String id,
        String sourceService,
        String action,
        String actorId,
        Instant timestamp,
        JsonNode payload
) {
}
