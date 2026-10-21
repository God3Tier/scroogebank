package bank.clienthealth.transaction.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Minimal matched transaction projection accepted from transaction-service. */
public record MatchedTransaction(
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
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public MatchedTransaction {
        transactionId = requireIdentifier(transactionId, "transactionId");
        clientId = requireIdentifier(clientId, "clientId");
        accountId = requireIdentifier(accountId, "accountId");
        type = Objects.requireNonNull(type, "type is required");
        amount = Objects.requireNonNull(amount, "amount is required");
        if (amount.signum() < 0 || amount.scale() > 2) {
            throw new IllegalArgumentException("amount must be non-negative with at most two decimals");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be a three-letter uppercase code");
        }
        transactionDate = Objects.requireNonNull(transactionDate, "transactionDate is required");
        status = Objects.requireNonNull(status, "status is required");
        activityKind = Objects.requireNonNull(activityKind, "activityKind is required");
        if (sourceFingerprint != null) {
            sourceFingerprint = requireIdentifier(sourceFingerprint, "sourceFingerprint");
        }
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a valid opaque identifier");
        }
        return value;
    }

    public enum TransactionType {
        DEPOSIT,
        WITHDRAWAL
    }

    public enum Status {
        COMPLETED,
        PENDING,
        FAILED
    }

    public enum ActivityKind {
        CUSTOMER_INITIATED,
        AUTOMATED,
        UNKNOWN
    }
}
