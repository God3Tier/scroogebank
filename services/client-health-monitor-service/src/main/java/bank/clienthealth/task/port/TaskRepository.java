package bank.clienthealth.task.port;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.TaskUpsertResult;
import bank.clienthealth.task.domain.RelationshipTask;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Atomic persistence boundary; Stage 1 supplies test implementations only. */
public interface TaskRepository {
    <T> T inTransaction(Function<Session, T> work);

    interface Session {
        Optional<ProcessedFinding> findProcessedFinding(String findingId);
        Optional<RelationshipTask> findActiveTask(String clientId);
        Set<String> findFingerprints(String clientId, Collection<String> fingerprints);
        Optional<RelationshipTask> findLatestTaskContainingAny(String clientId, Collection<String> fingerprints);
        Optional<RelationshipTask> findTask(String taskId);
        void saveTask(RelationshipTask task, long expectedVersion);
        void saveProcessedFinding(ProcessedFinding processedFinding);
    }

    record ProcessedFinding(
            ClientHealthFinding finding,
            TaskUpsertResult result,
            Instant processedAt
    ) { }

    class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }
}
