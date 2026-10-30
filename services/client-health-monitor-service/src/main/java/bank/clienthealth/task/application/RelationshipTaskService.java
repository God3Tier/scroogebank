package bank.clienthealth.task.application;

import bank.clienthealth.audit.RelationshipHealthAuditEventFactory;
import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.RelationshipTaskPort;
import bank.clienthealth.contract.TaskUpsertResult;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;
import bank.clienthealth.task.port.ClientAssignmentPort;
import bank.clienthealth.task.port.DispositionPolicy;
import bank.clienthealth.task.port.TaskRepository;
import bank.clienthealth.task.port.TaskRepository.ProcessedFinding;
import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.metrics.ClientHealthMetrics.TaskOutcome;
import bank.clienthealth.metrics.ClientHealthMetrics.TaskTransition;
import bank.clienthealth.task.domain.RelationshipTask.OptimisticLockException;
import bank.clienthealth.task.domain.RelationshipTask.TaskAccessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Supplier;

/** Application service consumed by analysis now and by the task API later. */
public final class RelationshipTaskService implements RelationshipTaskPort {
    private static final int MAX_ATTEMPTS = 3;
    private static final int DAILY_TASK_CAP = 20;
    private static final Duration COOLDOWN = Duration.ofDays(30);
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private final TaskRepository repository;
    private final ClientAssignmentPort assignments;
    private final DispositionPolicy dispositionPolicy;
    private final Clock clock;
    private final Supplier<String> taskIds;
    private final ClientHealthMetrics metrics;

    public RelationshipTaskService(
            TaskRepository repository,
            ClientAssignmentPort assignments,
            DispositionPolicy dispositionPolicy,
            Clock clock,
            Supplier<String> taskIds
    ) {
        this(repository, assignments, dispositionPolicy, clock, taskIds, ClientHealthMetrics.noOp());
    }

