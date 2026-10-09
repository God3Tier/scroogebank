package bank.dashboard.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * The detail of a dashboard view.
 *
 * <p>Scope, window and panel statuses only. No figure, no client name, no
 * contact field: an audit payload is a place PII leaks into if nobody decides
 * otherwise, so the record's shape is the decision.
 *
 * @param scope       ADMIN or AGENT, as derived from the token
 * @param windowFrom  ISO date
 * @param windowTo    ISO date
 * @param panels      panel name to its status, so a later reader can tell a
 *                    full view from a degraded one
 * @param resultCount how many panels had figures to show
 * @param cacheHit    whether the figures came from cache. Recorded because a
 *                    cached answer is still a view, and because it explains
 *                    why the figures may predate the request
 * @param datetime    when the view happened, as an ISO-8601 instant
 */
public record DashboardViewPayload(
        @JsonProperty("scope") String scope,
        @JsonProperty("window_from") String windowFrom,
        @JsonProperty("window_to") String windowTo,
        @JsonProperty("panels") Map<String, String> panels,
        @JsonProperty("result_count") int resultCount,
        @JsonProperty("cache_hit") boolean cacheHit,
        @JsonProperty("datetime") String datetime
) {
}
