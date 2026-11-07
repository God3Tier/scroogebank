package bank.alert.event;

import bank.alert.event.AgentEventModels.ActionRequest;
import bank.alert.event.AgentEventModels.ActionResult;
import bank.alert.event.AgentEventModels.EventDetail;
import bank.alert.event.AgentEventModels.EventPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The agent's event briefings: one card per public event, with the affected clients on drill-down. */
@RestController
@RequestMapping("/api/alerts/events")
@Validated
public class AgentEventController {

    private static final String IDEMPOTENCY_PATTERN = "^[A-Za-z0-9._:-]{8,128}$";

    private final AgentEventQueryService queries;
    private final AgentEventActionService actions;

    public AgentEventController(AgentEventQueryService queries, AgentEventActionService actions) {
        this.queries = queries;
        this.actions = actions;
    }

    @GetMapping
    @PreAuthorize("hasRole('AGENT')")
    public EventPage list(
            @RequestParam(defaultValue = "false") boolean onlyOpen,
            @RequestParam(defaultValue = "false") boolean includeClosed,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 512) String cursor,
            Authentication authentication) {
        return queries.list(authentication.getName(), onlyOpen, includeClosed, cursor, limit);
    }

    @GetMapping("/{eventId}")
    @PreAuthorize("hasRole('AGENT')")
    public EventDetail detail(@PathVariable UUID eventId, Authentication authentication) {
        return queries.detail(eventId, authentication.getName());
    }

    @PostMapping("/{eventId}/actions")
    @PreAuthorize("hasRole('AGENT')")
    public ActionResult act(
            @PathVariable UUID eventId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_PATTERN) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
            @Valid @RequestBody ActionRequest request,
            Authentication authentication) {
        return actions.apply(eventId, authentication.getName(), idempotencyKey,
                correlationId == null ? UUID.randomUUID() : correlationId, request);
    }
}
