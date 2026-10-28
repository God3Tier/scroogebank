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
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AdminViewer;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.AgentViewer;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import bank.clienthealth.explanation.ExplanationRepository;
import bank.clienthealth.metrics.ClientHealthMetrics;

/** Application read service used by the SF06 HTTP adapter. */
public final class RelationshipTaskQueryService {
    private final RelationshipTaskQueryPort queries;
    private final ClientAssignmentPort assignments;
    private final ExplanationRepository explanations;
    private final ClientHealthMetrics metrics;

    public RelationshipTaskQueryService(RelationshipTaskQueryPort queries, ClientAssignmentPort assignments) {
        this(queries, assignments, null, ClientHealthMetrics.noOp());
    }

    public RelationshipTaskQueryService(RelationshipTaskQueryPort queries, ClientAssignmentPort assignments,
            ExplanationRepository explanations) {
        this(queries, assignments, explanations, ClientHealthMetrics.noOp());
    }

    public RelationshipTaskQueryService(RelationshipTaskQueryPort queries, ClientAssignmentPort assignments,
            ExplanationRepository explanations, ClientHealthMetrics metrics) {
        this.queries = Objects.requireNonNull(queries);
        this.assignments = Objects.requireNonNull(assignments);
        this.explanations = explanations;
        this.metrics = Objects.requireNonNull(metrics);
    }

    public Page list(Viewer viewer, TaskFilter filter) {
        Objects.requireNonNull(viewer);
        Objects.requireNonNull(filter);
        var page = queries.findTasks(viewer, filter);
        if (viewer instanceof AdminViewer admin && admin.assignedAgentSubject() == null) {
            return page(page.tasks(), page.nextCursor());
        }
        String expectedSubject = viewer instanceof AgentViewer agent
                ? agent.subject() : ((AdminViewer) viewer).assignedAgentSubject();
        var visible = new ArrayList<bank.clienthealth.task.domain.RelationshipTask>();
        String requestCursor = filter.cursor();
        String nextCursor;
        while (true) {
            page.tasks().stream()
                    .map(task -> currentTaskFor(task, expectedSubject))
                    .flatMap(Optional::stream)
                    .forEach(visible::add);
            nextCursor = page.nextCursor();
            if (visible.size() >= filter.limit() || nextCursor == null) break;
            if (nextCursor.equals(requestCursor)) {
                throw new RelationshipTaskQueryPort.InvalidCursorException();
            }
            requestCursor = nextCursor;
            page = queries.findTasks(viewer, new TaskFilter(
                    filter.statuses(), filter.severities(), filter.limit() - visible.size(), nextCursor));
        }
        return page(visible, nextCursor);
    }

    public Task detail(String taskId, Viewer viewer) {
        var task = find(taskId, viewer);
        return RelationshipTaskView.task(task, explanation(task).orElse(null));
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
                metrics.authorizationDenied();
                throw new RelationshipTaskService.TaskNotFoundException(taskId);
            }
            if (!assignment.active() || !assignment.clientId().equals(task.clientId())
                    || !assignment.agentSubject().equals(agent.subject())) {
                metrics.authorizationDenied();
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

    private Page page(java.util.List<bank.clienthealth.task.domain.RelationshipTask> tasks, String cursor) {
        return RelationshipTaskView.pageViews(tasks.stream()
                .map(task -> RelationshipTaskView.task(task, explanation(task).orElse(null))).toList(), cursor);
    }

    private Optional<ExplanationRepository.Record> explanation(
            bank.clienthealth.task.domain.RelationshipTask task
    ) {
        if (explanations == null) return Optional.empty();
        long evidenceVersion = task.history().stream()
                .filter(item -> item.action() == bank.clienthealth.task.domain.RelationshipTask.Action.CREATED
                        || item.action() == bank.clienthealth.task.domain.RelationshipTask.Action.EVIDENCE_ADDED)
                .mapToLong(bank.clienthealth.task.domain.RelationshipTask.HistoryEntry::taskVersion)
                .max().orElseThrow();
        return explanations.findForTaskVersion(task.taskId(), evidenceVersion);
    }
}
