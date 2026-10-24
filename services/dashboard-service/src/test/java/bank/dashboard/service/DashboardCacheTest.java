package bank.dashboard.service;

import bank.dashboard.config.DashboardProperties;
import bank.dashboard.dto.DashboardResponse;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.dto.Panel;
import bank.dashboard.dto.PanelReason;
import bank.dashboard.dto.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardCacheTest {

    private static final DashboardWindow WINDOW =
            new DashboardWindow(LocalDate.of(2026, 8, 22), LocalDate.of(2026, 9, 20));

    private final DashboardCache cache = new DashboardCache(new DashboardProperties(
            new DashboardProperties.Window(30, 365),
            new DashboardProperties.Cache(60, 1000),
            new DashboardProperties.Upstream(2000, 3000, "", "", "")));

    @Test
    void neverServesOneCallersFiguresToAnother() {
        // The correctness property the whole cache design turns on. Responses
        // are scoped, so a shared entry would be a data leak rather than a
        // performance bug.
        DashboardResponse agentOne = cache.get(key("agent-1"), () -> response("agent-1")).response();
        DashboardResponse agentTwo = cache.get(key("agent-2"), () -> response("agent-2")).response();

        assertThat(agentOne.panels().get("clients").data()).isEqualTo("agent-1");
        assertThat(agentTwo.panels().get("clients").data()).isEqualTo("agent-2");
    }

    @Test
    void computesOnceForRepeatedIdenticalRequests() {
        AtomicInteger computations = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            cache.get(key("agent-1"), () -> {
                computations.incrementAndGet();
                return response("agent-1");
            });
        }

        assertThat(computations).hasValue(1);
    }

    @Test
    void treatsAChangedRoleAsADifferentEntry() {
        cache.get(new DashboardCache.Key("same-subject", false, "overview", WINDOW, null), () -> response("as-agent"));
        DashboardResponse asAdmin = cache.get(
                new DashboardCache.Key("same-subject", true, "overview", WINDOW, null), () -> response("as-admin")).response();

        assertThat(asAdmin.panels().get("clients").data()).isEqualTo("as-admin");
    }

    @Test
    void treatsADifferentWindowOrVariantAsADifferentEntry() {
        cache.get(key("agent-1"), () -> response("30-day"));

        DashboardWindow shorter = new DashboardWindow(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20));
        DashboardResponse other = cache.get(
                new DashboardCache.Key("agent-1", false, "overview", shorter, null), () -> response("7-day")).response();
        DashboardResponse variant = cache.get(
                new DashboardCache.Key("agent-1", false, "action-items", WINDOW, "14"), () -> response("stale-14")).response();

        assertThat(other.panels().get("clients").data()).isEqualTo("7-day");
        assertThat(variant.panels().get("clients").data()).isEqualTo("stale-14");
    }

    @Test
    void reportsWhetherTheAnswerCameFromCache() {
        // Drives the cache_hit field on audit events: a cached answer is still
        // a view, and still has to be recorded. Reported by the same call that
        // returns the value, so the two cannot disagree.
        assertThat(cache.get(key("agent-1"), () -> response("agent-1")).cacheHit()).isFalse();
        assertThat(cache.get(key("agent-1"), () -> response("agent-1")).cacheHit()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PanelReason.class, names = {"UPSTREAM_TIMEOUT", "UPSTREAM_ERROR"})
    void doesNotKeepAResponseWithATransientFailure(PanelReason reason) {
        // Kept for the TTL, one slow call after a restart would make the
        // dashboard look down for three minutes.
        AtomicInteger computations = new AtomicInteger();
        Supplier<DashboardResponse> partlyFailed = () -> {
            computations.incrementAndGet();
            return response(Map.of("clients", Panel.ok("figures"), "accounts", Panel.unavailable(reason)));
        };

        ServedResponse first = cache.get(key("agent-1"), partlyFailed);
        ServedResponse second = cache.get(key("agent-1"), partlyFailed);

        assertThat(computations).hasValue(2);
        assertThat(first.cacheHit()).isFalse();
        assertThat(second.cacheHit()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PanelReason.class, names = {"UPSTREAM_TIMEOUT", "UPSTREAM_ERROR"}, mode = EnumSource.Mode.EXCLUDE)
    void keepsAResponseWhoseFailuresAskingAgainWouldNotChange(PanelReason reason) {
        // Not wired up, refused, an unsupported window: the next request
        // would get the same answer.
        AtomicInteger computations = new AtomicInteger();
        Supplier<DashboardResponse> settled = () -> {
            computations.incrementAndGet();
            return response(Map.of("clients", Panel.ok("figures"), "accounts", Panel.unavailable(reason)));
        };

        cache.get(key("agent-1"), settled);
        boolean secondWasAHit = cache.get(key("agent-1"), settled).cacheHit();

        assertThat(computations).hasValue(1);
        assertThat(secondWasAHit).isTrue();
    }

    private DashboardCache.Key key(String subject) {
        return new DashboardCache.Key(subject, false, "overview", WINDOW, null);
    }

    private DashboardResponse response(String marker) {
        return response(Map.of("clients", Panel.ok(marker)));
    }

    private DashboardResponse response(Map<String, Panel> panels) {
        return new DashboardResponse(Instant.parse("2026-09-20T10:15:00Z"), WINDOW, Scope.AGENT, panels);
    }
}
