package bank.clienthealth.transaction.contract;

import java.util.List;
import java.util.Objects;

/** One versioned provider page; a null next cursor marks the final page. */
public record TransactionReplayPage(
        String schemaVersion,
        List<MatchedTransaction> items,
        String nextCursor
) {
    public static final String SCHEMA_VERSION = "1";

    public TransactionReplayPage {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported transaction replay schema: " + schemaVersion);
        }
        items = List.copyOf(Objects.requireNonNull(items, "items are required"));
        if (items.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("items cannot contain null");
        }
        if (nextCursor != null && (nextCursor.isBlank() || nextCursor.length() > 512)) {
            throw new IllegalArgumentException("nextCursor must be null or a bounded opaque cursor");
        }
    }
}