    public RelationshipTaskService(
            TaskRepository repository, ClientAssignmentPort assignments,
            DispositionPolicy dispositionPolicy, Clock clock, Supplier<String> taskIds,
            ClientHealthMetrics metrics
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.assignments = Objects.requireNonNull(assignments);
        this.dispositionPolicy = Objects.requireNonNull(dispositionPolicy);
        this.clock = Objects.requireNonNull(clock);
        this.taskIds = Objects.requireNonNull(taskIds);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public TaskUpsertResult upsertFinding(ClientHealthFinding finding) {
        try {
            return handleFinding(finding);
        } catch (TaskRepository.ConflictException conflict) {
            metrics.taskConflict();
            throw conflict;
        }
    }

    private TaskUpsertResult handleFinding(ClientHealthFinding finding) {
        Objects.requireNonNull(finding, "finding is required");
        TaskUpsertResult replay = retry(() -> repository.inTransaction(session -> session
                .findProcessedFinding(finding.findingId())
                .map(processed -> replay(processed, finding))
                .orElse(null)));
        if (replay != null) return replay;

        AssignmentSnapshot assignment = assignments.resolve(finding.clientId());
        validateAssignment(finding.clientId(), assignment);
        Handled handled = retry(() -> repository.inTransaction(session -> {
            var processed = session.findProcessedFinding(finding.findingId());
            if (processed.isPresent()) return new Handled(replay(processed.get(), finding), false);

            finding.signals().forEach(signal -> session.enqueueAudit(
                    RelationshipHealthAuditEventFactory.signalDetected(finding, signal)));
            var active = session.findActiveTask(finding.clientId());
            active.ifPresent(task -> {
                if (!task.assignment().agentSubject().equals(assignment.agentSubject())) {
                    throw new ClientAssignmentPort.AssignmentRejectedException(
                            "Active task belongs to a different Agent");
                }
            });

            var fingerprints = finding.signals().stream().map(ClientHealthFinding.SignalEvidence::fingerprint).toList();
            var seen = session.findFingerprints(finding.clientId(), fingerprints);
            var unseen = finding.signals().stream().filter(signal -> !seen.contains(signal.fingerprint())).toList();
            Instant now = clock.instant();
            TaskUpsertResult result;

            if (unseen.isEmpty()) {
                RelationshipTask task = active.orElseGet(() -> session
                        .findLatestTaskContainingAny(finding.clientId(), fingerprints)
                        .orElseThrow(() -> new IllegalStateException("Duplicate evidence has no owning task")));
                result = new TaskUpsertResult(task.taskId(), Outcome.DUPLICATE, task.version());
            } else if (active.isPresent()) {
                RelationshipTask current = active.get();
                RelationshipTask updated = current.attach(finding, unseen, now);
                session.saveTask(updated, current.version());
                session.enqueueAudit(RelationshipHealthAuditEventFactory.taskHistory(
                        updated.taskId(), updated.clientId(), updated.history().getLast()));
                result = new TaskUpsertResult(updated.taskId(), Outcome.EVIDENCE_ATTACHED, updated.version());
            } else {
                var latestClosed = session.findLatestClosedTask(finding.clientId());
                boolean inCooldown = latestClosed
                        .filter(task -> now.isBefore(task.updatedAt().plus(COOLDOWN)))
                        .filter(task -> finding.priorityInputs().highestSeverity().compareTo(task.severity()) <= 0)
                        .isPresent();
                if (inCooldown) {
                    result = TaskUpsertResult.suppressed(Outcome.SUPPRESSED_COOLDOWN);
                } else {
                    LocalDate singaporeDate = LocalDate.ofInstant(now, SINGAPORE);
                    if (!session.tryReserveTaskCreation(
                            assignment.agentSubject(), singaporeDate, DAILY_TASK_CAP)) {
                        result = TaskUpsertResult.suppressed(Outcome.SUPPRESSED_DAILY_CAP);
                    } else {
                        RelationshipTask created = RelationshipTask.create(
                                taskIds.get(), finding, unseen, assignment, now);
                        session.saveTask(created, 0);
                        session.enqueueAudit(RelationshipHealthAuditEventFactory.taskHistory(
                                created.taskId(), created.clientId(), created.history().getLast()));
                        result = new TaskUpsertResult(created.taskId(), Outcome.CREATED, created.version());
                    }
                }
            }
            if (result.outcome().suppressed()) {
                session.enqueueAudit(RelationshipHealthAuditEventFactory.suppression(
                        finding, result.outcome()));
            }
            session.saveProcessedFinding(new ProcessedFinding(finding, result, now));
            return new Handled(result, true);
        }));
        if (handled.newlyProcessed()) metrics.taskOutcome(taskOutcome(handled.result().outcome()));
        return handled.result();
    }

    public RelationshipTask accept(String taskId, long expectedVersion, String agent, String correlationId) {
        return transition(TaskTransition.ACCEPT, () -> update(taskId, expectedVersion,
                task -> task.accept(expectedVersion, agent, correlationId, clock.instant())));
    }

    public RelationshipTask complete(
            String taskId, long expectedVersion, String agent, String correlationId, String outcomeCode, String note
    ) {
        dispositionPolicy.validate(outcomeCode, note);
        return transition(TaskTransition.COMPLETE, () -> update(taskId, expectedVersion,
                task -> task.complete(expectedVersion, agent, correlationId, clock.instant(), outcomeCode, note)));
    }

    public RelationshipTask dismiss(
            String taskId, long expectedVersion, String agent, String correlationId, String reasonCode, String note
    ) {
        dispositionPolicy.validate(reasonCode, note);
        return transition(TaskTransition.DISMISS, () -> update(taskId, expectedVersion,
                task -> task.dismiss(expectedVersion, agent, correlationId, clock.instant(), reasonCode, note)));
    }

    private RelationshipTask transition(TaskTransition transition, Supplier<RelationshipTask> operation) {
        try {
            RelationshipTask task = operation.get();
            metrics.taskTransition(transition);
            return task;
        } catch (TaskAccessException denied) {
            metrics.authorizationDenied();
            throw denied;
        } catch (OptimisticLockException | TaskRepository.ConflictException conflict) {
            metrics.taskConflict();
            throw conflict;
        }
    }

    private RelationshipTask update(
            String taskId, long expectedVersion,
            java.util.function.Function<RelationshipTask, RelationshipTask> change
    ) {
        RelationshipTask snapshot = repository.inTransaction(session -> session.findTask(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId)));
        AssignmentSnapshot assignment = assignments.resolve(snapshot.clientId());
        validateAssignment(snapshot.clientId(), assignment);
        return repository.inTransaction(session -> {
            RelationshipTask current = session.findTask(taskId)
                    .orElseThrow(() -> new TaskNotFoundException(taskId));
            validateAssignment(current.clientId(), assignment);
            if (!assignment.agentSubject().equals(current.assignment().agentSubject())) {
                current = current.withAssignment(assignment);
            }
            RelationshipTask updated = change.apply(current);
            session.saveTask(updated, expectedVersion);
            session.enqueueAudit(RelationshipHealthAuditEventFactory.taskHistory(
                    updated.taskId(), updated.clientId(), updated.history().getLast()));
            return updated;
        });
    }

    private static TaskUpsertResult replay(ProcessedFinding processed, ClientHealthFinding finding) {
        if (!processed.finding().equals(finding)) {
            throw new IdempotencyConflictException(finding.findingId());
        }
        return processed.result();
    }

    private static void validateAssignment(String clientId, AssignmentSnapshot assignment) {
        if (assignment == null || !assignment.active() || !clientId.equals(assignment.clientId())) {
            throw new ClientAssignmentPort.AssignmentRejectedException("Client assignment is not usable");
        }
    }

    private <T> T retry(Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (TaskRepository.ConflictException conflict) {
                if (attempt == MAX_ATTEMPTS) throw conflict;
            }
        }
    }

    private static TaskOutcome taskOutcome(Outcome outcome) {
        return switch (outcome) {
            case CREATED -> TaskOutcome.CREATED;
            case EVIDENCE_ATTACHED -> TaskOutcome.EVIDENCE_ATTACHED;
            case DUPLICATE -> TaskOutcome.DUPLICATE;
            case SUPPRESSED_COOLDOWN -> TaskOutcome.SUPPRESSED_COOLDOWN;
            case SUPPRESSED_DAILY_CAP -> TaskOutcome.SUPPRESSED_DAILY_CAP;
        };
    }

    private record Handled(TaskUpsertResult result, boolean newlyProcessed) { }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String findingId) { super("Finding ID reused: " + findingId); }
    }
    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String taskId) { super("Task not found: " + taskId); }
    }
}
