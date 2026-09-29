package bank.clienthealth.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable handoff from Garvit's detection stream to Swayam's task stream.
 *
 * <p>One finding represents one client and one analysis run. It contains every
 * signal that qualified during that run. The task stream must consume it through
 * {@link RelationshipTaskPort}; it must not recalculate signal rules.</p>
 */
public record ClientHealthFinding(
        String schemaVersion,
        String findingId,
        String clientId,
        String analysisRunId,
        String rulesetVersion,
        AnalysisWindow analysisWindow,
        List<SignalEvidence<? extends EvidenceDetails>> signals,
        PriorityInputs priorityInputs,
        String correlationId,
        Instant detectedAt
) {

    public static final String SCHEMA_VERSION = "1";

    public ClientHealthFinding {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported ClientHealthFinding schema version: " + schemaVersion);
        }
        findingId = requireIdentifier(findingId, "findingId");
        clientId = requireIdentifier(clientId, "clientId");
        analysisRunId = requireIdentifier(analysisRunId, "analysisRunId");
        rulesetVersion = requireIdentifier(rulesetVersion, "rulesetVersion");
        analysisWindow = Objects.requireNonNull(analysisWindow, "analysisWindow is required");
        correlationId = requireIdentifier(correlationId, "correlationId");
        detectedAt = Objects.requireNonNull(detectedAt, "detectedAt is required");

        if (detectedAt.isBefore(analysisWindow.end())) {
            throw new IllegalArgumentException("detectedAt cannot be before the analysis window ends");
        }

        signals = List.copyOf(Objects.requireNonNull(signals, "signals are required"));
        if (signals.isEmpty()) {
            throw new IllegalArgumentException("At least one signal is required");
        }

        Set<String> fingerprints = new HashSet<>();
        for (SignalEvidence<? extends EvidenceDetails> signal : signals) {
            Objects.requireNonNull(signal, "signals cannot contain null");
            if (!analysisWindow.contains(signal.window())) {
                throw new IllegalArgumentException("Signal window must be inside the analysis window");
            }
            if (!fingerprints.add(signal.fingerprint())) {
                throw new IllegalArgumentException("Duplicate signal fingerprint: " + signal.fingerprint());
            }
        }

        priorityInputs = Objects.requireNonNull(priorityInputs, "priorityInputs are required");
        if (priorityInputs.signalCount() != signals.size()) {
            throw new IllegalArgumentException("priorityInputs.signalCount must equal the number of signals");
        }

        Severity highestSeverity = signals.stream()
                .map(SignalEvidence::severity)
                .max(Severity::compareTo)
                .orElseThrow();
        if (priorityInputs.highestSeverity() != highestSeverity) {
            throw new IllegalArgumentException("priorityInputs.highestSeverity must match the highest signal severity");
        }
    }

    public record AnalysisWindow(Instant start, Instant end) {
        public AnalysisWindow {
            start = Objects.requireNonNull(start, "analysis window start is required");
            end = Objects.requireNonNull(end, "analysis window end is required");
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("analysis window start must be before end");
            }
        }

        boolean contains(SignalWindow signalWindow) {
            return !signalWindow.baselineStart().isBefore(start)
                    && !signalWindow.comparisonEnd().isAfter(end);
        }
    }

    public record SignalWindow(
            Instant baselineStart,
            Instant baselineEnd,
            Instant comparisonStart,
            Instant comparisonEnd
    ) {
        public SignalWindow {
            baselineStart = Objects.requireNonNull(baselineStart, "baselineStart is required");
            baselineEnd = Objects.requireNonNull(baselineEnd, "baselineEnd is required");
            comparisonStart = Objects.requireNonNull(comparisonStart, "comparisonStart is required");
            comparisonEnd = Objects.requireNonNull(comparisonEnd, "comparisonEnd is required");

            if (!baselineStart.isBefore(baselineEnd)) {
                throw new IllegalArgumentException("baselineStart must be before baselineEnd");
            }
            if (!comparisonStart.isBefore(comparisonEnd)) {
                throw new IllegalArgumentException("comparisonStart must be before comparisonEnd");
            }
            if (baselineEnd.isAfter(comparisonStart)) {
                throw new IllegalArgumentException("baseline and comparison windows cannot overlap");
            }
        }
    }

    public record SignalEvidence<T extends EvidenceDetails>(
            String signalId,
            String accountId,
            SignalType type,
            String ruleVersion,
            Severity severity,
            SignalWindow window,
            T evidence,
            String fingerprint
    ) {
        public SignalEvidence {
            signalId = requireIdentifier(signalId, "signalId");
            accountId = requireIdentifier(accountId, "accountId");
            type = Objects.requireNonNull(type, "type is required");
            ruleVersion = requireIdentifier(ruleVersion, "ruleVersion");
            severity = Objects.requireNonNull(severity, "severity is required");
            window = Objects.requireNonNull(window, "window is required");
            evidence = Objects.requireNonNull(evidence, "evidence is required");

            if (!type.evidenceType().isInstance(evidence)) {
                throw new IllegalArgumentException(
                        "Signal type %s requires evidence type %s".formatted(
                                type, type.evidenceType().getSimpleName()));
            }

            fingerprint = requireFingerprint(fingerprint);
        }
    }

    public sealed interface EvidenceDetails permits
            RecurringCreditInterruptionEvidence,
            TransactionActivityDeclineEvidence,
            SustainedNetOutflowEvidence {
    }

    public record RecurringCreditInterruptionEvidence(
            int expectedCadenceDays,
            int previousOccurrenceCount,
            int daysSinceLastOccurrence
    ) implements EvidenceDetails {
        public RecurringCreditInterruptionEvidence {
            if (expectedCadenceDays <= 0) {
                throw new IllegalArgumentException("expectedCadenceDays must be positive");
            }
            if (previousOccurrenceCount < 3) {
                throw new IllegalArgumentException("At least three previous occurrences are required");
            }
            if (daysSinceLastOccurrence < 0) {
                throw new IllegalArgumentException("daysSinceLastOccurrence cannot be negative");
            }
        }
    }

    public record TransactionActivityDeclineEvidence(
            int baselineMonthlyAverageCount,
            int comparisonCount,
            BigDecimal changePercent
    ) implements EvidenceDetails {
        public TransactionActivityDeclineEvidence {
            if (baselineMonthlyAverageCount <= 0) {
                throw new IllegalArgumentException("baselineMonthlyAverageCount must be positive");
            }
            if (comparisonCount < 0) {
                throw new IllegalArgumentException("comparisonCount cannot be negative");
            }
            changePercent = requireScale(changePercent, "changePercent", 2);
            if (changePercent.signum() >= 0 || changePercent.compareTo(new BigDecimal("-100.00")) < 0) {
                throw new IllegalArgumentException("changePercent must be between -100.00 and 0");
            }
        }
    }

    public record SustainedNetOutflowEvidence(
            int periodCount,
            BigDecimal totalDeposits,
            BigDecimal totalWithdrawals,
            BigDecimal netFlow,
            String currency,
            BigDecimal changeFromBaselinePercent
    ) implements EvidenceDetails {
        public SustainedNetOutflowEvidence {
            if (periodCount < 2) {
                throw new IllegalArgumentException("At least two periods are required");
            }
            totalDeposits = requireNonNegativeMoney(totalDeposits, "totalDeposits");
            totalWithdrawals = requireNonNegativeMoney(totalWithdrawals, "totalWithdrawals");
            netFlow = requireScale(netFlow, "netFlow", 2);
            currency = requireCurrency(currency);
            changeFromBaselinePercent = requireScale(
                    changeFromBaselinePercent, "changeFromBaselinePercent", 2);

            if (netFlow.compareTo(totalDeposits.subtract(totalWithdrawals)) != 0) {
                throw new IllegalArgumentException("netFlow must equal totalDeposits minus totalWithdrawals");
            }
            if (netFlow.signum() >= 0) {
                throw new IllegalArgumentException("Sustained net-outflow evidence requires negative netFlow");
            }
            if (changeFromBaselinePercent.signum() >= 0) {
                throw new IllegalArgumentException("changeFromBaselinePercent must be negative");
            }
        }
    }

    public record PriorityInputs(Severity highestSeverity, int signalCount) {
        public PriorityInputs {
            highestSeverity = Objects.requireNonNull(highestSeverity, "highestSeverity is required");
            if (signalCount <= 0) {
                throw new IllegalArgumentException("signalCount must be positive");
            }
        }
    }

    public enum SignalType {
        RECURRING_CREDIT_INTERRUPTION(RecurringCreditInterruptionEvidence.class),
        TRANSACTION_ACTIVITY_DECLINE(TransactionActivityDeclineEvidence.class),
        SUSTAINED_NET_OUTFLOW(SustainedNetOutflowEvidence.class);

        private final Class<? extends EvidenceDetails> evidenceType;

        SignalType(Class<? extends EvidenceDetails> evidenceType) {
            this.evidenceType = evidenceType;
        }

        Class<? extends EvidenceDetails> evidenceType() {
            return evidenceType;
        }
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    private static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a non-blank opaque identifier");
        }
        return value;
    }

    private static String requireFingerprint(String value) {
        if (value == null || !FINGERPRINT_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 hex string");
        }
        return value;
    }

    private static String requireCurrency(String value) {
        if (value == null || !CURRENCY_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("currency must be a three-letter uppercase code");
        }
        return value;
    }

    private static BigDecimal requireNonNegativeMoney(BigDecimal value, String field) {
        value = requireScale(value, field, 2);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return value;
    }

    private static BigDecimal requireScale(BigDecimal value, String field, int maximumScale) {
        Objects.requireNonNull(value, field + " is required");
        if (value.scale() > maximumScale) {
            throw new IllegalArgumentException(field + " cannot have more than " + maximumScale + " decimals");
        }
        return value;
    }
}
