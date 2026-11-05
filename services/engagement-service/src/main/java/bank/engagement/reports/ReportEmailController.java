package bank.engagement.reports;

import bank.engagement.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Sends an approved client report to one client (ADR-0084). */
@RestController
@RequestMapping("/api/engagement/report-emails")
@RequiredArgsConstructor
public class ReportEmailController {

    public record SendReportRequest(@NotNull UUID reportId, @NotNull UUID alertId) {
    }

    private final ReportEmailService reports;
    private final CurrentUser currentUser;

    @PostMapping
    public ResponseEntity<Void> send(@Valid @RequestBody SendReportRequest request) {
        reports.send(currentUser.subject(), request.reportId(), request.alertId());
        return ResponseEntity.noContent().build();
    }
}
