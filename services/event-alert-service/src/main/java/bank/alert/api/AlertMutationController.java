package bank.alert.api;

import bank.alert.service.AlertMutationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/alerts")
@Validated
public class AlertMutationController {

    private static final String IDEMPOTENCY_PATTERN = "^[A-Za-z0-9._:-]{8,128}$";

    private final AlertMutationService mutationService;

    public AlertMutationController(AlertMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @PostMapping("/{alertId}/decisions")
    @PreAuthorize("hasRole('AGENT')")
    public DecisionResponse recordDecision(
            @PathVariable UUID alertId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication) {
        return DecisionResponse.from(mutationService.recordDecision(
                alertId, authentication.getName(), idempotencyKey,
                correlationId == null ? UUID.randomUUID() : correlationId, request));
    }

    @PostMapping("/{alertId}/feedback")
    @PreAuthorize("hasRole('AGENT')")
    public FeedbackResponse recordFeedback(
            @PathVariable UUID alertId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            @Valid @RequestBody FeedbackRequest request,
            Authentication authentication) {
        return FeedbackResponse.from(mutationService.recordFeedback(
                alertId, authentication.getName(), idempotencyKey,
                correlationId == null ? UUID.randomUUID() : correlationId, request));
    }
}
