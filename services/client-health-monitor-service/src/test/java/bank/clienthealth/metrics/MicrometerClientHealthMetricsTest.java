package bank.clienthealth.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import bank.clienthealth.metrics.ClientHealthMetrics.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MicrometerClientHealthMetricsTest {
    @Test
    void recordsBoundedOperationalOutcomesWithoutIdentifiers() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientHealthMetrics(registry);

        metrics.analysis(AnalysisOutcome.COMPLETED, FailureStage.NONE,
                Duration.ofSeconds(2), Duration.ofMinutes(3));
        metrics.replay(ReplayOutcome.COMPLETED, 2, 7, 0, Duration.ofMillis(40));
        metrics.ruleEvaluation(Rule.RECURRING_CREDIT, RuleOutcome.TRIGGERED, Severity.HIGH);
        metrics.ruleSignal(Rule.RECURRING_CREDIT, Severity.HIGH);
        metrics.taskOutcome(TaskOutcome.CREATED);
        metrics.taskTransition(TaskTransition.ACCEPT);
        metrics.taskConflict();
        metrics.authorizationDenied();
        metrics.assignment(AssignmentOutcome.UNAVAILABLE, Duration.ofMillis(5));
        metrics.audit(AuditOutcome.RELEASED, AuditFailure.UNAVAILABLE);

        assertThat(registry.get("client_health_analysis_runs_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("client_health_replay_pages_total").counter().count()).isEqualTo(2);
        assertThat(registry.get("client_health_replay_transactions_total").counter().count()).isEqualTo(7);
        assertThat(registry.get("client_health_analysis_watermark_age_seconds").gauge().value()).isEqualTo(180);
        assertThat(registry.get("client_health_rule_evaluations_total")
                .tags("rule", "recurring_credit", "outcome", "triggered",
                        "severity", "high", "ruleset", "relationship-health-v1")
                .counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getValue()).doesNotContain(
                        "client-123", "account-123", "task-123", "agent-123", "https://")));
    }
}
