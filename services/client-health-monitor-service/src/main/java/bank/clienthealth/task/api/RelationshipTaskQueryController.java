package bank.clienthealth.task.api;

import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.task.application.RelationshipTaskQueryService;
import bank.clienthealth.task.domain.RelationshipTask.Status;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AdminViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AgentViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.TaskFilter;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.Viewer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/relationship-health/tasks")
@Validated
public class RelationshipTaskQueryController {
    private final RelationshipTaskQueryService queries;

    public RelationshipTaskQueryController(RelationshipTaskQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize("@taskAuthorization.canRead(authentication)")
    public RelationshipTaskView.Page list(
            @RequestParam(required = false) Set<Status> status,
            @RequestParam(required = false) Set<Severity> severity,
            @RequestParam(required = false) @Size(max = 128) String assignedAgentSubject,
            @RequestParam(required = false) @Size(max = 512) String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            Authentication authentication
    ) {
        Viewer viewer = viewer(authentication, assignedAgentSubject);
        return queries.list(viewer, new TaskFilter(
                status == null ? Set.of() : status,
                severity == null ? Set.of() : severity,
                limit,
                cursor));
    }

    @GetMapping("/{taskId}")
    @PreAuthorize("@taskAuthorization.canRead(authentication)")
    public RelationshipTaskView.Task detail(
            @PathVariable @NotBlank @Size(max = 128) String taskId,
            Authentication authentication
    ) {
        return queries.detail(taskId, viewer(authentication, null));
    }

    @GetMapping("/{taskId}/history")
    @PreAuthorize("@taskAuthorization.canRead(authentication)")
    public RelationshipTaskView.History history(
            @PathVariable @NotBlank @Size(max = 128) String taskId,
            Authentication authentication
    ) {
        return queries.history(taskId, viewer(authentication, null));
    }

    private static Viewer viewer(Authentication authentication, String assignedAgentSubject) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (admin) return new AdminViewer(assignedAgentSubject);
        if (assignedAgentSubject != null) {
            throw new IllegalArgumentException("assignedAgentSubject is an Admin-only filter");
        }
        return new AgentViewer(authentication.getName());
    }
}
