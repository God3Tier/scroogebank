package bank.clienthealth.transaction.adapter.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank.clienthealth.transaction.application.TransactionReplayService;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import bank.clienthealth.transaction.port.TransactionReplaySource.Request;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SyntheticTransactionReplaySourceTest {
    private static final Instant FROM = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void providesCompleteDeterministicSixMonthScenariosAcrossPages() {
        var source = SyntheticTransactionReplaySource.sixMonthScenario();
        var result = new TransactionReplayService(source, 7, 20, 100).replay(FROM, TO);

        assertEquals(44, result.transactions().size());
        assertEquals(7, result.pagesFetched());
        assertTrue(result.transactions().stream()
                .allMatch(transaction -> "SGD".equals(transaction.currency())));

        var recurring = result.transactions().stream()
                .filter(transaction -> "client-recurring".equals(transaction.clientId()))
                .toList();
        assertEquals(3, recurring.size());
        assertTrue(recurring.stream().allMatch(transaction ->
                "synthetic-employer-001".equals(transaction.sourceFingerprint())));
        assertEquals(6, recurring.getLast().transactionDate().atZone(java.time.ZoneOffset.UTC).getMonthValue());

        long healthyCredits = result.transactions().stream()
                .filter(transaction -> "client-control".equals(transaction.clientId()))
                .filter(transaction -> "synthetic-employer-control".equals(transaction.sourceFingerprint()))
                .count();
        assertEquals(6, healthyCredits);

        long activityBaseline = result.transactions().stream()
                .filter(transaction -> "client-activity".equals(transaction.clientId()))
                .filter(transaction -> transaction.transactionDate().isBefore(
                        Instant.parse("2026-09-01T00:00:00Z")))
                .count();
        long activitySeptember = result.transactions().stream()
                .filter(transaction -> "client-activity".equals(transaction.clientId()))
                .filter(transaction -> !transaction.transactionDate().isBefore(
                        Instant.parse("2026-09-01T00:00:00Z")))
                .count();
        assertEquals(20, activityBaseline);
        assertEquals(1, activitySeptember);

        for (int month : new int[]{8, 9}) {
            BigDecimal netFlow = result.transactions().stream()
                    .filter(transaction -> "client-outflow".equals(transaction.clientId()))
                    .filter(transaction -> transaction.transactionDate()
                            .atZone(java.time.ZoneOffset.UTC).getMonthValue() == month)
                    .map(transaction -> transaction.type()
                            == bank.clienthealth.transaction.contract.MatchedTransaction.TransactionType.DEPOSIT
                            ? transaction.amount() : transaction.amount().negate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(new BigDecimal("-2000.00"), netFlow);
        }

        assertEquals(1, result.transactions().stream()
                .filter(transaction -> transaction.status() == Status.PENDING).count());
        assertEquals(1, result.transactions().stream()
                .filter(transaction -> transaction.status() == Status.FAILED).count());
    }

    @Test
    void rejectsUnknownAndOutOfRangeCursors() {
        var source = SyntheticTransactionReplaySource.sixMonthScenario();

        assertThrows(IllegalArgumentException.class,
                () -> source.fetch(new Request(FROM, TO, "other:2", 10)));
        assertThrows(IllegalArgumentException.class,
                () -> source.fetch(new Request(FROM, TO, "offset:999", 10)));
    }
}
