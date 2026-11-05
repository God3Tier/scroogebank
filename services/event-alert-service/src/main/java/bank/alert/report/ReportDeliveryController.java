package bank.alert.report;

import bank.alert.report.ReportDeliveryService.DeliveryPackage;
import bank.alert.report.ReportDeliveryService.Recipient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Report approval for the agent page, and the delivery steps the engagement service calls with the agent own
 * token. Nothing here sends mail: sending belongs to the engagement service (ADR-0084).
 */
@RestController
@RequestMapping("/api/alerts")
@Validated
public class ReportDeliveryController {

    public record ApprovalRequest(@NotNull UUID reportId, @NotEmpty @Size(max = 200) List<@NotNull UUID> alertIds) {
    }

    public record SentRequest(@NotNull @Size(min = 1, max = 256) String messageId) {
    }

    private final ReportDeliveryService delivery;

    public ReportDeliveryController(ReportDeliveryService delivery) {
        this.delivery = delivery;
    }

    @PostMapping("/events/{eventId}/report/approval")
    @PreAuthorize("hasRole('AGENT')")
    public List<Recipient> approve(@PathVariable UUID eventId, @Valid @RequestBody ApprovalRequest request,
            Authentication authentication) {
        return delivery.approve(eventId, authentication.getName(), request.reportId(), request.alertIds());
    }

    @GetMapping("/report-deliveries/{reportId}/{alertId}")
    @PreAuthorize("hasRole('AGENT')")
    public DeliveryPackage get(@PathVariable UUID reportId, @PathVariable UUID alertId, Authentication authentication) {
        return delivery.delivery(reportId, alertId, authentication.getName());
    }

    @PostMapping("/report-deliveries/{reportId}/{alertId}/claim")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<Void> claim(@PathVariable UUID reportId, @PathVariable UUID alertId, Authentication authentication) {
        delivery.claim(reportId, alertId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/report-deliveries/{reportId}/{alertId}/sent")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<Void> sent(@PathVariable UUID reportId, @PathVariable UUID alertId,
            @Valid @RequestBody SentRequest request, Authentication authentication) {
        delivery.complete(reportId, alertId, authentication.getName(), request.messageId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/report-deliveries/{reportId}/{alertId}/release")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<Void> release(@PathVariable UUID reportId, @PathVariable UUID alertId, Authentication authentication) {
        delivery.release(reportId, alertId, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
