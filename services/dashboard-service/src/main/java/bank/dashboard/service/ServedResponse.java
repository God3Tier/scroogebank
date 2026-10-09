package bank.dashboard.service;

import bank.dashboard.dto.DashboardResponse;

/**
 * A response together with whether it came from cache.
 *
 * <p>The two travel as one value because they are decided as one: the cache
 * knows whether it computed or returned an existing entry, and asking it twice
 * -- once to probe, once to fetch -- leaves a window in which an entry can
 * expire between the two calls and the audit trail records a hit that did not
 * happen. {@code cacheHit} is an ADR-0033 audit field, so its accuracy is the
 * point rather than a detail.
 *
 * @param response the response to serve
 * @param cacheHit whether it was already held, rather than computed now
 */
public record ServedResponse(DashboardResponse response, boolean cacheHit) {
}
