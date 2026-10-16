package bank.clienthealth.transaction.port;

import bank.clienthealth.transaction.contract.TransactionReplayPage;
import java.time.Instant;
import java.util.Objects;

/** Provider boundary implemented later by the authenticated transaction API adapter. */
@FunctionalInterface
public interface TransactionReplaySource {
    TransactionReplayPage fetch(Request request);

    record Request(Instant from, Instant to, String cursor, int limit) {
        public Request {
            from = Objects.requireNonNull(from, "from is required");
            to = Objects.requireNonNull(to, "to is required");
            if (!from.isBefore(to)) {
                throw new IllegalArgumentException("from must be before to");
            }
            if (cursor != null && (cursor.isBlank() || cursor.length() > 512)) {
                throw new IllegalArgumentException("cursor must be null or bounded");
            }
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("limit must be from 1 to 500");
            }
        }
    }
}
