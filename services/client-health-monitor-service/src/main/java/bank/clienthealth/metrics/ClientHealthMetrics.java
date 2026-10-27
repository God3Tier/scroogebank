package bank.clienthealth.metrics;

import java.time.Duration;
import java.time.Instant;

/** Low-cardinality operational metrics boundary for Client Health. */
public interface ClientHealthMetrics {
    void analysis(AnalysisOutcome outcome, FailureStage stage, Duration duration, Instant watermark);
    void replay(ReplayOutcome outcome, int pages, int transactions, int rejected, Duration duration);
    void ruleEvaluation(Rule rule, RuleOutcome outcome, Severity severity);
    void ruleSignal(Rule rule, Severity severity);
    void taskOutcome(TaskOutcome outcome);
    void taskTransition(TaskTransition transition);
    void taskConflict();
    void authorizationDenied();
    void assignment(AssignmentOutcome outcome, Duration duration);
    void audit(AuditOutcome outcome, AuditFailure failure);

    static ClientHealthMetrics noOp() { return NoOp.INSTANCE; }

    enum AnalysisOutcome { COMPLETED, FAILED, ALREADY_COMPLETED, LEASE_HELD }
    enum FailureStage { REPLAY, PROJECTION, BASELINE, RULES, TASK_HANDOFF, UNEXPECTED, NONE }
    enum ReplayOutcome { COMPLETED, FAILED, LIMIT_EXCEEDED, INVALID_PAGE }
    enum Rule { RECURRING_CREDIT, ACTIVITY_DECLINE, NET_OUTFLOW }
    enum RuleOutcome { TRIGGERED, NOT_TRIGGERED, INSUFFICIENT }
    enum Severity { LOW, MEDIUM, HIGH, NONE }
    enum TaskOutcome { CREATED, EVIDENCE_ATTACHED, DUPLICATE, SUPPRESSED_COOLDOWN, SUPPRESSED_DAILY_CAP }
    enum TaskTransition { ACCEPT, COMPLETE, DISMISS }
    enum AssignmentOutcome { SUCCESS, INACTIVE, NOT_FOUND, UNAVAILABLE, INVALID_RESPONSE }
    enum AuditOutcome { ENQUEUED, DELIVERED, RELEASED, RECOVERED }
    enum AuditFailure { UNAVAILABLE, REJECTED, UNKNOWN, NONE }

    enum NoOp implements ClientHealthMetrics {
        INSTANCE;
        public void analysis(AnalysisOutcome o, FailureStage s, Duration d, Instant w) { }
        public void replay(ReplayOutcome o, int p, int t, int r, Duration d) { }
        public void ruleEvaluation(Rule r, RuleOutcome o, Severity s) { }
        public void ruleSignal(Rule r, Severity s) { }
        public void taskOutcome(TaskOutcome o) { }
        public void taskTransition(TaskTransition t) { }
        public void taskConflict() { }
        public void authorizationDenied() { }
        public void assignment(AssignmentOutcome o, Duration d) { }
        public void audit(AuditOutcome o, AuditFailure f) { }
    }
}
