package bank.alert.processing;

import java.util.List;
import java.util.UUID;

public record AlertGenerationOutcome(
        UUID publicEventId,
        int alertsCreated,
        int duplicates,
        int belowThreshold,
        int unevaluable,
        int dailyLimitSuppressed,
        List<UUID> alertIds) {

    public AlertGenerationOutcome {
        alertIds = List.copyOf(alertIds);
    }
}
