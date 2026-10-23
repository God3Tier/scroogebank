package bank.clienthealth.task.support;

import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.port.RelationshipTaskQueryPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Query fake that models persistence filtering and stable keyset task ordering. */
public final class InMemoryRelationshipTaskQueryPort implements RelationshipTaskQueryPort {
    private static final Comparator<RelationshipTask> NEWEST_FIRST =
            Comparator.comparing(RelationshipTask::updatedAt).reversed()
                    .thenComparing(RelationshipTask::taskId);
    private final List<RelationshipTask> tasks = new ArrayList<>();

    public void add(RelationshipTask task) {
        tasks.add(task);
    }

    @Override
    public TaskPage findTasks(Viewer viewer, TaskFilter filter) {
        List<RelationshipTask> visible = tasks.stream()
                .filter(task -> filter.statuses().isEmpty() || filter.statuses().contains(task.status()))
                .filter(task -> filter.severities().isEmpty() || filter.severities().contains(task.severity()))
                .sorted(NEWEST_FIRST)
                .toList();
        int start = cursorStart(visible, filter.cursor());
        int end = Math.min(start + filter.limit(), visible.size());
        String next = end < visible.size() ? TaskCursor.encode(visible.get(end - 1)) : null;
        return new TaskPage(visible.subList(start, end), next);
    }

    @Override
    public Optional<RelationshipTask> findTask(String taskId, Viewer viewer) {
        return tasks.stream().filter(task -> task.taskId().equals(taskId))
                .findFirst();
    }

    private static int cursorStart(List<RelationshipTask> tasks, String cursor) {
        if (cursor == null) return 0;
        TaskCursor boundary = TaskCursor.decode(cursor);
        for (int index = 0; index < tasks.size(); index++) {
            RelationshipTask task = tasks.get(index);
            if (task.updatedAt().isBefore(boundary.updatedAt())
                    || (task.updatedAt().equals(boundary.updatedAt())
                    && task.taskId().compareTo(boundary.taskId()) > 0)) return index;
        }
        return tasks.size();
    }

    private record TaskCursor(Instant updatedAt, String taskId) {
        private static TaskCursor decode(String cursor) {
            try {
                String value = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                        java.nio.charset.StandardCharsets.UTF_8);
                int separator = value.indexOf('\n');
                return new TaskCursor(Instant.parse(value.substring(0, separator)),
                        value.substring(separator + 1));
            } catch (RuntimeException invalid) {
                throw new InvalidCursorException();
            }
        }

        private static String encode(RelationshipTask task) {
            String value = task.updatedAt() + "\n" + task.taskId();
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
