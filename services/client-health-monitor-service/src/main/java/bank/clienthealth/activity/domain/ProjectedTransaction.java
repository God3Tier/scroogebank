package bank.clienthealth.activity.domain;

import bank.clienthealth.transaction.contract.MatchedTransaction;
import bank.clienthealth.transaction.contract.MatchedTransaction.ActivityKind;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import bank.clienthealth.transaction.contract.MatchedTransaction.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Minimum local transaction projection retained for deterministic rules. */
public record ProjectedTransaction(
        String transactionId,
        String clientId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant transactionDate,
        Status status,
        ActivityKind activityKind,
        String sourceFingerprint
) {
    public ProjectedTransaction {
        transactionId = Objects.requireNonNull(transactionId);
        clientId = Objects.requireNonNull(clientId);
        accountId = Objects.requireNonNull(accountId);
        type = Objects.requireNonNull(type);
        amount = Objects.requireNonNull(amount);
        currency = Objects.requireNonNull(currency);
        transactionDate = Objects.requireNonNull(transactionDate);
        status = Objects.requireNonNull(status);
        activityKind = Objects.requireNonNull(activityKind);
    }

    public static ProjectedTransaction from(MatchedTransaction transaction) {
        return new ProjectedTransaction(
                transaction.transactionId(), transaction.clientId(), transaction.accountId(),
                transaction.type(), transaction.amount(), transaction.currency(),
                transaction.transactionDate(), transaction.status(), transaction.activityKind(),
                transaction.sourceFingerprint());
    }

    public boolean hasSameImmutableFields(ProjectedTransaction other) {
        return transactionId.equals(other.transactionId)
                && clientId.equals(other.clientId)
                && accountId.equals(other.accountId)
                && type == other.type
                && amount.compareTo(other.amount) == 0
                && currency.equals(other.currency)
                && transactionDate.equals(other.transactionDate)
                && activityKind == other.activityKind
                && Objects.equals(sourceFingerprint, other.sourceFingerprint);
    }

    public ProjectedTransaction withStatus(Status nextStatus) {
        return new ProjectedTransaction(
                transactionId, clientId, accountId, type, amount, currency,
                transactionDate, nextStatus, activityKind, sourceFingerprint);
    }
}
