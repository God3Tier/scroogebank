package bank.clienthealth.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Micrometer implementation whose tags are derived only from bounded enums. */
public final class MicrometerClientHealthMetrics implements ClientHealthMetrics {
    private static final String RULESET = "relationship-health-v1";
    private final MeterRegistry registry;
    private final Clock clock;
    private final AtomicReference<Instant> watermark = new AtomicReference<>();

    public MicrometerClientHealthMetrics(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    MicrometerClientHealthMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        Gauge.builder("client_health_analysis_watermark_age_seconds", watermark, this::watermarkAgeSeconds)
                .register(registry);
    }

    @Override
    public void analysis(AnalysisOutcome outcome, FailureStage stage, Duration duration, Instant successfulWatermark) {
        counter("client_health_analysis_runs_total", "outcome", tag(outcome), "failure_stage", tag(stage)).increment();
        Timer.builder("client_health_analysis_duration_seconds")
                .tag("outcome", tag(outcome)).register(registry).record(duration);
        if (successfulWatermark != null) watermark.set(successfulWatermark);
    }

    @Override
    public void replay(ReplayOutcome outcome, int pages, int transactions, int rejected, Duration duration) {
        String value = tag(outcome);
        counter("client_health_replay_runs_total", "outcome", value).increment();
        counter("client_health_replay_pages_total", "outcome", value).increment(pages);
        counter("client_health_replay_transactions_total", "outcome", value).increment(transactions);
        counter("client_health_replay_rejected_total", "outcome", value).increment(rejected);
        Timer.builder("client_health_replay_duration_seconds")
                .tag("outcome", value).register(registry).record(duration);
    }

    @Override
    public void ruleEvaluation(Rule rule, RuleOutcome outcome, Severity severity) {
        counter("client_health_rule_evaluations_total", "rule", tag(rule), "outcome", tag(outcome),
                "severity", tag(severity), "ruleset", RULESET).increment();
    }

    @Override
    public void ruleSignal(Rule rule, Severity severity) {
        counter("client_health_rule_signals_total", "rule", tag(rule), "severity", tag(severity),
                "ruleset", RULESET).increment();
    }

    @Override public void taskOutcome(TaskOutcome outcome) {
        counter("client_health_task_outcomes_total", "outcome", tag(outcome)).increment();
    }
    @Override public void taskTransition(TaskTransition transition) {
        counter("client_health_task_transition_total", "transition", tag(transition)).increment();
    }
    @Override public void taskConflict() { counter("client_health_task_conflict_total").increment(); }
    @Override public void authorizationDenied() { counter("client_health_authorization_denied_total").increment(); }

    @Override
    public void assignment(AssignmentOutcome outcome, Duration duration) {
        String value = tag(outcome);
        counter("client_health_assignment_requests_total", "outcome", value).increment();
        Timer.builder("client_health_assignment_duration_seconds")
                .tag("outcome", value).register(registry).record(duration);
    }

    @Override public void audit(AuditOutcome outcome, AuditFailure failure) {
        counter("client_health_audit_events_total", "outcome", tag(outcome), "failure", tag(failure)).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private double watermarkAgeSeconds(AtomicReference<Instant> reference) {
        Instant value = reference.get();
        if (value == null) return Double.NaN;
        return Math.max(0.0, Duration.between(value, clock.instant()).toMillis() / 1000.0);
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
