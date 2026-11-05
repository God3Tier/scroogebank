package bank.engagement.alertreports;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Request and response shapes of {@code /api/engagement/alert-report-deliveries} (ADR-0084). */
public final class AlertReportModels {

    private AlertReportModels() {
    }

    public record DeliveryRequest(
            @NotNull UUID eventId,
            @Min(1) int expectedReportVersion,
            @NotEmpty @Size(max = 200) List<@NotNull UUID> alertIds,
            @Size(max = 500) String agentNote) {
    }

    /** {@code status} is QUEUED, SENT, FAILED or NOT_AUTHORISED; {@code errorCode} says why for FAILED and NOT_AUTHORISED. */
    public record RecipientStatus(UUID alertId, String status, String errorCode) {
    }

    public record BatchResult(UUID batchId, UUID eventId, int reportVersion, List<RecipientStatus> recipients) {
    }
}
