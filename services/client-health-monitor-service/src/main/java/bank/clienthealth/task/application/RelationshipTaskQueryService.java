package bank.clienthealth.task.application;

import bank.clienthealth.task.api.RelationshipTaskView;
import bank.clienthealth.task.api.RelationshipTaskView.History;
import bank.clienthealth.task.api.RelationshipTaskView.Page;
import bank.clienthealth.task.api.RelationshipTaskView.Task;
import bank.clienthealth.task.port.RelationshipTaskQueryPort;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.TaskFilter;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.Viewer;
import bank.clienthealth.task.port.ClientAssignmentPort;
import bank.clienthealth.task.port.ClientAssignmentPort.AssignmentRejectedException;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AgentViewer;
import java.util.Objects;
import java.util.Optional;

/** Application read service used by the SF06 HTTP adapter. */
public final class RelationshipTaskQueryService {
    private final RelationshipTaskQueryPort queries;
    private final ClientAssignmentPort assignments;

    public RelationshipTaskQueryService(RelationshipTaskQueryPort queries, ClientAssignmentPort assignments) {
        this.queries = Objects.requireNonNull(queries);
        this.assignments = Objects.requireNonNull(assignments);
    }

    public Page list(Viewer viewer, TaskFilter filter) {
        var page = queries.findTasks(Objects.requireNonNull(viewer), Objects.requireNonNull(filter));
        if (!(viewer instanceof AgentViewer agent)) {
            return RelationshipTaskView.page(page.tasks(), page.nextCursor());
        }
        var visible = page.tasks().stream()
                .map(task -> currentTaskFor(task, agent.subject()))
                .flatMap(Optional::stream)
                .toList();
        return RelationshipTaskView.page(visible, page.nextCursor());
    }

    public Task detail(String taskId, Viewer viewer) {
        return RelationshipTaskView.task(find(taskId, viewer));
    }

    public History history(String taskId, Viewer viewer) {
        return RelationshipTaskView.history(find(taskId, viewer));
    }

    private bank.clienthealth.task.domain.RelationshipTask find(String taskId, Viewer viewer) {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        var task = queries.findTask(taskId, Objects.requireNonNull(viewer))
                .orElseThrow(() -> new RelationshipTaskService.TaskNotFoundException(taskId));
        if (viewer instanceof AgentViewer agent) {
            final bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot assignment;
            try {
                assignment = assignments.resolve(task.clientId());
            } catch (AssignmentRejectedException rejected) {
                throw new RelationshipTaskService.TaskNotFoundException(taskId);
            }
            if (!assignment.active() || !assignment.clientId().equals(task.clientId())
                    || !assignment.agentSubject().equals(agent.subject())) {
                throw new RelationshipTaskService.TaskNotFoundException(taskId);
            }
            return task.withAssignment(assignment);
        }
        return task;
    }

    private Optional<bank.clienthealth.task.domain.RelationshipTask> currentTaskFor(
            bank.clienthealth.task.domain.RelationshipTask task, String subject
    ) {
        final bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot assignment;
        try {
            assignment = assignments.resolve(task.clientId());
        } catch (AssignmentRejectedException rejected) {
            return Optional.empty();
        }
        if (!assignment.active() || !assignment.clientId().equals(task.clientId())
                || !assignment.agentSubject().equals(subject)) {
            return Optional.empty();
        }
        return Optional.of(task.withAssignment(assignment));
    }
}
