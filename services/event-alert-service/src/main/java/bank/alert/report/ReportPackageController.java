package bank.alert.report;

import bank.alert.report.ReportPackageService.ReportPackage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The report and the chosen clients, for the engagement service to email (ADR-0084). It is a POST only because
 * the alert IDs travel in the body; it changes nothing and sends nothing.
 */
@RestController
@RequestMapping("/api/alerts/events")
@Validated
public class ReportPackageController {

    public record PackageRequest(@Min(1) int expectedReportVersion,
            @NotEmpty @Size(max = 200) List<@NotNull UUID> alertIds) {
    }

    private final ReportPackageService packages;

    public ReportPackageController(ReportPackageService packages) {
        this.packages = packages;
    }

    @PostMapping("/{eventId}/report/delivery-package")
    @PreAuthorize("hasRole('AGENT')")
    public ReportPackage build(@PathVariable UUID eventId, @Valid @RequestBody PackageRequest request,
            Authentication authentication) {
        return packages.build(eventId, authentication.getName(), request.expectedReportVersion(), request.alertIds());
    }
}
