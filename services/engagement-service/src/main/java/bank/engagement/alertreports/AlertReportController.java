package bank.engagement.alertreports;

import bank.engagement.alertreports.AlertReportModels.BatchResult;
import bank.engagement.alertreports.AlertReportModels.DeliveryRequest;
import bank.engagement.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Emails an approved event report to the clients an agent chose (ADR-0084). */
@RestController
@RequestMapping("/api/engagement/alert-report-deliveries")
@RequiredArgsConstructor
@Validated
public class AlertReportController {

    private static final String KEY = "^[A-Za-z0-9._:-]{8,128}$";

    private final AlertReportService reports;
    private final CurrentUser currentUser;

    @PostMapping
    public BatchResult deliver(@RequestHeader("Idempotency-Key") @Pattern(regexp = KEY) String idempotencyKey,
            @Valid @RequestBody DeliveryRequest request) {
        return reports.deliver(currentUser.subject(), idempotencyKey, request);
    }
}
