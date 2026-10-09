package bank.user.activity;

import bank.user.exception.BadRequestException;
import bank.user.exception.ForbiddenOperationException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The activity feed (ADR-0021): the spec's "Agents can only see their own
 * client activities. Admins can see the activities of all agents." It shows
 * changes only, never reads.
 */
@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ActivityService activityService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AGENT')")
    public ActivityPage listActivities(
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor,
            JwtAuthenticationToken caller) {
        // Checked here rather than left to the auditor, so an error from the
        // auditor always means the auditor failed, never that the caller did.
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size: must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BadRequestException("from: must be before to");
        }
        boolean admin = caller.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            // An agent sees only their own activities. The auditor applies the
            // same rule from the forwarded token.
            String self = caller.getToken().getSubject();
            if (agentId != null && !agentId.equals(self)) {
                throw new ForbiddenOperationException("Agents can see only their own activities");
            }
            agentId = self;
        }
        return activityService.list(new ActivityQuery(agentId, from, to, size, cursor),
                caller.getToken().getTokenValue());
    }
}
