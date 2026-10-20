package bank.alert.operations;

import bank.alert.config.OperationalProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API and the run-once worker are separate processes, so the quotas and
 * disables must be shared through the database. Each {@link OutboundControlService}
 * below stands for one process.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JdbcOutboundControlStore.class)
// The store commits in its own transactions, so the test must not hold one open.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcOutboundControlStoreTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
    private static final YearMonth MONTH = YearMonth.of(2026, 10);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcOutboundControlStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbound_usage");
        jdbc.update("DELETE FROM outbound_control");
    }

    @Test
    void searchUsageIsSharedBetweenProcesses() {
        OutboundControlService api = process();
        OutboundControlService worker = process();

        assertThat(worker.reserveSearches(4).allowed()).isTrue();
        assertThat(api.usage().searchQueries()).isEqualTo(4);
        // A fresh worker run starts from the stored count, not from zero.
        assertThat(process().usage().searchQueries()).isEqualTo(4);
        assertThat(api.reserveSearches(4)).isEqualTo(ReservationResult.allowed(2));
        assertThat(worker.reserveSearches(5).code()).isEqualTo("RUN_SEARCH_LIMIT");
        assertThat(worker.reserveSearches(2)).isEqualTo(ReservationResult.allowed(0));
        assertThat(worker.reserveSearches(1).code()).isEqualTo("SEARCH_QUOTA_REACHED");
    }

    @Test
    void aQuotaStopMadeByOneProcessBlocksTheOther() {
        OutboundControlService api = process();
        OutboundControlService worker = process();
        api.reserveSearches(4);
        api.reserveSearches(4);
        assertThat(api.reserveSearches(4).code()).isEqualTo("SEARCH_QUOTA_REACHED");

        assertThat(worker.status(OutboundCapability.INGESTION).reason())
                .isEqualTo(ControlReason.SEARCH_QUOTA_REACHED);
        assertThat(worker.reserveSearches(1).code()).isEqualTo("SEARCH_QUOTA_REACHED");
    }

    @Test
    void anOperatorDisableReachesEveryProcessUntilReenabled() {
        OutboundControlService api = process();
        OutboundControlService worker = process();

        api.disable(OutboundCapability.INGESTION);

        assertThat(worker.status(OutboundCapability.INGESTION).reason())
                .isEqualTo(ControlReason.OPERATOR_DISABLED);
        assertThat(worker.reserveSearches(1).code()).isEqualTo("OPERATOR_DISABLED");
        // The model is a separate capability.
        assertThat(worker.reserveModelTokens(10).allowed()).isTrue();

        api.reenable(OutboundCapability.INGESTION, "operator-subject", "corr-1");

        assertThat(process().status(OutboundCapability.INGESTION).enabled()).isTrue();
        assertThat(worker.reserveSearches(1).allowed()).isTrue();
    }

    @Test
    void theBudgetStopAccumulatesAcrossProcessesAndStopsBoth() {
        OutboundControlService api = process();
        OutboundControlService worker = process();

        worker.recordEstimatedCost(new BigDecimal("6.00"));
        api.recordEstimatedCost(new BigDecimal("4.00"));

        assertThat(worker.usage().estimatedCostUsd()).isEqualByComparingTo("10.00");
        assertThat(worker.status(OutboundCapability.INGESTION).reason())
                .isEqualTo(ControlReason.MONTHLY_BUDGET_REACHED);
        assertThat(api.status(OutboundCapability.MODEL).reason())
                .isEqualTo(ControlReason.MONTHLY_BUDGET_REACHED);
    }

    @Test
    void modelTokensAreSharedAndCapped() {
        OutboundControlService api = process();
        OutboundControlService worker = process();

        assertThat(api.reserveModelTokens(60)).isEqualTo(ReservationResult.allowed(40));
        assertThat(worker.reserveModelTokens(50).code()).isEqualTo("MODEL_TOKEN_QUOTA_REACHED");
        assertThat(api.status(OutboundCapability.MODEL).reason())
                .isEqualTo(ControlReason.MODEL_TOKEN_QUOTA_REACHED);
    }

    @Test
    void concurrentReservationsNeverExceedTheQuota() throws Exception {
        int quota = 7;
        int attempts = 25;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> reservations = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                reservations.add(() -> store.reserveSearches(MONTH, 1, quota));
            }
            int granted = 0;
            for (Future<Boolean> result : pool.invokeAll(reservations)) {
                if (result.get()) granted++;
            }

            assertThat(granted).isEqualTo(quota);
            assertThat(store.usage(MONTH).searchQueries()).isEqualTo(quota);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void usageStartsAtZeroEachMonth() {
        store.reserveSearches(MONTH, 3, 10);

        assertThat(store.usage(MONTH.plusMonths(1)).searchQueries()).isZero();
        assertThat(store.usage(MONTH).searchQueries()).isEqualTo(3);
    }

    @Test
    void disableReportsThePreviousReason() {
        assertThat(store.disable(OutboundCapability.MODEL, ControlReason.OPERATOR_DISABLED)).isEmpty();
        assertThat(store.disable(OutboundCapability.MODEL, ControlReason.MONTHLY_BUDGET_REACHED))
                .contains(ControlReason.OPERATOR_DISABLED);
        store.clearDisabled(OutboundCapability.MODEL);
        assertThat(store.disabledReason(OutboundCapability.MODEL)).isEmpty();
    }

    private OutboundControlService process() {
        OperationalProperties properties = new OperationalProperties(
                true, true, 10, 4, 100, 60, BigDecimal.TEN);
        return new OutboundControlService(properties, event -> { },
                new OperationalMetrics(new SimpleMeterRegistry()), CLOCK, store);
    }
}
