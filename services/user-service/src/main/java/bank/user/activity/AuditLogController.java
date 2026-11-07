package bank.user.activity;

import bank.user.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only access to every audit event, including transaction-service events. */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ActivityService activityService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AuditLogPage listAuditLogs(
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor,
            JwtAuthenticationToken caller) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size: must be between 1 and " + MAX_PAGE_SIZE);
        }
        return activityService.listAuditLogs(size, cursor, caller.getToken().getTokenValue());
    }
}
