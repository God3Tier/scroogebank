package bank.clienthealth.transaction.application;

import bank.clienthealth.transaction.contract.MatchedTransaction;
import bank.clienthealth.transaction.contract.TransactionReplayPage;
import bank.clienthealth.transaction.port.TransactionReplaySource;
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

    public TransactionReplayService(
            TransactionReplaySource source,
            int pageSize,
            int maximumPages,
            int maximumTransactions
    ) {
        this.source = Objects.requireNonNull(source, "source is required");
        if (pageSize < 1 || pageSize > 500 || maximumPages < 1 || maximumTransactions < 1) {
            throw new IllegalArgumentException("replay limits must be positive and pageSize at most 500");
        }
        this.pageSize = pageSize;
        this.maximumPages = maximumPages;
        this.maximumTransactions = maximumTransactions;
    }

    public ReplayResult replay(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }

        var transactions = new LinkedHashMap<String, MatchedTransaction>();
        var cursors = new java.util.HashSet<String>();
        String cursor = null;
        int pages = 0;

        do {
            if (++pages > maximumPages) {
                throw new IncompleteReplayException("Replay exceeded the page limit");
            }
            TransactionReplayPage page;
            try {
                page = Objects.requireNonNull(
                        source.fetch(new TransactionReplaySource.Request(from, to, cursor, pageSize)),
                        "source returned a null page");
            } catch (RuntimeException failure) {
                if (failure instanceof IncompleteReplayException incomplete) {
                    throw incomplete;
                }
                throw new IncompleteReplayException("Transaction replay page failed", failure);
            }
            if (page.items().size() > pageSize) {
                throw new IncompleteReplayException("Provider exceeded the requested page size");
            }

            for (MatchedTransaction transaction : page.items()) {
                if (transaction.transactionDate().isBefore(from)
                        || !transaction.transactionDate().isBefore(to)) {
                    throw new IncompleteReplayException(
                            "Provider returned an out-of-window transaction: " + transaction.transactionId());
                }
                MatchedTransaction previous = transactions.putIfAbsent(
                        transaction.transactionId(), transaction);
                if (previous != null && !previous.equals(transaction)) {
                    throw new IncompleteReplayException(
                            "Provider returned conflicting transaction: " + transaction.transactionId());
                }
                if (transactions.size() > maximumTransactions) {
                    throw new IncompleteReplayException("Replay exceeded the transaction limit");
                }
            }

            cursor = page.nextCursor();
            if (cursor != null && !cursors.add(cursor)) {
                throw new IncompleteReplayException("Provider repeated a replay cursor");
            }
        } while (cursor != null);

        return new ReplayResult(from, to, List.copyOf(transactions.values()), pages);
    }

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
        public IncompleteReplayException(String message) {
            super(message);
        }

        public IncompleteReplayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
