package bank.clienthealth.transaction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bank.clienthealth.transaction.application.TransactionReplayService.IncompleteReplayException;
import bank.clienthealth.transaction.contract.MatchedTransaction;
import bank.clienthealth.transaction.contract.MatchedTransaction.ActivityKind;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import bank.clienthealth.transaction.contract.MatchedTransaction.TransactionType;
import bank.clienthealth.transaction.contract.TransactionReplayPage;
import bank.clienthealth.transaction.port.TransactionReplaySource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import bank.clienthealth.metrics.MicrometerClientHealthMetrics;

class TransactionReplayServiceTest {
    private static final Instant FROM = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void returnsOnlyAfterFollowingEveryPageAndDeduplicatesIdenticalRows() {
        var calls = new AtomicInteger();
        MatchedTransaction first = transaction("txn-1", FROM.plusSeconds(60), Status.COMPLETED);
        MatchedTransaction second = transaction("txn-2", FROM.plusSeconds(120), Status.PENDING);
        TransactionReplaySource source = request -> {
            calls.incrementAndGet();
            if (request.cursor() == null) {
                assertEquals(FROM, request.from());
                assertEquals(TO, request.to());
                assertEquals(100, request.limit());
                return page(List.of(first, first), "cursor-1");
            }
            assertEquals("cursor-1", request.cursor());
            return page(List.of(second), null);
        };

        var result = service(source).replay(FROM, TO);

        assertEquals(List.of(first, second), result.transactions());
        assertEquals(2, result.pagesFetched());
        assertEquals(2, calls.get());
    }

    @Test
    void recordsCompletedAndInvalidReplayCountsOnce() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientHealthMetrics(registry);
        var successful = new TransactionReplayService(
                request -> page(List.of(transaction("txn-1", FROM.plusSeconds(60), Status.COMPLETED)), null),
                100, 10, 1_000, metrics);
        successful.replay(FROM, TO);
        var invalid = new TransactionReplayService(
                request -> page(List.of(transaction("txn-2", TO, Status.COMPLETED)), null),
                100, 10, 1_000, metrics);
        assertThrows(IncompleteReplayException.class, () -> invalid.replay(FROM, TO));

        assertEquals(1, registry.get("client_health_replay_runs_total")
                .tag("outcome", "completed").counter().count());
        assertEquals(1, registry.get("client_health_replay_runs_total")
                .tag("outcome", "invalid_page").counter().count());
        assertEquals(1, registry.get("client_health_replay_transactions_total")
                .tag("outcome", "completed").counter().count());
        assertEquals(1, registry.get("client_health_replay_rejected_total")
                .tag("outcome", "invalid_page").counter().count());
    }

    @Test
    void rejectsConflictingRowsWithTheSameTransactionId() {
        MatchedTransaction pending = transaction("txn-1", FROM.plusSeconds(60), Status.PENDING);
        MatchedTransaction completed = transaction("txn-1", FROM.plusSeconds(60), Status.COMPLETED);
        TransactionReplaySource source = request -> request.cursor() == null
                ? page(List.of(pending), "next")
                : page(List.of(completed), null);

        assertThrows(IncompleteReplayException.class, () -> service(source).replay(FROM, TO));
    }

    @Test
    void rejectsOutOfWindowRowsAndRepeatedCursors() {
        TransactionReplaySource outside = request ->
                page(List.of(transaction("txn-1", TO, Status.COMPLETED)), null);
        assertThrows(IncompleteReplayException.class, () -> service(outside).replay(FROM, TO));

        TransactionReplaySource loop = request -> page(List.of(), "same-cursor");
        assertThrows(IncompleteReplayException.class, () -> service(loop).replay(FROM, TO));
    }

    @Test
    void wrapsProviderFailureWithoutReturningAPartialResult() {
        TransactionReplaySource source = request -> {
            if (request.cursor() == null) {
                return page(List.of(transaction("txn-1", FROM.plusSeconds(60), Status.COMPLETED)), "next");
            }
            throw new IllegalStateException("transaction-service unavailable");
        };

        IncompleteReplayException failure = assertThrows(
                IncompleteReplayException.class, () -> service(source).replay(FROM, TO));

        assertEquals("Transaction replay page failed", failure.getMessage());
        assertEquals(IllegalStateException.class, failure.getCause().getClass());
    }

    @Test
    void validatesProviderContractsAndSafetyBounds() {
        assertThrows(IllegalArgumentException.class, () -> new MatchedTransaction(
                "txn-1", "client-1", "account-1", TransactionType.DEPOSIT,
                new BigDecimal("-1.00"), "SGD", FROM, Status.COMPLETED,
                ActivityKind.AUTOMATED, "source-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransactionReplayPage("2", List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new TransactionReplaySource.Request(FROM, TO, null, 501));

        var oversized = new TransactionReplayService(
                request -> page(List.of(
                        transaction("txn-1", FROM.plusSeconds(60), Status.COMPLETED),
                        transaction("txn-2", FROM.plusSeconds(120), Status.COMPLETED)), null),
                1, 2, 10);
        assertThrows(IncompleteReplayException.class, () -> oversized.replay(FROM, TO));

        TransactionReplaySource endless = request ->
                page(List.of(transaction("txn-" + request.cursor(), FROM.plusSeconds(60), Status.COMPLETED)),
                        request.cursor() == null ? "1" : Integer.toString(Integer.parseInt(request.cursor()) + 1));
        var bounded = new TransactionReplayService(endless, 100, 2, 10);
        assertThrows(IncompleteReplayException.class, () -> bounded.replay(FROM, TO));
    }

    private static TransactionReplayService service(TransactionReplaySource source) {
        return new TransactionReplayService(source, 100, 10, 1_000);
    }

    private static TransactionReplayPage page(List<MatchedTransaction> items, String cursor) {
        return new TransactionReplayPage("1", items, cursor);
    }

    private static MatchedTransaction transaction(String id, Instant date, Status status) {
        return new MatchedTransaction(
                id, "client-1", "account-1", TransactionType.DEPOSIT,
                new BigDecimal("4250.00"), "SGD", date, status,
                ActivityKind.AUTOMATED, "synthetic-employer-1");
    }
}
