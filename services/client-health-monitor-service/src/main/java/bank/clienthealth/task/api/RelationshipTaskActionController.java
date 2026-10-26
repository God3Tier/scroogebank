package bank.clienthealth.task.api;

import bank.clienthealth.explanation.ExplanationRepository;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.application.RelationshipTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/relationship-health/tasks")
@Validated
public class RelationshipTaskActionController {
    private static final String IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private final RelationshipTaskService tasks;
    private final ExplanationRepository explanations;

    public RelationshipTaskActionController(RelationshipTaskService tasks) {
        this.tasks = tasks;
        this.explanations = null;
    }

    @Autowired
    public RelationshipTaskActionController(RelationshipTaskService tasks,
            ObjectProvider<ExplanationRepository> explanations) {
        this.tasks = tasks;
        this.explanations = explanations.getIfAvailable();
    }

    @PostMapping("/{taskId}/accept")
    @PreAuthorize("@taskAuthorization.canMutate(authentication)")
    public RelationshipTaskView.Task accept(
            @PathVariable @NotBlank @Size(max = 128) String taskId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            @Pattern(regexp = IDENTIFIER) String correlationId,
            @Valid @RequestBody RelationshipTaskActionRequest.Accept request,
            Authentication authentication
    ) {
        return view(tasks.accept(
                taskId, request.expectedVersion(), authentication.getName(), correlation(correlationId)));
    }

    @PostMapping("/{taskId}/complete")
    @PreAuthorize("@taskAuthorization.canMutate(authentication)")
    public RelationshipTaskView.Task complete(
            @PathVariable @NotBlank @Size(max = 128) String taskId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            @Pattern(regexp = IDENTIFIER) String correlationId,
            @Valid @RequestBody RelationshipTaskActionRequest.Complete request,
            Authentication authentication
    ) {
        return view(tasks.complete(
                taskId, request.expectedVersion(), authentication.getName(), correlation(correlationId),
                request.outcomeCode(), request.note()));
    }

    @PostMapping("/{taskId}/dismiss")
    @PreAuthorize("@taskAuthorization.canMutate(authentication)")
    public RelationshipTaskView.Task dismiss(
            @PathVariable @NotBlank @Size(max = 128) String taskId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            @Pattern(regexp = IDENTIFIER) String correlationId,
            @Valid @RequestBody RelationshipTaskActionRequest.Dismiss request,
            Authentication authentication
    ) {
        return view(tasks.dismiss(
                taskId, request.expectedVersion(), authentication.getName(), correlation(correlationId),
                request.reasonCode(), request.note()));
    }

    private RelationshipTaskView.Task view(RelationshipTask task) {
        if (explanations == null) return RelationshipTaskView.task(task);
        long evidenceVersion = task.history().stream()
                .filter(item -> item.action() == RelationshipTask.Action.CREATED
                        || item.action() == RelationshipTask.Action.EVIDENCE_ADDED)
                .mapToLong(RelationshipTask.HistoryEntry::taskVersion).max().orElseThrow();
        return RelationshipTaskView.task(task,
                explanations.findForTaskVersion(task.taskId(), evidenceVersion).orElse(null));
    }

    private static String correlation(String supplied) {
        return supplied == null ? UUID.randomUUID().toString() : supplied;
    }
}
