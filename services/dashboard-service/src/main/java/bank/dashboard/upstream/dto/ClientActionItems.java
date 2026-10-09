package bank.dashboard.upstream.dto;

import java.time.Instant;
import java.util.List;

/**
 * The caller's work queue from client-service's
 * {@code GET /aggregates/clients/action-items}: clients still not verified
 * some days after they were created, oldest first. The list is capped; the
 * total is not.
 */
public record ClientActionItems(
        List<ClientRef> staleUnverified,
        long staleUnverifiedTotal
) {

    /** A client the caller can already open. */
    public record ClientRef(String clientId, String firstName, String lastName, Instant createdAt) {
    }
}
