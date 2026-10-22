package bank.clienthealth.task.support;

import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.port.RelationshipTaskQueryPort;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Query fake that models snapshot ownership, filtering and stable task ordering. */
public final class InMemoryRelationshipTaskQueryPort implements RelationshipTaskQueryPort {
    private static final Comparator<RelationshipTask> NEWEST_FIRST =
            Comparator.comparing(RelationshipTask::updatedAt)
                    .thenComparing(RelationshipTask::taskId)
                    .reversed();
    private final List<RelationshipTask> tasks = new ArrayList<>();

    public void add(RelationshipTask task) {
        tasks.add(task);
    }

    @Override
    public TaskPage findTasks(Viewer viewer, TaskFilter filter) {
        List<RelationshipTask> visible = tasks.stream()
                .filter(task -> visible(task, viewer))
                .filter(task -> filter.statuses().isEmpty() || filter.statuses().contains(task.status()))
                .filter(task -> filter.severities().isEmpty() || filter.severities().contains(task.severity()))
                .sorted(NEWEST_FIRST)
                .toList();
        int start = cursorStart(visible, filter.cursor());
        int end = Math.min(start + filter.limit(), visible.size());
        String next = end < visible.size() ? encode(visible.get(end - 1).taskId()) : null;
        return new TaskPage(visible.subList(start, end), next);
    }

    @Override
    public Optional<RelationshipTask> findTask(String taskId, Viewer viewer) {
        return tasks.stream().filter(task -> task.taskId().equals(taskId))
                .filter(task -> visible(task, viewer)).findFirst();
    }

    private static boolean visible(RelationshipTask task, Viewer viewer) {
        String assigned = task.assignment().agentSubject();
        if (viewer instanceof AgentViewer agent) return assigned.equals(agent.subject());
        AdminViewer admin = (AdminViewer) viewer;
        return admin.assignedAgentSubject() == null || assigned.equals(admin.assignedAgentSubject());
    }

    private static int cursorStart(List<RelationshipTask> tasks, String cursor) {
        if (cursor == null) return 0;
        String taskId;
        try {
            taskId = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidCursorException();
        }
        for (int index = 0; index < tasks.size(); index++) {
            if (tasks.get(index).taskId().equals(taskId)) return index + 1;
        }
        throw new InvalidCursorException();
    }

    private static String encode(String taskId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(taskId.getBytes(StandardCharsets.UTF_8));
    }
}
