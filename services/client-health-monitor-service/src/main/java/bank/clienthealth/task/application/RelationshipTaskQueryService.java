package bank.clienthealth.task.application;

import bank.clienthealth.task.api.RelationshipTaskView;
import bank.clienthealth.task.api.RelationshipTaskView.History;
import bank.clienthealth.task.api.RelationshipTaskView.Page;
import bank.clienthealth.task.api.RelationshipTaskView.Task;
import bank.clienthealth.task.port.RelationshipTaskQueryPort;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.TaskFilter;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.Viewer;
import java.util.Objects;

/** Application read service used by the SF06 HTTP adapter. */
public final class RelationshipTaskQueryService {
    private final RelationshipTaskQueryPort queries;

    public RelationshipTaskQueryService(RelationshipTaskQueryPort queries) {
        this.queries = Objects.requireNonNull(queries);
    }

    public Page list(Viewer viewer, TaskFilter filter) {
        var page = queries.findTasks(Objects.requireNonNull(viewer), Objects.requireNonNull(filter));
        return RelationshipTaskView.page(page.tasks(), page.nextCursor());
    }

    public Task detail(String taskId, Viewer viewer) {
        return RelationshipTaskView.task(find(taskId, viewer));
    }

    public History history(String taskId, Viewer viewer) {
        return RelationshipTaskView.history(find(taskId, viewer));
    }

    private bank.clienthealth.task.domain.RelationshipTask find(String taskId, Viewer viewer) {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        return queries.findTask(taskId, Objects.requireNonNull(viewer))
                .orElseThrow(() -> new RelationshipTaskService.TaskNotFoundException(taskId));
    }
}
