package bank.alert.portfolio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/alert-portfolios")
@Validated
@ConditionalOnProperty(name = "alert.holdings.mode", havingValue = "database")
public class PortfolioController {

    private static final String IDEMPOTENCY_PATTERN = "^[A-Za-z0-9._:-]{8,128}$";

    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    public PortfolioResponse get(@PathVariable UUID accountId, Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        return service.get(accountId, authentication.getName(), admin);
    }

    @PutMapping("/{accountId}")
    @PreAuthorize("hasRole('AGENT')")
    public PortfolioResponse replace(
            @PathVariable UUID accountId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            @Valid @RequestBody PortfolioReplaceRequest request,
            Authentication authentication) {
        return service.replace(accountId, authentication.getName(), idempotencyKey,
                correlationId == null ? UUID.randomUUID() : correlationId, request);
    }
}
