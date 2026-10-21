package bank.clienthealth.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.clienthealth.activity.application.ActivityBaselineService;
import bank.clienthealth.activity.application.ActivityBaselineService.AccountBaseline;
import bank.clienthealth.activity.application.ActivityBaselineService.ActivitySummary;
import bank.clienthealth.activity.application.ActivityBaselineService.BaselineResult;
import bank.clienthealth.activity.application.ActivityBaselineService.ClientBaseline;
import bank.clienthealth.activity.application.ActivityBaselineService.PeriodSummary;
import bank.clienthealth.activity.application.ActivityBaselineService.RecurringCredit;
import bank.clienthealth.activity.application.ActivityBaselineService.RecurringCreditSeries;
import bank.clienthealth.activity.application.ActivityProjectionService;
import bank.clienthealth.activity.support.InMemoryActivityProjectionRepository;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SustainedNetOutflowEvidence;
import bank.clienthealth.contract.ClientHealthFinding.TransactionActivityDeclineEvidence;
import bank.clienthealth.transaction.adapter.mock.SyntheticTransactionReplaySource;
import bank.clienthealth.transaction.application.TransactionReplayService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RelationshipHealthRuleEngineTest {
    private static final Instant HISTORY_START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant BASELINE_START = Instant.parse("2026-06-03T00:00:00Z");
    private static final Instant COMPARISON_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-01T00:00:00Z");
    private final RelationshipHealthRuleEngine engine =
            new RelationshipHealthRuleEngine(RelationshipHealthRuleset.v1());

    @Test
    void detectsThreeSyntheticSignalsAndNoHealthyControlSignal() {
        var repository = new InMemoryActivityProjectionRepository();
        var replay = new TransactionReplayService(
                SyntheticTransactionReplaySource.sixMonthScenario(), 10, 10, 100)
                .replay(HISTORY_START, CUTOFF);
        new ActivityProjectionService(repository).apply(replay);
        BaselineResult baseline = new ActivityBaselineService(repository)
                .calculate(LocalDate.of(2026, 10, 1));

        var evaluation = engine.evaluate(baseline);

        assertEquals("relationship-health-v1", evaluation.rulesetVersion());
        assertEquals(3, evaluation.outcomes().size());
        assertSignal(evaluation, "client-recurring",
                SignalType.RECURRING_CREDIT_INTERRUPTION, Severity.HIGH);
        assertSignal(evaluation, "client-activity",
                SignalType.TRANSACTION_ACTIVITY_DECLINE, Severity.MEDIUM);
        assertSignal(evaluation, "client-outflow",
                SignalType.SUSTAINED_NET_OUTFLOW, Severity.HIGH);
        assertTrue(evaluation.outcomes().stream()
                .noneMatch(signal -> "client-control".equals(signal.clientId())));
        var recurringEvidence = assertInstanceOf(
                RecurringCreditInterruptionEvidence.class,
                signal(evaluation, SignalType.RECURRING_CREDIT_INTERRUPTION).evidence());
        assertEquals(30, recurringEvidence.expectedCadenceDays());
        assertEquals(3, recurringEvidence.previousOccurrenceCount());
        assertEquals(121, recurringEvidence.daysSinceLastOccurrence());
        var activityEvidence = assertInstanceOf(
                TransactionActivityDeclineEvidence.class,
                signal(evaluation, SignalType.TRANSACTION_ACTIVITY_DECLINE).evidence());
        assertEquals(4, activityEvidence.baselineMonthlyAverageCount());
        assertEquals(1, activityEvidence.comparisonCount());
        assertEquals(new BigDecimal("-75.00"), activityEvidence.changePercent());
        var outflowEvidence = assertInstanceOf(
                SustainedNetOutflowEvidence.class,
                signal(evaluation, SignalType.SUSTAINED_NET_OUTFLOW).evidence());
        assertEquals(new BigDecimal("2000.00"), outflowEvidence.totalDeposits());
        assertEquals(new BigDecimal("6000.00"), outflowEvidence.totalWithdrawals());
        assertEquals(new BigDecimal("-4000.00"), outflowEvidence.netFlow());
        assertEquals(evaluation, engine.evaluate(baseline));
    }

    @Test
    void includesExactLowSeverityBoundaries() {
        List<RecurringCredit> occurrences = List.of(
                credit("2026-06-23T00:00:00Z", "1000.00"),
                credit("2026-07-23T00:00:00Z", "1000.00"),
                credit("2026-08-22T00:00:00Z", "1200.00"));
        List<PeriodSummary> periods = periods(
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "2000.00", "2500.00"),
                summary(0, 0, "2000.00", "2500.00"));
        BaselineResult baseline = baseline(
                summary(6, 0, "300.00", "0.00"),
                summary(1, 0, "0.00", "75.00"),
                List.of(new RecurringCreditSeries("source-1", occurrences)),
                periods);

        var outcomes = engine.evaluate(baseline).outcomes();

        assertEquals(3, outcomes.size());
        assertTrue(outcomes.stream().allMatch(signal -> signal.severity() == Severity.LOW));
    }

    @Test
    void suppressesJustBelowThresholdAndInsufficientInputs() {
        List<RecurringCredit> unstable = List.of(
                credit("2026-06-23T00:00:00Z", "1000.00"),
                credit("2026-07-23T00:00:00Z", "1000.00"),
                credit("2026-08-22T00:00:00Z", "1200.01"));
        List<PeriodSummary> periods = periods(
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "3000.00", "3499.99"),
                summary(0, 0, "3000.00", "3499.99"));
        BaselineResult baseline = baseline(
                summary(5, 1, "300.00", "0.00"),
                summary(1, 0, "0.00", "75.00"),
                List.of(new RecurringCreditSeries("source-1", unstable)),
                periods);

        assertTrue(engine.evaluate(baseline).outcomes().isEmpty());
    }

    private static void assertSignal(
            RelationshipHealthRuleEngine.RuleEvaluation evaluation,
            String client,
            SignalType type,
            Severity severity
    ) {
        assertTrue(evaluation.outcomes().stream().anyMatch(signal ->
                client.equals(signal.clientId())
                        && signal.type() == type
                        && signal.severity() == severity));
    }

    private static DetectedSignal signal(
            RelationshipHealthRuleEngine.RuleEvaluation evaluation, SignalType type
    ) {
        return evaluation.outcomes().stream()
                .filter(outcome -> outcome.type() == type)
                .findFirst()
                .orElseThrow();
    }

    private static BaselineResult baseline(
            ActivitySummary baseline,
            ActivitySummary comparison,
            List<RecurringCreditSeries> recurring,
            List<PeriodSummary> periods
    ) {
        AccountBaseline account = new AccountBaseline(
                "account-1", baseline, comparison, recurring, periods);
        ClientBaseline client = new ClientBaseline(
                "client-1", List.of(account), baseline, comparison, baseline.completedCount() > 0);
        return new BaselineResult(
                HISTORY_START, BASELINE_START, COMPARISON_START, CUTOFF,
                Map.of(client.clientId(), client));
    }

    private static List<PeriodSummary> periods(ActivitySummary... summaries) {
        List<PeriodSummary> periods = new ArrayList<>();
        for (int index = 0; index < summaries.length; index++) {
            Instant from = CUTOFF.minus(Duration.ofDays(30L * (summaries.length - index)));
            periods.add(new PeriodSummary(from, from.plus(Duration.ofDays(30)), summaries[index]));
        }
        return periods;
    }

    private static ActivitySummary summary(
            long customerCount,
            long unknownCount,
            String deposits,
            String withdrawals
    ) {
        BigDecimal deposit = new BigDecimal(deposits);
        BigDecimal withdrawal = new BigDecimal(withdrawals);
        return new ActivitySummary(
                customerCount + unknownCount, customerCount, 0, unknownCount,
                deposit, withdrawal, deposit.subtract(withdrawal), "SGD");
    }

    private static RecurringCredit credit(String timestamp, String amount) {
        return new RecurringCredit(Instant.parse(timestamp), new BigDecimal(amount));
    }
}
