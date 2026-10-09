package bank.user.activity;

import bank.user.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** The spec's "Admins can see the activities of all agents". */
@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ActivityService activityService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ActivityPage listActivities(
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor) {
        // Checked here rather than left to the auditor, so an error from the
        // auditor always means the auditor failed, never that the caller did.
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size: must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BadRequestException("from: must be before to");
        }
        return activityService.list(new ActivityQuery(agentId, from, to, size, cursor));
    }
}
