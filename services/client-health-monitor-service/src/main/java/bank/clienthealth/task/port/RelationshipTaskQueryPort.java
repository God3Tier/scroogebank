package bank.clienthealth.task.port;

import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.Status;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read boundary for the relationship-task API. Assignment-dependent visibility
 * is applied by the application service after current assignment is resolved;
 * persistence adapters must not pre-filter using the historical snapshot.
 */
public interface RelationshipTaskQueryPort {
    TaskPage findTasks(Viewer viewer, TaskFilter filter);

    Optional<RelationshipTask> findTask(String taskId, Viewer viewer);

    sealed interface Viewer permits AgentViewer, AdminViewer { }

    record AgentViewer(String subject) implements Viewer {
        public AgentViewer {
            subject = requireText(subject, "subject");
        }
    }

    /** A null Agent filter means all Agents. */
    record AdminViewer(String assignedAgentSubject) implements Viewer {
        public AdminViewer {
            if (assignedAgentSubject != null) {
                assignedAgentSubject = requireText(assignedAgentSubject, "assignedAgentSubject");
            }
        }
    }

    record TaskFilter(Set<Status> statuses, Set<Severity> severities, int limit, String cursor) {
        public TaskFilter {
            statuses = Set.copyOf(Objects.requireNonNull(statuses, "statuses are required"));
            severities = Set.copyOf(Objects.requireNonNull(severities, "severities are required"));
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (cursor != null && (cursor.isBlank() || cursor.length() > 512)) {
                throw new IllegalArgumentException("cursor must be nonblank and at most 512 characters");
            }
        }
    }

    record TaskPage(List<RelationshipTask> tasks, String nextCursor) {
        public TaskPage {
            tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks are required"));
            if (nextCursor != null && nextCursor.isBlank()) {
                throw new IllegalArgumentException("nextCursor cannot be blank");
            }
        }
    }

    class InvalidCursorException extends RuntimeException {
        public InvalidCursorException() {
            super("The task cursor is invalid or expired");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must be a bounded opaque identifier");
        }
        return value;
    }
}
