package bank.user.controller;

import bank.user.service.CorruptedAuditLogProxy;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/corrupted-logs")
@RequiredArgsConstructor
public class CorruptedAuditLogController {

    private final CorruptedAuditLogProxy proxy;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JsonNode> getCorruptedLogs(@AuthenticationPrincipal Jwt caller) {
        return proxy.getCorruptedLogs(caller.getTokenValue());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JsonNode> createCorruptedLog(@RequestBody JsonNode request,
                                                       HttpServletRequest servletRequest,
                                                       @AuthenticationPrincipal Jwt caller) {
        // The Auditor's response has no id, so the audit event takes it from
        // the request. Anything but a UUID is left out; the Auditor rejects it.
        JsonNode id = request.path("id");
        if (id.isTextual() && isUuid(id.asText())) {
            servletRequest.setAttribute("audit.resource_id", id.asText());
        }
        return proxy.createCorruptedLog(request, caller.getTokenValue());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JsonNode> deleteCorruptedLog(@PathVariable UUID id, @AuthenticationPrincipal Jwt caller) {
        return proxy.deleteCorruptedLog(id, caller.getTokenValue());
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
