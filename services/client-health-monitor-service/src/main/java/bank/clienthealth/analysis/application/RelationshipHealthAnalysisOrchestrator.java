package bank.clienthealth.analysis.application;

import bank.clienthealth.activity.application.ActivityBaselineService;
import bank.clienthealth.activity.application.ActivityProjectionService;
import bank.clienthealth.analysis.port.AnalysisRunPort;
import bank.clienthealth.analysis.port.AnalysisRunPort.AcquisitionStatus;
import bank.clienthealth.analysis.port.AnalysisRunPort.FailureCategory;
import bank.clienthealth.analysis.port.AnalysisRunPort.Lease;
import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.EvidenceDetails;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.RelationshipTaskPort;
import bank.clienthealth.contract.TaskUpsertResult;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.rules.DetectedSignal;
import bank.clienthealth.rules.RelationshipHealthRuleEngine;
import bank.clienthealth.transaction.application.TransactionReplayService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Retry-safe RH-SF04 pipeline from replay through task-stream handoff. */
public final class RelationshipHealthAnalysisOrchestrator {
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(60);

    private final TransactionReplayService replayService;
    private final ActivityProjectionService projectionService;
    private final ActivityBaselineService baselineService;
    private final RelationshipHealthRuleEngine ruleEngine;
    private final RelationshipTaskPort taskPort;
    private final AnalysisRunPort runPort;
    private final Clock clock;
    private final Duration leaseDuration;

