package bank.clienthealth.transaction.adapter.mock;

import bank.clienthealth.transaction.contract.MatchedTransaction;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import bank.clienthealth.transaction.contract.MatchedTransaction.TransactionType;
import bank.clienthealth.transaction.contract.TransactionReplayPage;
import bank.clienthealth.transaction.port.TransactionReplaySource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Contract-faithful local provider used until Feature 3 supplies its endpoint. */
public final class SyntheticTransactionReplaySource implements TransactionReplaySource {
    private static final String CURSOR_PREFIX = "offset:";
    private final List<MatchedTransaction> transactions;

    public SyntheticTransactionReplaySource(List<MatchedTransaction> transactions) {
        this.transactions = transactions.stream()
                .sorted(Comparator.comparing(MatchedTransaction::transactionDate)
                        .thenComparing(MatchedTransaction::transactionId))
                .toList();
    }

    @Override
    public TransactionReplayPage fetch(Request request) {
        List<MatchedTransaction> window = transactions.stream()
                .filter(transaction -> !transaction.transactionDate().isBefore(request.from()))
                .filter(transaction -> transaction.transactionDate().isBefore(request.to()))
                .toList();
        int offset = decodeCursor(request.cursor(), window.size());
        int end = Math.min(offset + request.limit(), window.size());
        String nextCursor = end < window.size() ? CURSOR_PREFIX + end : null;
        return new TransactionReplayPage("1", window.subList(offset, end), nextCursor);
    }

    public static SyntheticTransactionReplaySource sixMonthScenario() {
        List<MatchedTransaction> transactions = new ArrayList<>();

        // Three established monthly credits followed by three missing months.
        for (int month = 4; month <= 6; month++) {
            transactions.add(deposit(
                    "recurring-" + month, "client-recurring", "account-recurring",
                    month, 1, "4250.00", "synthetic-employer-001"));
        }

        // Healthy control: the same recurring source appears throughout the window.
        for (int month = 4; month <= 9; month++) {
            transactions.add(deposit(
                    "control-" + month, "client-control", "account-control",
                    month, 2, "3800.00", "synthetic-employer-control"));
        }

        // Four completed customer transactions per month, then one in September.
        for (int month = 4; month <= 8; month++) {
            for (int occurrence = 1; occurrence <= 4; occurrence++) {
                transactions.add(withdrawal(
                        "activity-" + month + "-" + occurrence,
                        "client-activity", "account-activity", month, occurrence + 4, "75.00"));
            }
        }
        transactions.add(withdrawal(
                "activity-9-1", "client-activity", "account-activity", 9, 5, "75.00"));

        // Four balanced months followed by two consecutive net-outflow months.
        for (int month = 4; month <= 7; month++) {
            transactions.add(deposit(
                    "flow-in-" + month, "client-outflow", "account-outflow",
                    month, 10, "3000.00", "synthetic-income-outflow"));
            transactions.add(withdrawal(
                    "flow-out-" + month, "client-outflow", "account-outflow",
                    month, 20, "2000.00"));
        }
        for (int month = 8; month <= 9; month++) {
            transactions.add(deposit(
                    "flow-in-" + month, "client-outflow", "account-outflow",
                    month, 10, "1000.00", "synthetic-income-outflow"));
            transactions.add(withdrawal(
                    "flow-out-" + month, "client-outflow", "account-outflow",
                    month, 20, "3000.00"));
        }

        // Non-completed rows prove later analysis can exclude them.
        transactions.add(transaction(
                "pending-control", "client-control", "account-control",
                TransactionType.WITHDRAWAL, "200.00", 9, 25, Status.PENDING, null));
        transactions.add(transaction(
                "failed-control", "client-control", "account-control",
                TransactionType.WITHDRAWAL, "300.00", 9, 26, Status.FAILED, null));

        return new SyntheticTransactionReplaySource(transactions);
    }

    private static int decodeCursor(String cursor, int size) {
        if (cursor == null) {
            return 0;
        }
        if (!cursor.startsWith(CURSOR_PREFIX)) {
            throw new IllegalArgumentException("Unknown synthetic replay cursor");
        }
        try {
            int offset = Integer.parseInt(cursor.substring(CURSOR_PREFIX.length()));
            if (offset < 0 || offset > size) {
                throw new IllegalArgumentException("Synthetic replay cursor is out of range");
            }
            return offset;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Malformed synthetic replay cursor", invalid);
        }
    }

    private static MatchedTransaction deposit(
            String id, String client, String account, int month, int day,
            String amount, String sourceFingerprint
    ) {
        return transaction(
                id, client, account, TransactionType.DEPOSIT, amount,
                month, day, Status.COMPLETED, sourceFingerprint);
    }

    private static MatchedTransaction withdrawal(
            String id, String client, String account, int month, int day, String amount
    ) {
        return transaction(
                id, client, account, TransactionType.WITHDRAWAL, amount,
                month, day, Status.COMPLETED, null);
    }

    private static MatchedTransaction transaction(
            String id, String client, String account, TransactionType type,
            String amount, int month, int day, Status status, String sourceFingerprint
    ) {
        Instant date = Instant.parse("2026-%02d-%02dT01:00:00Z".formatted(month, day));
        return new MatchedTransaction(
                id, client, account, type, new BigDecimal(amount), "SGD",
                date, status, sourceFingerprint);
    }
}
