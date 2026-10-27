package bank.clienthealth.activity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.clienthealth.activity.application.ActivityBaselineService.InsufficientCoverageException;
import bank.clienthealth.activity.application.ActivityBaselineService.UnsupportedCurrencyException;
import bank.clienthealth.activity.support.InMemoryActivityProjectionRepository;
import bank.clienthealth.transaction.adapter.mock.SyntheticTransactionReplaySource;
import bank.clienthealth.transaction.application.TransactionReplayService;
import bank.clienthealth.transaction.application.TransactionReplayService.ReplayResult;
import bank.clienthealth.transaction.contract.MatchedTransaction;
import bank.clienthealth.transaction.contract.MatchedTransaction.ActivityKind;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import bank.clienthealth.transaction.contract.MatchedTransaction.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityBaselineServiceTest {
    private static final Instant HISTORY_START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-01T00:00:00Z");
    private static final LocalDate ANALYSIS_DATE = LocalDate.of(2026, 10, 1);

    private InMemoryActivityProjectionRepository repository;
    private ActivityProjectionService projection;
    private ActivityBaselineService baselines;

    @BeforeEach
    void setUp() {
        repository = new InMemoryActivityProjectionRepository();
        projection = new ActivityProjectionService(repository);
        baselines = new ActivityBaselineService(repository);
    }

    @Test
    void buildsSingaporeAnchoredAccountAndClientSummariesFromSixMonthHistory() {
        ReplayResult replay = new TransactionReplayService(
                SyntheticTransactionReplaySource.sixMonthScenario(), 10, 10, 100)
                .replay(HISTORY_START, CUTOFF);
        projection.apply(replay);

        var result = baselines.calculate(ANALYSIS_DATE);

        assertEquals(HISTORY_START, result.historyStart());
        assertEquals(Instant.parse("2026-06-03T00:00:00Z"), result.baselineStart());
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), result.comparisonStart());
        assertEquals(CUTOFF, result.analysisCutoff());
        assertEquals(4, result.clients().size());

        var activity = result.clients().get("client-activity");
        assertEquals(12, activity.baseline().customerInitiatedCount());
        assertEquals(1, activity.comparison().customerInitiatedCount());
        assertEquals(new BigDecimal("-900.00"), activity.baseline().netFlow());
        assertEquals(new BigDecimal("-75.00"), activity.comparison().netFlow());
        assertTrue(activity.hasBaselineActivity());

        var recurring = result.clients().get("client-recurring");
        assertEquals(3, recurring.accounts().getFirst()
                .recurringCredits().getFirst().occurrences().size());
        assertFalse(recurring.hasBaselineActivity());

        var outflow = result.clients().get("client-outflow");
        assertEquals(new BigDecimal("0.00"), outflow.baseline().netFlow());
        assertEquals(new BigDecimal("-2000.00"), outflow.comparison().netFlow());
        assertEquals(6, outflow.accounts().getFirst().periods().size());
        assertEquals(new BigDecimal("-2000.00"),
                outflow.accounts().getFirst().periods().get(4).summary().netFlow());
        assertEquals(new BigDecimal("-2000.00"),
                outflow.accounts().getFirst().periods().get(5).summary().netFlow());

        var control = result.clients().get("client-control");
        assertEquals(1, control.comparison().completedCount());
        assertEquals(0, control.comparison().customerInitiatedCount());
        assertEquals(1, control.comparison().automatedCount());
    }

    @Test
    void rollsAccountsUpExactlyAndExcludesUnknownFromActivityCounts() {
        projection.apply(replay(
                transaction("customer", "account-1", TransactionType.WITHDRAWAL,
                        "100.00", ActivityKind.CUSTOMER_INITIATED, "2026-07-01T00:00:00Z", "SGD"),
                transaction("automated", "account-2", TransactionType.DEPOSIT,
                        "300.00", ActivityKind.AUTOMATED, "2026-07-02T00:00:00Z", "SGD"),
                transaction("unknown", "account-2", TransactionType.WITHDRAWAL,
                        "50.00", ActivityKind.UNKNOWN, "2026-07-03T00:00:00Z", "SGD")));

        var client = baselines.calculate(ANALYSIS_DATE).clients().get("client-1");

        assertEquals(2, client.accounts().size());
        assertEquals(3, client.baseline().completedCount());
        assertEquals(1, client.baseline().customerInitiatedCount());
        assertEquals(1, client.baseline().automatedCount());
        assertEquals(1, client.baseline().unknownCount());
        assertEquals(new BigDecimal("150.00"), client.baseline().netFlow());
        assertEquals(
                client.baseline().completedCount(),
                client.accounts().stream().mapToLong(account -> account.baseline().completedCount()).sum());
        assertEquals(
                client.baseline().netFlow(),
                client.accounts().stream()
                        .map(account -> account.baseline().netFlow())
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void failsClosedOnIncompleteCoverageOrUnsupportedCurrency() {
        assertThrows(InsufficientCoverageException.class,
                () -> baselines.calculate(ANALYSIS_DATE));

        projection.apply(replay(transaction(
                "usd", "account-1", TransactionType.DEPOSIT,
                "100.00", ActivityKind.AUTOMATED, "2026-07-01T00:00:00Z", "USD")));
        assertThrows(UnsupportedCurrencyException.class,
                () -> baselines.calculate(ANALYSIS_DATE));
    }

    private static ReplayResult replay(MatchedTransaction... transactions) {
        return new ReplayResult(HISTORY_START, CUTOFF, List.of(transactions), 1);
    }

    private static MatchedTransaction transaction(
            String id,
            String accountId,
            TransactionType type,
            String amount,
            ActivityKind activityKind,
            String timestamp,
            String currency
    ) {
        return new MatchedTransaction(
                id, "client-1", accountId, type, new BigDecimal(amount), currency,
                Instant.parse(timestamp), Status.COMPLETED, activityKind, null);
    }
}
