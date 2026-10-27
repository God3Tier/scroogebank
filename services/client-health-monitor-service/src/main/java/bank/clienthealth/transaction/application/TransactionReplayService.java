package bank.clienthealth.transaction.application;

import bank.clienthealth.transaction.contract.MatchedTransaction;
import bank.clienthealth.transaction.contract.TransactionReplayPage;
import bank.clienthealth.transaction.port.TransactionReplaySource;
import bank.clienthealth.metrics.ClientHealthMetrics;
import bank.clienthealth.metrics.ClientHealthMetrics.ReplayOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Fetches and validates a complete bounded replay before exposing any result. */
public final class TransactionReplayService {
    private final TransactionReplaySource source;
    private final int pageSize;
    private final int maximumPages;
    private final int maximumTransactions;
    private final ClientHealthMetrics metrics;

    public TransactionReplayService(
            TransactionReplaySource source,
            int pageSize,
            int maximumPages,
            int maximumTransactions
    ) {
        this(source, pageSize, maximumPages, maximumTransactions, ClientHealthMetrics.noOp());
    }

    public TransactionReplayService(
            TransactionReplaySource source, int pageSize, int maximumPages,
            int maximumTransactions, ClientHealthMetrics metrics
    ) {
        this.source = Objects.requireNonNull(source, "source is required");
        if (pageSize < 1 || pageSize > 500 || maximumPages < 1 || maximumTransactions < 1) {
            throw new IllegalArgumentException("replay limits must be positive and pageSize at most 500");
        }
        this.pageSize = pageSize;
        this.maximumPages = maximumPages;
        this.maximumTransactions = maximumTransactions;
        this.metrics = Objects.requireNonNull(metrics);
    }

    public ReplayResult replay(Instant from, Instant to) {
        long started = System.nanoTime();
        if (from == null || to == null || !from.isBefore(to)) {
            metrics.replay(ReplayOutcome.INVALID_PAGE, 0, 0, 1, elapsed(started));
            throw new IllegalArgumentException("from must be before to");
        }

        var transactions = new LinkedHashMap<String, MatchedTransaction>();
        var cursors = new java.util.HashSet<String>();
        String cursor = null;
        int pages = 0;
        int fetchedPages = 0;

        try {
            do {
                if (++pages > maximumPages) {
                    throw new IncompleteReplayException(Reason.LIMIT_EXCEEDED, "Replay exceeded the page limit");
                }
                TransactionReplayPage page;
                try {
                    page = Objects.requireNonNull(
                            source.fetch(new TransactionReplaySource.Request(from, to, cursor, pageSize)),
                            "source returned a null page");
                } catch (RuntimeException failure) {
                    if (failure instanceof IncompleteReplayException incomplete) throw incomplete;
                    throw new IncompleteReplayException(Reason.FAILED, "Transaction replay page failed", failure);
                }
                fetchedPages++;
                if (page.items().size() > pageSize) {
                    throw new IncompleteReplayException(Reason.INVALID_PAGE,
                            "Provider exceeded the requested page size");
                }

                for (MatchedTransaction transaction : page.items()) {
                    if (transaction.transactionDate().isBefore(from)
                            || !transaction.transactionDate().isBefore(to)) {
                        throw new IncompleteReplayException(Reason.INVALID_PAGE,
                                "Provider returned an out-of-window transaction: " + transaction.transactionId());
                    }
                    MatchedTransaction previous = transactions.putIfAbsent(
                            transaction.transactionId(), transaction);
                    if (previous != null && !previous.equals(transaction)) {
                        throw new IncompleteReplayException(Reason.INVALID_PAGE,
                                "Provider returned conflicting transaction: " + transaction.transactionId());
                    }
                    if (transactions.size() > maximumTransactions) {
                        throw new IncompleteReplayException(Reason.LIMIT_EXCEEDED,
                                "Replay exceeded the transaction limit");
                    }
                }

                cursor = page.nextCursor();
                if (cursor != null && !cursors.add(cursor)) {
                    throw new IncompleteReplayException(Reason.INVALID_PAGE,
                            "Provider repeated a replay cursor");
                }
            } while (cursor != null);

            ReplayResult result = new ReplayResult(from, to, List.copyOf(transactions.values()), pages);
            metrics.replay(ReplayOutcome.COMPLETED, fetchedPages, transactions.size(), 0, elapsed(started));
            return result;
        } catch (IncompleteReplayException failure) {
            ReplayOutcome outcome = switch (failure.reason()) {
                case FAILED -> ReplayOutcome.FAILED;
                case LIMIT_EXCEEDED -> ReplayOutcome.LIMIT_EXCEEDED;
                case INVALID_PAGE -> ReplayOutcome.INVALID_PAGE;
            };
            metrics.replay(outcome, fetchedPages, transactions.size(), 1, elapsed(started));
            throw failure;
        }
    }

    private static Duration elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started); }

    public record ReplayResult(
            Instant from,
            Instant to,
            List<MatchedTransaction> transactions,
            int pagesFetched
    ) {
        public ReplayResult {
            transactions = List.copyOf(transactions);
        }
    }

    public static final class IncompleteReplayException extends RuntimeException {
        private final Reason reason;
        public IncompleteReplayException(String message) {
            this(Reason.FAILED, message);
        }

        public IncompleteReplayException(String message, Throwable cause) {
            this(Reason.FAILED, message, cause);
        }

        private IncompleteReplayException(Reason reason, String message) {
            super(message); this.reason = reason;
        }
        private IncompleteReplayException(Reason reason, String message, Throwable cause) {
            super(message, cause); this.reason = reason;
        }
        Reason reason() { return reason; }
    }

    private enum Reason { FAILED, LIMIT_EXCEEDED, INVALID_PAGE }
}
