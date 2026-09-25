package bank.user.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * Filters for the activity feed. Null means "any".
 *
 * @param from   inclusive
 * @param to     exclusive
 * @param cursor a previous page's nextCursor, or null for the newest page
 */
public record ActivityQuery(
        UUID agentId,
        Instant from,
        Instant to,
        int size,
        Long cursor
) {
}
