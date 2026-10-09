package bank.user.activity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One change in the activity feed. actorId is the acting user's Cognito sub,
 * or a system: id for work no user started; actorName is that user's name,
 * or null if they are not a CRM user here. payload is the event's own detail,
 * whose shape depends on sourceService and action; it never holds client
 * personal data.
 */
public record ActivityResponse(
        String id,
        String sourceService,
        String action,
        String actorId,
        String actorName,
        Instant timestamp,
        JsonNode payload
) {
}
