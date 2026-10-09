package bank.user.controller;

import bank.user.service.TransactionStatsProxy;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Transaction figures for the business dashboard, fetched with the caller's
 * own token. Outside /api, so API Gateway never routes it, and not audited
 * here: the dashboard audits each view (ADR-0039).
 */
@RestController
@RequestMapping("/aggregates/transactions")
@RequiredArgsConstructor
public class TransactionStatsController {

    private final TransactionStatsProxy proxy;

    /** The window's first and last day, both included. */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'AGENT')")
    public ResponseEntity<JsonNode> getStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            Authentication authentication
    ) {
        // Scope comes from the verified token's groups, never from a parameter.
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return proxy.getStats(from, to, admin, authorization);
    }
}
