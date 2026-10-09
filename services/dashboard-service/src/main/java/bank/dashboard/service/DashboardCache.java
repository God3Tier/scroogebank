package bank.dashboard.service;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.DashboardResponse;
import bank.dashboard.dto.DashboardWindow;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Per-task response cache (ADR-0033).
 *
 * <p>Two properties matter more than the hit rate:
 *
 * <p><strong>The caller's subject is part of every key.</strong> Responses are
 * scoped -- an agent's payload contains their own clients' figures -- so a
 * cache that could serve one caller's response to another would be a data
 * leak, not a performance bug. That is why the key is a typed record rather
 * than a SpEL expression: it cannot be edited into something that omits the
 * subject without the change being obvious.
 *
 * <p><strong>It is per task, not shared.</strong> Two replicas can answer from
 * entries up to one TTL apart. Acceptable for a management dashboard, and made
 * visible by {@code generatedAt} on every response rather than hidden.
 */
@Component
public class DashboardCache {

    private final Cache<Key, DashboardResponse> cache;

    public DashboardCache(DashboardProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.cache().ttlSeconds()))
                .maximumSize(properties.cache().maxEntries())
                .build();
    }

    /**
     * @param subject  the caller's JWT subject -- never omit this
     * @param admin    the caller's role, because the same subject could in
     *                 principle see differently shaped payloads
     * @param endpoint which dashboard endpoint was asked for
     * @param window   the reporting window
     * @param variant  any further parameter that changes the answer, such as
     *                 the stale-days threshold or the sort column
     */
    public record Key(String subject, boolean admin, String endpoint, DashboardWindow window, String variant) {
    }

    /**
     * Returns the entry for this key, computing it only on a miss, and says
     * which of the two happened.
     *
     * <p>The flag is set inside the mapping function, which Caffeine runs only
     * on a miss and under the entry lock. That makes the answer exact: a
     * separate probe-then-fetch could see an entry expire between the two
     * calls and report a hit that never happened.
     */
    public ServedResponse get(Key key, Supplier<DashboardResponse> compute) {
        AtomicBoolean computed = new AtomicBoolean();
        DashboardResponse response = cache.get(key, k -> {
            computed.set(true);
            return compute.get();
        });
        return new ServedResponse(response, !computed.get());
    }

    /** Test seam; not exposed over HTTP. */
    public void invalidateAll() {
        cache.invalidateAll();
    }
}
