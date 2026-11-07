package bank.user.activity;

import java.time.Instant;

/**
 * Filters for the activity feed. Null means "any".
 *
 * @param agentId the acting user's Cognito sub. For an agent it is always their own.
 * @param from    inclusive
 * @param to      exclusive
 * @param cursor  a previous page's nextCursor, or null for the newest page. Opaque: the
 *                auditor's page token.
 */
public record ActivityQuery(
        String agentId,
        String clientId,
        Instant from,
        Instant to,
        int size,
        String cursor
) {
}
