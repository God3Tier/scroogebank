package bank.clienthealth.task.domain;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Persistence-independent aggregate for one client relationship task. */
public record RelationshipTask(
        String taskId,
        String clientId,
        AssignmentSnapshot assignment,
        Status status,
        Severity severity,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<Evidence> evidence,
        List<HistoryEntry> history
) {
    public RelationshipTask {
        taskId = requireText(taskId, "taskId");
        clientId = requireText(clientId, "clientId");
        assignment = Objects.requireNonNull(assignment, "assignment is required");
        if (!clientId.equals(assignment.clientId())) {
            throw new IllegalArgumentException("assignment must belong to the task client");
        }
        status = Objects.requireNonNull(status, "status is required");
        severity = Objects.requireNonNull(severity, "severity is required");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
        history = List.copyOf(Objects.requireNonNull(history, "history is required"));
    }

    public static RelationshipTask create(
            String taskId,
            ClientHealthFinding finding,
            AssignmentSnapshot assignment,
            Instant now
    ) {
        return create(taskId, finding, finding.signals(), assignment, now);
    }

    public static RelationshipTask create(
            String taskId,
            ClientHealthFinding finding,
            List<ClientHealthFinding.SignalEvidence<? extends ClientHealthFinding.EvidenceDetails>> signals,
            AssignmentSnapshot assignment,
            Instant now
    ) {
        if (signals.isEmpty()) throw new IllegalArgumentException("Task requires evidence");
        List<Evidence> evidence = signals.stream()
                .map(signal -> Evidence.from(finding, signal, now))
                .toList();
        HistoryEntry created = new HistoryEntry(
                1, Action.CREATED, "SYSTEM", finding.correlationId(), now,
                null, Status.OPEN, null, finding.priorityInputs().highestSeverity(),
                evidence.stream().map(Evidence::fingerprint).toList(), null, null);
        return new RelationshipTask(
                taskId, finding.clientId(), assignment, Status.OPEN,
                finding.priorityInputs().highestSeverity(), 1, now, now,
                evidence, List.of(created));
    }

    public RelationshipTask attach(
            ClientHealthFinding finding,
            List<ClientHealthFinding.SignalEvidence<? extends ClientHealthFinding.EvidenceDetails>> unseen,
            Instant now
    ) {
        if (!active()) {
            throw new InvalidTransitionException("Evidence can only be attached to an active task");
        }
        if (unseen.isEmpty()) {
            return this;
        }
        List<Evidence> additions = unseen.stream().map(signal -> Evidence.from(finding, signal, now)).toList();
        Severity nextSeverity = severity.compareTo(finding.priorityInputs().highestSeverity()) >= 0
                ? severity : finding.priorityInputs().highestSeverity();
        long nextVersion = version + 1;
        HistoryEntry entry = new HistoryEntry(
                nextVersion, Action.EVIDENCE_ADDED, "SYSTEM", finding.correlationId(), now,
                status, status, severity, nextSeverity,
                additions.stream().map(Evidence::fingerprint).toList(), null, null);
        return copy(status, nextSeverity, nextVersion, now,
                append(evidence, additions), append(history, List.of(entry)));
    }

    public RelationshipTask accept(long expectedVersion, String agentSubject, String correlationId, Instant now) {
        return transition(expectedVersion, agentSubject, correlationId, now,
                Status.OPEN, Status.IN_REVIEW, Action.ACCEPTED, null, null);
    }

    public RelationshipTask complete(
            long expectedVersion, String agentSubject, String correlationId, Instant now,
            String outcomeCode, String note
    ) {
        return transition(expectedVersion, agentSubject, correlationId, now,
                Status.IN_REVIEW, Status.COMPLETED, Action.COMPLETED, requireText(outcomeCode, "outcomeCode"), note);
    }

    public RelationshipTask dismiss(
            long expectedVersion, String agentSubject, String correlationId, Instant now,
            String reasonCode, String note
    ) {
        if (status != Status.OPEN && status != Status.IN_REVIEW) {
            throw new InvalidTransitionException("Only OPEN or IN_REVIEW tasks can be dismissed");
        }
        return transition(expectedVersion, agentSubject, correlationId, now,
                status, Status.DISMISSED, Action.DISMISSED, requireText(reasonCode, "reasonCode"), note);
    }

    public boolean active() {
        return status == Status.OPEN || status == Status.IN_REVIEW;
    }

    private RelationshipTask transition(
            long expectedVersion, String agentSubject, String correlationId, Instant now,
            Status required, Status target, Action action, String dispositionCode, String note
    ) {
        if (version != expectedVersion) {
            throw new OptimisticLockException("Expected version %d but found %d".formatted(expectedVersion, version));
        }
        if (status != required) {
            throw new InvalidTransitionException("Cannot transition from " + status + " to " + target);
        }
        if (!assignment.agentSubject().equals(requireText(agentSubject, "agentSubject"))) {
            throw new TaskAccessException("Task is assigned to another Agent");
        }
        long nextVersion = version + 1;
        HistoryEntry entry = new HistoryEntry(
                nextVersion, action, agentSubject, requireText(correlationId, "correlationId"), now,
                status, target, severity, severity, List.of(), dispositionCode, note);
        return copy(target, severity, nextVersion, now, evidence, append(history, List.of(entry)));
    }

    private RelationshipTask copy(
            Status nextStatus, Severity nextSeverity, long nextVersion, Instant now,
            List<Evidence> nextEvidence, List<HistoryEntry> nextHistory
    ) {
        return new RelationshipTask(taskId, clientId, assignment, nextStatus, nextSeverity,
                nextVersion, createdAt, now, nextEvidence, nextHistory);
    }

    private static <T> List<T> append(List<T> current, List<T> additions) {
        var result = new java.util.ArrayList<>(current);
        result.addAll(additions);
        return List.copyOf(result);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    public enum Status { OPEN, IN_REVIEW, COMPLETED, DISMISSED }
    public enum Action { CREATED, EVIDENCE_ADDED, ACCEPTED, COMPLETED, DISMISSED }

    public record AssignmentSnapshot(
            String clientId, String agentSubject, boolean active, long assignmentVersion, Instant updatedAt
    ) {
        public AssignmentSnapshot {
            clientId = requireText(clientId, "clientId");
            agentSubject = requireText(agentSubject, "agentSubject");
            if (assignmentVersion <= 0) throw new IllegalArgumentException("assignmentVersion must be positive");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
        }
    }

    public record Evidence(
            String findingId, String analysisRunId, String rulesetVersion, String correlationId,
            Instant detectedAt, Instant attachedAt,
            ClientHealthFinding.SignalEvidence<? extends ClientHealthFinding.EvidenceDetails> signal
    ) {
        public Evidence {
            findingId = requireText(findingId, "findingId");
            signal = Objects.requireNonNull(signal, "signal is required");
        }

        public String fingerprint() { return signal.fingerprint(); }

        static Evidence from(
                ClientHealthFinding finding,
                ClientHealthFinding.SignalEvidence<? extends ClientHealthFinding.EvidenceDetails> signal,
                Instant now
        ) {
            return new Evidence(finding.findingId(), finding.analysisRunId(), finding.rulesetVersion(),
                    finding.correlationId(), finding.detectedAt(), now, signal);
        }
    }

    public record HistoryEntry(
            long taskVersion, Action action, String actor, String correlationId, Instant occurredAt,
            Status beforeStatus, Status afterStatus, Severity beforeSeverity, Severity afterSeverity,
            List<String> addedFingerprints, String dispositionCode, String note
    ) {
        public HistoryEntry {
            action = Objects.requireNonNull(action, "action is required");
            actor = requireText(actor, "actor");
            correlationId = requireText(correlationId, "correlationId");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt is required");
            addedFingerprints = List.copyOf(addedFingerprints);
        }
    }

    public static class InvalidTransitionException extends RuntimeException {
        public InvalidTransitionException(String message) { super(message); }
    }
    public static class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) { super(message); }
    }
    public static class TaskAccessException extends RuntimeException {
        public TaskAccessException(String message) { super(message); }
    }
}
