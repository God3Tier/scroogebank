package bank.dashboard.controller;

import bank.dashboard.audit.AuditEmitter;
import bank.dashboard.audit.DashboardAction;
import bank.dashboard.audit.DashboardAuditEvent;
import bank.dashboard.audit.DashboardViewPayload;
import bank.dashboard.dto.AgentSortMetric;
import bank.dashboard.dto.DashboardResponse;
import bank.dashboard.dto.DashboardWindow;
import bank.dashboard.security.CurrentUser;
import bank.dashboard.service.DashboardService;
import bank.dashboard.service.ServedResponse;
import bank.dashboard.window.WindowParser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The dashboard API. Every endpoint is a GET: this feature creates no records
 * and changes no state.
 *
 * <p>The committed contract is {@code docs/api/dashboard-api.yaml}, and the
 * property most likely to surprise an integrator is stated there and worth
 * repeating: <strong>a 200 does not mean every figure is present</strong>.
 * Each panel reports its own status, and today most of them report
 * {@code UNAVAILABLE} because the services that own their data have not been
 * built yet.
 *
 * <p>No endpoint accepts an agent identifier. Scope comes from the token.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Validated
@Slf4j
public class DashboardController {

    private final DashboardService dashboardService;
    private final WindowParser windowParser;
    private final AuditEmitter auditEmitter;
    private final CurrentUser currentUser;
    private final Clock clock;

    @GetMapping("/overview")
    public ResponseEntity<DashboardResponse> overview(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        DashboardWindow window = windowParser.parse(from, to);
        return respond(dashboardService.overview(window), DashboardAction.VIEW_OVERVIEW);
    }

    @GetMapping("/portfolio")
    public ResponseEntity<DashboardResponse> portfolio(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        DashboardWindow window = windowParser.parse(from, to);
        return respond(dashboardService.portfolio(window), DashboardAction.VIEW_PORTFOLIO);
    }

    @GetMapping("/transactions")
    public ResponseEntity<DashboardResponse> transactions(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        DashboardWindow window = windowParser.parse(from, to);
        return respond(dashboardService.transactions(window), DashboardAction.VIEW_TRANSACTIONS);
    }

    @GetMapping("/action-items")
    public ResponseEntity<DashboardResponse> actionItems(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "7") @Min(1) @Max(3650) int staleDays) {
        DashboardWindow window = windowParser.parse(from, to);
        return respond(dashboardService.actionItems(window, staleDays), DashboardAction.VIEW_ACTION_ITEMS);
    }

    /**
     * Ranks named employees by an explicit metric. Admin-only, and no
     * composite score is computed -- see ADR-0038 for why that refusal is
     * deliberate rather than an omission.
     */
    @GetMapping("/agents")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DashboardResponse> agentPerformance(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "clientsOnboarded") AgentSortMetric sort) {
        DashboardWindow window = windowParser.parse(from, to);
        return respond(dashboardService.agentPerformance(window, sort), DashboardAction.VIEW_AGENT_PERFORMANCE);
    }

    /**
     * A partial result is a 200; only a total failure is an error. The body is
     * the same either way, so a client renders panel statuses rather than
     * branching on the status code.
     */
    private ResponseEntity<DashboardResponse> respond(ServedResponse served, DashboardAction action) {
        DashboardResponse response = served.response();
        audit(response, action, served.cacheHit());
        HttpStatus status = response.allPanelsUnavailable()
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Records who looked at what. A cache hit is audited too: the audit
     * question is who looked, which caching does not change.
     *
     * <p>Wrapped because auditing a read must never take down the read. A
     * failure here is a warning, not a 500 in front of the user.
     */
    private void audit(DashboardResponse response, DashboardAction action, boolean cacheHit) {
        try {
            Map<String, String> panelStatuses = new LinkedHashMap<>();
            response.panels().forEach((name, panel) -> panelStatuses.put(name, panel.status().name()));

            DashboardViewPayload payload = new DashboardViewPayload(
                    response.scope().name(),
                    response.window().from().toString(),
                    response.window().to().toString(),
                    panelStatuses,
                    (int) response.panels().values().stream().filter(panel -> !panel.isUnavailable()).count(),
                    cacheHit,
                    // The time of this view, not of the figures: a cache hit
                    // shows figures computed earlier.
                    Instant.now(clock).toString());

            auditEmitter.emit(DashboardAuditEvent.of(action, currentUser.subject(), payload));
        } catch (RuntimeException ex) {
            log.warn("Could not record audit event for action {}", action, ex);
        }
    }
}
