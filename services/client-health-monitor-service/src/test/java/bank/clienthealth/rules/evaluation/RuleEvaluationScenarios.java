package bank.clienthealth.rules.evaluation;

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
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.rules.evaluation.RuleEvaluationHarness.ExpectedSignal;
import bank.clienthealth.rules.evaluation.RuleEvaluationHarness.LabelledScenario;
import bank.clienthealth.rules.evaluation.RuleEvaluationHarness.SignalKey;
import bank.clienthealth.transaction.adapter.mock.SyntheticTransactionReplaySource;
import bank.clienthealth.transaction.application.TransactionReplayService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class RuleEvaluationScenarios {
    private static final Instant HISTORY_START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant BASELINE_START = Instant.parse("2026-06-03T00:00:00Z");
    private static final Instant COMPARISON_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-01T00:00:00Z");

    private RuleEvaluationScenarios() { }

    static List<LabelledScenario> all() {
        BaselineResult synthetic = syntheticBaseline();
        return List.of(
                new LabelledScenario("combined-positive", synthetic, List.of(
                        expected("client-recurring", "account-recurring",
                                SignalType.RECURRING_CREDIT_INTERRUPTION, Severity.HIGH),
                        expected("client-activity", "account-activity",
                                SignalType.TRANSACTION_ACTIVITY_DECLINE, Severity.MEDIUM),
                        expected("client-outflow", "account-outflow",
                                SignalType.SUSTAINED_NET_OUTFLOW, Severity.HIGH))),
                new LabelledScenario(
                        "healthy-control",
                        filterClient(synthetic, "client-control"),
                        List.of()),
                exactBoundary(),
                justBelowBoundary(),
                unknownClassification(),
                sparseHistory());
    }

    private static LabelledScenario exactBoundary() {
        List<RecurringCredit> recurring = List.of(
                credit("2026-06-23T00:00:00Z", "1000.00"),
                credit("2026-07-23T00:00:00Z", "1000.00"),
                credit("2026-08-22T00:00:00Z", "1200.00"));
        BaselineResult baseline = baseline(
                summary(6, 0, "300.00", "0.00"),
                summary(1, 0, "0.00", "75.00"),
                List.of(new RecurringCreditSeries("source-boundary", recurring)),
                periods(
                        summary(0, 0, "1000.00", "900.00"),
                        summary(0, 0, "1000.00", "900.00"),
                        summary(0, 0, "1000.00", "900.00"),
                        summary(0, 0, "1000.00", "900.00"),
                        summary(0, 0, "2000.00", "2500.00"),
                        summary(0, 0, "2000.00", "2500.00")));
        return new LabelledScenario("exact-low-boundaries", baseline, List.of(
                expected("client-1", "account-1",
                        SignalType.RECURRING_CREDIT_INTERRUPTION, Severity.LOW),
                expected("client-1", "account-1",
                        SignalType.TRANSACTION_ACTIVITY_DECLINE, Severity.LOW),
                expected("client-1", "account-1",
                        SignalType.SUSTAINED_NET_OUTFLOW, Severity.LOW)));
    }

    private static LabelledScenario justBelowBoundary() {
        List<RecurringCredit> unstable = List.of(
                credit("2026-06-23T00:00:00Z", "1000.00"),
                credit("2026-07-23T00:00:00Z", "1000.00"),
                credit("2026-08-22T00:00:00Z", "1200.01"));
        BaselineResult baseline = baseline(
                summary(5, 0, "300.00", "0.00"),
                summary(1, 0, "0.00", "75.00"),
                List.of(new RecurringCreditSeries("source-unstable", unstable)),
                periods(
                        summary(0, 0, "1000.00", "900.00"),
                        summary(0, 0, "1000.00", "900.00"),
                        summary(0, 0, "1000.00", "900.00"),
                        summary(0, 0, "1000.00", "900.00"),
                        summary(0, 0, "3000.00", "3499.99"),
                        summary(0, 0, "3000.00", "3499.99")));
        return new LabelledScenario("just-below-thresholds", baseline, List.of());
    }

    private static LabelledScenario unknownClassification() {
        BaselineResult baseline = baseline(
                summary(6, 1, "300.00", "100.00"),
                summary(1, 0, "100.00", "75.00"),
                List.of(),
                positivePeriods());
        return new LabelledScenario("unknown-activity-fails-closed", baseline, List.of());
    }

    private static LabelledScenario sparseHistory() {
        BaselineResult baseline = baseline(
                summary(5, 0, "300.00", "100.00"),
                summary(0, 0, "100.00", "75.00"),
                List.of(),
                positivePeriods());
        return new LabelledScenario("sparse-history-suppressed", baseline, List.of());
    }

    private static List<PeriodSummary> positivePeriods() {
        return periods(
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"),
                summary(0, 0, "1000.00", "900.00"));
    }

    private static BaselineResult syntheticBaseline() {
        var repository = new InMemoryActivityProjectionRepository();
        var replay = new TransactionReplayService(
                SyntheticTransactionReplaySource.sixMonthScenario(), 10, 10, 100)
                .replay(HISTORY_START, CUTOFF);
        new ActivityProjectionService(repository).apply(replay);
        return new ActivityBaselineService(repository).calculate(LocalDate.of(2026, 10, 1));
    }

    private static BaselineResult filterClient(BaselineResult source, String clientId) {
        ClientBaseline client = source.clients().get(clientId);
        return new BaselineResult(
                source.historyStart(), source.baselineStart(), source.comparisonStart(),
                source.analysisCutoff(), Map.of(clientId, client));
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
            long customerCount, long unknownCount, String deposits, String withdrawals
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

    private static ExpectedSignal expected(
            String clientId, String accountId, SignalType type, Severity severity
    ) {
        return new ExpectedSignal(new SignalKey(clientId, accountId, type), severity);
    }
}
