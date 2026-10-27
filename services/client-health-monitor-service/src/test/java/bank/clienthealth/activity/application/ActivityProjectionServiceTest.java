package bank.clienthealth.activity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bank.clienthealth.activity.application.ActivityProjectionService.ProjectionConflictException;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityProjectionServiceTest {
    private static final Instant FROM = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");

    private InMemoryActivityProjectionRepository repository;
    private ActivityProjectionService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryActivityProjectionRepository();
        service = new ActivityProjectionService(repository);
    }

    @Test
    void appliesCompleteReplayIdempotently() {
        ReplayResult replay = new TransactionReplayService(
                SyntheticTransactionReplaySource.sixMonthScenario(), 10, 10, 100)
                .replay(FROM, TO);

        var first = service.apply(replay);
        var repeated = service.apply(replay);

        assertEquals(new ActivityProjectionService.ApplyResult(44, 0, 0), first);
        assertEquals(new ActivityProjectionService.ApplyResult(0, 0, 44), repeated);
        assertEquals(44, repository.all().size());
    }

    @Test
    void permitsOnlyPendingToTerminalStatusUpdates() {
        service.apply(replay(transaction("txn-1", Status.PENDING, "50.00")));

        var completed = service.apply(replay(transaction("txn-1", Status.COMPLETED, "50.00")));

        assertEquals(new ActivityProjectionService.ApplyResult(0, 1, 0), completed);
        assertEquals(Status.COMPLETED, repository.all().getFirst().status());
        assertThrows(ProjectionConflictException.class,
                () -> service.apply(replay(transaction("txn-1", Status.PENDING, "50.00"))));
        assertEquals(Status.COMPLETED, repository.all().getFirst().status());

        service.apply(replay(transaction("txn-2", Status.PENDING, "25.00")));
        service.apply(replay(transaction("txn-2", Status.FAILED, "25.00")));
        assertEquals(Status.FAILED, repository.all().getLast().status());
    }

    @Test
    void rejectsImmutableChangesAndRollsBackWholeReplay() {
        service.apply(replay(transaction("txn-1", Status.COMPLETED, "50.00")));
        ReplayResult conflictingBatch = replay(
                transaction("txn-2", Status.COMPLETED, "10.00"),
                transaction("txn-1", Status.COMPLETED, "75.00"));

        assertThrows(ProjectionConflictException.class, () -> service.apply(conflictingBatch));

        assertEquals(1, repository.all().size());
        assertEquals(new BigDecimal("50.00"), repository.all().getFirst().amount());
    }

    private static ReplayResult replay(MatchedTransaction... transactions) {
        return new ReplayResult(FROM, TO, List.of(transactions), 1);
    }

    private static MatchedTransaction transaction(String id, Status status, String amount) {
        return new MatchedTransaction(
                id, "client-1", "account-1", TransactionType.WITHDRAWAL,
                new BigDecimal(amount), "SGD", FROM.plusSeconds(60), status,
                ActivityKind.CUSTOMER_INITIATED, null);
    }
}