    public RelationshipHealthAnalysisOrchestrator(
            TransactionReplayService replayService,
            ActivityProjectionService projectionService,
            ActivityBaselineService baselineService,
            RelationshipHealthRuleEngine ruleEngine,
            RelationshipTaskPort taskPort,
            AnalysisRunPort runPort,
            Clock clock,
            Duration leaseDuration
    ) {
        this.replayService = Objects.requireNonNull(replayService);
        this.projectionService = Objects.requireNonNull(projectionService);
        this.baselineService = Objects.requireNonNull(baselineService);
        this.ruleEngine = Objects.requireNonNull(ruleEngine);
        this.taskPort = Objects.requireNonNull(taskPort);
        this.runPort = Objects.requireNonNull(runPort);
        this.clock = Objects.requireNonNull(clock);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    public RunResult run(LocalDate analysisDate) {
        Objects.requireNonNull(analysisDate, "analysisDate is required");
        String runId = "analysis-" + analysisDate + "-" + ruleEngine.rulesetVersion();
        Instant cutoff = ActivityBaselineService.analysisCutoff(analysisDate);
        Instant historyStart = ActivityBaselineService.historyStart(analysisDate);
        var acquisition = runPort.acquire(
                runId, cutoff, clock.instant(), leaseDuration);
        if (acquisition.status() == AcquisitionStatus.ALREADY_SUCCEEDED) {
            return new RunResult(runId, RunStatus.ALREADY_COMPLETED, Map.of());
        }
        if (acquisition.status() == AcquisitionStatus.LEASE_HELD) {
            return new RunResult(runId, RunStatus.LEASE_HELD, Map.of());
        }

        Lease lease = acquisition.lease();
        try {
            var replay = stage(FailureCategory.REPLAY,
                    () -> replayService.replay(historyStart, cutoff));
            stage(FailureCategory.PROJECTION, () -> projectionService.apply(replay));
            var baseline = stage(FailureCategory.BASELINE,
                    () -> baselineService.calculate(analysisDate));
            var evaluation = stage(FailureCategory.RULE_EVALUATION,
                    () -> ruleEngine.evaluate(baseline));
            lease = runPort.renew(lease, clock.instant(), leaseDuration);

            Map<String, List<DetectedSignal>> byClient = evaluation.outcomes().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            DetectedSignal::clientId, TreeMap::new,
                            java.util.stream.Collectors.toList()));
            EnumMap<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);
            for (var clientSignals : byClient.entrySet()) {
                lease = runPort.renew(lease, clock.instant(), leaseDuration);
                ClientHealthFinding finding = finding(
                        runId, evaluation.rulesetVersion(), historyStart, cutoff,
                        clientSignals.getKey(), clientSignals.getValue());
                TaskUpsertResult handled = stage(FailureCategory.TASK_HANDOFF,
                        () -> Objects.requireNonNull(
                                taskPort.upsertFinding(finding),
                                "task port returned no explicit outcome"));
                requireExplicitOutcome(handled.outcome());
                outcomes.merge(handled.outcome(), 1L, Long::sum);
            }
            runPort.succeed(lease, cutoff, clock.instant());
            return new RunResult(runId, RunStatus.COMPLETED, outcomes);
        } catch (AnalysisRunException failure) {
            fail(lease, failure.category(), failure);
            throw failure;
        } catch (RuntimeException failure) {
            fail(lease, FailureCategory.UNEXPECTED, failure);
            throw new AnalysisRunException(FailureCategory.UNEXPECTED, failure);
        }
    }

    private static ClientHealthFinding finding(
            String runId,
            String rulesetVersion,
            Instant historyStart,
            Instant cutoff,
            String clientId,
            List<DetectedSignal> detected
    ) {
        List<DetectedSignal> sorted = detected.stream()
                .sorted(Comparator.comparing(DetectedSignal::accountId)
                        .thenComparing(signal -> signal.type().name())
                        .thenComparing(DetectedSignal::ruleVersion))
                .toList();
        List<SignalEvidence<? extends EvidenceDetails>> mutableSignals = new ArrayList<>();
        sorted.forEach(signal -> mutableSignals.add(evidence(runId, clientId, signal)));
        List<SignalEvidence<? extends EvidenceDetails>> signals = List.copyOf(mutableSignals);
        Severity highest = sorted.stream()
                .map(DetectedSignal::severity)
                .max(Severity::compareTo)
                .orElseThrow();
        return new ClientHealthFinding(
                ClientHealthFinding.SCHEMA_VERSION,
                "finding-" + hash(runId + "|" + clientId),
                clientId,
                runId,
                rulesetVersion,
                new ClientHealthFinding.AnalysisWindow(historyStart, cutoff),
                signals,
                new PriorityInputs(highest, signals.size()),
                "correlation-" + hash(runId + "|" + clientId),
                cutoff);
    }

    private static SignalEvidence<? extends EvidenceDetails> evidence(
            String runId, String clientId, DetectedSignal signal
    ) {
        String identity = clientId + "|" + signal.accountId() + "|"
                + signal.type() + "|" + signal.ruleVersion() + "|"
                + signal.window().baselineStart() + "|" + signal.window().baselineEnd()
                + "|" + signal.window().comparisonStart() + "|"
                + signal.window().comparisonEnd();
        return new SignalEvidence<>(
                "signal-" + hash(runId + "|" + identity),
                signal.accountId(),
                signal.type(),
                signal.ruleVersion(),
                signal.severity(),
                signal.window(),
                signal.evidence(),
                hash(identity));
    }

    private <T> T stage(FailureCategory category, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            throw new AnalysisRunException(category, failure);
        }
    }

    private void fail(Lease lease, FailureCategory category, RuntimeException original) {
        try {
            runPort.fail(lease, category, clock.instant());
        } catch (RuntimeException runFailure) {
            original.addSuppressed(runFailure);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static void requireExplicitOutcome(Outcome outcome) {
        switch (outcome) {
            case CREATED, EVIDENCE_ATTACHED, DUPLICATE,
                    SUPPRESSED_COOLDOWN, SUPPRESSED_DAILY_CAP -> {
                // Every current outcome is explicit and permits run completion.
            }
        }
    }

    public record RunResult(
            String runId,
            RunStatus status,
            Map<Outcome, Long> taskOutcomes
    ) {
        public RunResult {
            taskOutcomes = Map.copyOf(taskOutcomes);
        }
    }

    public enum RunStatus {
        COMPLETED,
        ALREADY_COMPLETED,
        LEASE_HELD
    }

    public static final class AnalysisRunException extends RuntimeException {
        private final FailureCategory category;

        public AnalysisRunException(FailureCategory category, Throwable cause) {
            super("Client Health analysis failed at " + category, cause);
            this.category = category;
        }

        public FailureCategory category() {
            return category;
        }
    }
}
