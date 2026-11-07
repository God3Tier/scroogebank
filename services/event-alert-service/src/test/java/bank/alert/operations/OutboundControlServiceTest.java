package bank.alert.operations;

import bank.alert.config.OperationalProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundControlServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void defaultsBothOutboundCapabilitiesToOff() {
        OperationalProperties properties = new OperationalProperties(
                false, false, 0, 0, 0, 0, null);
        OutboundControlService controls = service(properties, event -> { });

        assertThat(controls.status(OutboundCapability.INGESTION))
                .isEqualTo(new ControlStatus(OutboundCapability.INGESTION, false,
                        ControlReason.CONFIG_DISABLED));
        assertThat(controls.status(OutboundCapability.MODEL))
                .isEqualTo(new ControlStatus(OutboundCapability.MODEL, false,
                        ControlReason.CONFIG_DISABLED));
        assertThat(controls.reserveSearches(1).code()).isEqualTo("CONFIG_DISABLED");
        assertThat(controls.reserveModelTokens(1).code()).isEqualTo("CONFIG_DISABLED");
    }

    @Test
    void enforcesPerRunAndMonthlySearchLimits() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboundControlService controls = service(enabledProperties(), event -> { }, registry);

        assertThat(controls.reserveSearches(5).code()).isEqualTo("RUN_SEARCH_LIMIT");
        assertThat(controls.reserveSearches(4)).isEqualTo(ReservationResult.allowed(1));
        assertThat(controls.reserveSearches(2).code()).isEqualTo("SEARCH_QUOTA_REACHED");
        assertThat(controls.status(OutboundCapability.INGESTION).reason())
                .isEqualTo(ControlReason.SEARCH_QUOTA_REACHED);
        assertThat(registry.counter("alert.outbound.disabled", "capability", "ingestion",
                "reason", "search_quota_reached").count()).isEqualTo(1.0);
        assertThat(registry.get("alert.outbound.monthly.searches").gauge().value()).isEqualTo(4.0);
    }

    @Test
    void enforcesInvocationAndMonthlyModelTokenLimits() {
        OutboundControlService controls = service(enabledProperties(), event -> { });

        assertThat(controls.reserveModelTokens(61).code()).isEqualTo("INVOCATION_TOKEN_LIMIT");
        assertThat(controls.reserveModelTokens(60)).isEqualTo(ReservationResult.allowed(40));
        assertThat(controls.reserveModelTokens(50).code()).isEqualTo("MODEL_TOKEN_QUOTA_REACHED");
        assertThat(controls.status(OutboundCapability.MODEL).reason())
                .isEqualTo(ControlReason.MODEL_TOKEN_QUOTA_REACHED);
    }

    @Test
    void budgetBoundaryStopsBothOutboundCapabilities() {
        OutboundControlService controls = service(enabledProperties(), event -> { });

        controls.recordEstimatedCost(new BigDecimal("9.99"));
        assertThat(controls.status(OutboundCapability.INGESTION).enabled()).isTrue();
        controls.recordEstimatedCost(new BigDecimal("0.01"));

        assertThat(controls.status(OutboundCapability.INGESTION).reason())
                .isEqualTo(ControlReason.MONTHLY_BUDGET_REACHED);
        assertThat(controls.status(OutboundCapability.MODEL).reason())
                .isEqualTo(ControlReason.MONTHLY_BUDGET_REACHED);
        assertThat(controls.usage().estimatedCostUsd()).isEqualByComparingTo("10.00");
        assertThatThrownBy(() -> controls.reenable(
                OutboundCapability.MODEL, "operator-subject", "corr-budget"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget");
    }

    @Test
    void reenableRequiresHumanContextAndDurableAuditFirst() {
        List<ControlAuditEvent> events = new ArrayList<>();
        OutboundControlService controls = service(enabledProperties(), events::add);
        controls.disable(OutboundCapability.INGESTION);

        assertThatThrownBy(() -> controls.reenable(OutboundCapability.INGESTION, "", "corr-1"))
                .isInstanceOf(IllegalArgumentException.class);
        controls.reenable(OutboundCapability.INGESTION, "operator-subject", "corr-1");

        assertThat(controls.status(OutboundCapability.INGESTION).enabled()).isTrue();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.capability()).isEqualTo(OutboundCapability.INGESTION);
            assertThat(event.action()).isEqualTo("REENABLED");
            assertThat(event.actorSubject()).isEqualTo("operator-subject");
            assertThat(event.correlationId()).isEqualTo("corr-1");
            assertThat(event.previousReason()).isEqualTo(ControlReason.OPERATOR_DISABLED);
            assertThat(event.occurredAt()).isEqualTo(CLOCK.instant());
        });
    }

    @Test
    void failedAuditLeavesCapabilityDisabled() {
        OutboundControlService controls = service(enabledProperties(), event -> {
            throw new OperationalControlAuditUnavailableException();
        });
        controls.disable(OutboundCapability.MODEL);

        assertThatThrownBy(() -> controls.reenable(
                OutboundCapability.MODEL, "operator-subject", "corr-2"))
                .isInstanceOf(OperationalControlAuditUnavailableException.class);
        assertThat(controls.status(OutboundCapability.MODEL).enabled()).isFalse();
    }

    @Test
    void configurationDisabledCapabilityCannotBeReenabledAtRuntime() {
        OutboundControlService controls = service(new OperationalProperties(
                false, true, 5, 4, 100, 60, BigDecimal.TEN), event -> { });

        assertThatThrownBy(() -> controls.reenable(
                OutboundCapability.INGESTION, "operator-subject", "corr-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration");
    }

    private static OperationalProperties enabledProperties() {
        return new OperationalProperties(true, true, 5, 4, 100, 60, BigDecimal.TEN);
    }

    private static OutboundControlService service(OperationalProperties properties,
            OperationalControlAuditSink auditSink) {
        return service(properties, auditSink, new SimpleMeterRegistry());
    }

    private static OutboundControlService service(OperationalProperties properties,
            OperationalControlAuditSink auditSink, SimpleMeterRegistry registry) {
        return new OutboundControlService(properties, auditSink,
                new OperationalMetrics(registry), CLOCK);
    }

    @Test
    void everySearchIsCountedAgainstTheMonthlyBudget() {
        OperationalProperties properties = new OperationalProperties(true, true, 1_000, 1_000, 100, 60,
                new BigDecimal("0.05"), new BigDecimal("0.02"));
        OutboundControlService controls = service(properties, event -> { });

        assertThat(controls.reserveSearches(2).allowed()).isTrue();

        assertThat(controls.usage().estimatedCostUsd()).isEqualByComparingTo("0.04");
        assertThat(controls.usage().searchQueries()).isEqualTo(2);
    }

    @Test
    void aRunThatTheBudgetCannotCoverSearchesNothingAndLeavesTheControlOn() {
        OperationalProperties properties = new OperationalProperties(true, true, 1_000, 1_000, 100, 60,
                new BigDecimal("0.05"), new BigDecimal("0.02"));
        OutboundControlService controls = service(properties, event -> { });
        controls.reserveSearches(2);

        ReservationResult refused = controls.reserveSearches(1);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.code()).isEqualTo("MONTHLY_BUDGET_REACHED");
        assertThat(controls.usage().searchQueries()).isEqualTo(2);
        assertThat(controls.usage().estimatedCostUsd()).isEqualByComparingTo("0.04");
        assertThat(controls.status(OutboundCapability.INGESTION).enabled()).isTrue();
    }

    @Test
    void aWholeRunIsReservedTogetherOrNotAtAll() {
        OutboundControlService controls = service(new OperationalProperties(true, true, 10, 10, 100, 60,
                BigDecimal.TEN, new BigDecimal("0.008")), event -> { });

        assertThat(controls.reserveSearches(8).allowed()).isTrue();
        ReservationResult refused = controls.reserveSearches(4);

        assertThat(refused.code()).isEqualTo("SEARCH_QUOTA_REACHED");
        assertThat(controls.usage().searchQueries()).isEqualTo(8);
    }
}
