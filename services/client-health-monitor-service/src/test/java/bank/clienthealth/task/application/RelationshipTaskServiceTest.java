package bank.clienthealth.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;
import bank.clienthealth.task.domain.RelationshipTask.Status;
import bank.clienthealth.task.port.ClientAssignmentPort;
import bank.clienthealth.task.support.InMemoryTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelationshipTaskServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final String CLIENT = "client-1";
    private static final String AGENT = "agent-subject";

    private InMemoryTaskRepository repository;
    private AtomicInteger assignmentCalls;
    private AtomicInteger taskSequence;
    private ClientAssignmentPort assignmentPort;
    private RelationshipTaskService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTaskRepository();
        assignmentCalls = new AtomicInteger();
        taskSequence = new AtomicInteger();
        assignmentPort = clientId -> {
            assignmentCalls.incrementAndGet();
            return assignment(clientId, AGENT, true);
        };
        service = service(assignmentPort, (code, note) -> {
            if (!code.startsWith("VALID_")) throw new IllegalArgumentException("Unsupported disposition");
        });
    }

    @Test
    void createsOneOpenTaskAndReplaysExactFindingOutcome() {
        var finding = finding("finding-1", Severity.MEDIUM, signal("a", Severity.MEDIUM));

        var created = service.upsertFinding(finding);
        var replay = service.upsertFinding(finding);
        RelationshipTask task = repository.task(created.taskId()).orElseThrow();

        assertEquals(Outcome.CREATED, created.outcome());
        assertEquals(created, replay);
        assertEquals(Status.OPEN, task.status());
        assertEquals(1, task.evidence().size());
        assertEquals(RelationshipTask.Action.CREATED, task.history().getFirst().action());
        assertEquals(1, assignmentCalls.get());
        assertEquals(1, repository.taskCount());
    }

    @Test
    void attachesOnlyUnseenEvidenceAndKeepsHighestSeverity() {
        var first = finding("finding-1", Severity.HIGH, signal("a", Severity.HIGH));
        String taskId = service.upsertFinding(first).taskId();
        var second = finding("finding-2", Severity.MEDIUM,
                signal("a", Severity.MEDIUM), signal("b", Severity.MEDIUM));

        var result = service.upsertFinding(second);
        RelationshipTask task = repository.task(taskId).orElseThrow();

        assertEquals(Outcome.EVIDENCE_ATTACHED, result.outcome());
        assertEquals(2, task.evidence().size());
        assertEquals(Severity.HIGH, task.severity());
        assertEquals(2, task.version());
        assertEquals(List.of("b".repeat(64)), task.history().getLast().addedFingerprints());

        service.accept(taskId, 2, AGENT, "accept-correlation");
        var duplicate = service.upsertFinding(
                finding("finding-3", Severity.MEDIUM, signal("b", Severity.MEDIUM)));
        assertEquals(Outcome.DUPLICATE, duplicate.outcome());
        assertEquals(3, repository.task(taskId).orElseThrow().version());

        var reviewAttachment = service.upsertFinding(
                finding("finding-4", Severity.LOW, signal("c", Severity.LOW)));
        RelationshipTask inReview = repository.task(taskId).orElseThrow();
        assertEquals(Outcome.EVIDENCE_ATTACHED, reviewAttachment.outcome());
        assertEquals(Status.IN_REVIEW, inReview.status());
        assertEquals(4, inReview.version());
    }

    @Test
    void deduplicatesFingerprintsAcrossClosedTasksAndPreservesHistory() {
        var original = finding("finding-1", Severity.LOW, signal("a", Severity.LOW));
        var originalResult = service.upsertFinding(original);
        String oldTaskId = originalResult.taskId();
        service.accept(oldTaskId, 1, AGENT, "accept-correlation");
        service.complete(oldTaskId, 2, AGENT, "complete-correlation", "VALID_COMPLETE", null);
        assertEquals(originalResult, service.upsertFinding(original));

        var duplicate = service.upsertFinding(
                finding("finding-2", Severity.LOW, signal("a", Severity.LOW)));
        var mixed = service.upsertFinding(finding("finding-3", Severity.HIGH,
                signal("a", Severity.LOW), signal("b", Severity.HIGH)));
        RelationshipTask oldTask = repository.task(oldTaskId).orElseThrow();
        RelationshipTask newTask = repository.task(mixed.taskId()).orElseThrow();

        assertEquals(Outcome.DUPLICATE, duplicate.outcome());
        assertEquals(oldTaskId, duplicate.taskId());
        assertEquals(Outcome.CREATED, mixed.outcome());
        assertEquals(1, newTask.evidence().size());
        assertEquals("b".repeat(64), newTask.evidence().getFirst().fingerprint());
        assertEquals(Severity.HIGH, newTask.severity());
        assertEquals(1, oldTask.evidence().size());
        assertEquals(3, oldTask.history().size());
    }

    @Test
    void enforcesStateMachineOwnershipVersionsAndDispositionPolicy() {
        String taskId = service.upsertFinding(
                finding("finding-1", Severity.LOW, signal("a", Severity.LOW))).taskId();

        assertThrows(RelationshipTask.TaskAccessException.class,
                () -> service.accept(taskId, 1, "other-agent", "correlation"));
        assertThrows(IllegalArgumentException.class,
                () -> service.dismiss(taskId, 1, AGENT, "correlation", "BAD", null));
        RelationshipTask accepted = service.accept(taskId, 1, AGENT, "accept-correlation");
        assertThrows(RelationshipTask.OptimisticLockException.class,
                () -> service.dismiss(taskId, 1, AGENT, "correlation", "VALID_DISMISS", null));
        RelationshipTask completed = service.complete(
                taskId, 2, AGENT, "complete-correlation", "VALID_COMPLETE", "reviewed");
        assertThrows(RelationshipTask.InvalidTransitionException.class,
                () -> service.dismiss(taskId, 3, AGENT, "correlation", "VALID_DISMISS", null));

        assertEquals(Status.IN_REVIEW, accepted.status());
        assertEquals(Status.COMPLETED, completed.status());
        assertEquals(3, completed.version());
        assertEquals(3, completed.history().size());
    }

    @Test
    void dismissesFromOpenOrInReviewAndMakesBothTasksTerminal() {
        String openTask = service.upsertFinding(
                finding("finding-open", Severity.LOW, signal("a", Severity.LOW))).taskId();
        RelationshipTask openDismissed = service.dismiss(
                openTask, 1, AGENT, "dismiss-open", "VALID_DISMISS", null);

        String reviewTask = service.upsertFinding(
                finding("finding-review", Severity.MEDIUM, signal("b", Severity.MEDIUM))).taskId();
        service.accept(reviewTask, 1, AGENT, "accept-review");
        RelationshipTask reviewDismissed = service.dismiss(
                reviewTask, 2, AGENT, "dismiss-review", "VALID_DISMISS", "expected activity");

        assertEquals(Status.DISMISSED, openDismissed.status());
        assertEquals(Status.DISMISSED, reviewDismissed.status());
        assertEquals(3, reviewDismissed.version());
    }

    @Test
    void suppressesDuringCooldownIdempotentlyAndAllowsHigherSeverity() {
        String taskId = service.upsertFinding(
                finding("finding-low", Severity.LOW, signal("a", Severity.LOW))).taskId();
        service.accept(taskId, 1, AGENT, "accept-correlation");
        service.complete(taskId, 2, AGENT, "complete-correlation", "VALID_COMPLETE", null);

        var cooldownFinding = finding("finding-cooldown", Severity.LOW, signal("b", Severity.LOW));
        var suppressed = service.upsertFinding(cooldownFinding);
        var replay = service.upsertFinding(cooldownFinding);
        var worsening = service.upsertFinding(
                finding("finding-high", Severity.HIGH, signal("c", Severity.HIGH)));

        assertEquals(Outcome.SUPPRESSED_COOLDOWN, suppressed.outcome());
        assertEquals(suppressed, replay);
        assertEquals(Outcome.CREATED, worsening.outcome());
        assertEquals(2, repository.taskCount());
        assertEquals(3, repository.processedFindingCount());
    }

    @Test
    void capsNewTasksPerAgentButStillAttachesEvidence() {
        for (int index = 0; index < 20; index++) {
            var result = service.upsertFinding(finding(
                    "client-" + index, "finding-" + index,
                    Severity.LOW, signal("a", Severity.LOW)));
            assertEquals(Outcome.CREATED, result.outcome());
        }

        var capped = service.upsertFinding(finding(
                "client-20", "finding-20", Severity.LOW, signal("a", Severity.LOW)));
        var attachment = service.upsertFinding(finding(
                "client-0", "finding-attachment", Severity.MEDIUM, signal("b", Severity.MEDIUM)));

        assertEquals(Outcome.SUPPRESSED_DAILY_CAP, capped.outcome());
        assertEquals(Outcome.EVIDENCE_ATTACHED, attachment.outcome());
        assertEquals(20, repository.taskCount());
        assertEquals(22, repository.processedFindingCount());
    }

    @Test
    void refusesUnavailableInactiveAndChangedAssignmentsWithoutMutation() {
        RelationshipTaskService unavailable = service(clientId -> {
            throw new ClientAssignmentPort.AssignmentUnavailableException("down");
        }, (code, note) -> { });
        assertThrows(ClientAssignmentPort.AssignmentUnavailableException.class,
                () -> unavailable.upsertFinding(finding("finding-down", Severity.LOW, signal("c", Severity.LOW))));
        assertEquals(0, repository.taskCount());

        RelationshipTaskService inactive = service(
                clientId -> assignment(clientId, AGENT, false), (code, note) -> { });
        assertThrows(ClientAssignmentPort.AssignmentRejectedException.class,
                () -> inactive.upsertFinding(finding("finding-inactive", Severity.LOW, signal("d", Severity.LOW))));

        service.upsertFinding(finding("finding-1", Severity.LOW, signal("a", Severity.LOW)));
        RelationshipTaskService reassigned = service(
                clientId -> assignment(clientId, "new-agent", true), (code, note) -> { });
        assertThrows(ClientAssignmentPort.AssignmentRejectedException.class,
                () -> reassigned.upsertFinding(finding("finding-2", Severity.LOW, signal("b", Severity.LOW))));
        assertEquals(1, repository.taskCount());
        assertEquals(1, repository.processedFindingCount());
    }

    @Test
    void rejectsFindingIdReuseWithDifferentContent() {
        service.upsertFinding(finding("same-id", Severity.LOW, signal("a", Severity.LOW)));
        assertThrows(RelationshipTaskService.IdempotencyConflictException.class,
                () -> service.upsertFinding(finding("same-id", Severity.MEDIUM, signal("b", Severity.MEDIUM))));
    }

    @Test
    void retriesConflictsAndRollsBackFailedAtomicWrite() {
        repository.injectConflicts(2);
        var created = service.upsertFinding(finding("finding-retry", Severity.LOW, signal("a", Severity.LOW)));
        assertEquals(Outcome.CREATED, created.outcome());

        repository.failProcessedFindingSave(true);
        assertThrows(IllegalStateException.class,
                () -> service.upsertFinding(finding(
                        "client-fail", "finding-fail", Severity.MEDIUM, signal("b", Severity.MEDIUM))));
        RelationshipTask stored = repository.task(created.taskId()).orElseThrow();
        assertEquals(1, stored.version());
        assertEquals(1, stored.evidence().size());
        assertEquals(1, repository.processedFindingCount());
        assertEquals(1, repository.creationCount(AGENT, LocalDate.of(2026, 9, 29)));
    }

    private RelationshipTaskService service(ClientAssignmentPort assignments, bank.clienthealth.task.port.DispositionPolicy policy) {
        return new RelationshipTaskService(repository, assignments, policy,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "task-" + taskSequence.incrementAndGet());
    }

    private static AssignmentSnapshot assignment(String clientId, String agent, boolean active) {
        return new AssignmentSnapshot(clientId, agent, active, 1, NOW.minusSeconds(60));
    }

    @SafeVarargs
    private static ClientHealthFinding finding(
            String findingId, Severity highest, SignalEvidence<? extends ClientHealthFinding.EvidenceDetails>... signals
    ) {
        return finding(CLIENT, findingId, highest, signals);
    }

    @SafeVarargs
    private static ClientHealthFinding finding(
            String clientId,
            String findingId,
            Severity highest,
            SignalEvidence<? extends ClientHealthFinding.EvidenceDetails>... signals
    ) {
        Instant start = NOW.minusSeconds(120 * 86_400L);
        Instant end = NOW.minusSeconds(60);
        return new ClientHealthFinding("1", findingId, clientId, "run-" + findingId,
                "rules-v1", new AnalysisWindow(start, end), List.of(signals),
                new PriorityInputs(highest, signals.length), "correlation-" + findingId, NOW);
    }

    private static SignalEvidence<RecurringCreditInterruptionEvidence> signal(String seed, Severity severity) {
        Instant start = NOW.minusSeconds(120 * 86_400L);
        Instant midpoint = NOW.minusSeconds(30 * 86_400L);
        Instant end = NOW.minusSeconds(60);
        return new SignalEvidence<>("signal-" + seed, "account-1",
                SignalType.RECURRING_CREDIT_INTERRUPTION, "rule-v1", severity,
                new SignalWindow(start, midpoint, midpoint, end),
                new RecurringCreditInterruptionEvidence(30, 3, 67), seed.repeat(64));
    }
}
