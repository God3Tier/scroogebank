package bank.alert.api;

import bank.alert.domain.AlertStatus;
import bank.alert.service.AlertQueryService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/alerts")
@Validated
public class AlertQueryController {

    private final AlertQueryService queryService;

    public AlertQueryController(AlertQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    public AlertPageResponse listAlerts(
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false)
            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal minimumScore,
            @RequestParam(required = false) @Size(max = 512) String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        return queryService.listAlerts(
                authentication.getName(), admin, status, minimumScore, cursor, limit);
    }
}
