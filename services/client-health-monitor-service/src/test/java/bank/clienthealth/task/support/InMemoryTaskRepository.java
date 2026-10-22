package bank.clienthealth.task.support;

import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.port.TaskRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Transactional fake that models the constraints required of the Stage 2 adapter. */
public final class InMemoryTaskRepository implements TaskRepository {
    private Map<String, RelationshipTask> tasks = new HashMap<>();
    private Map<String, ProcessedFinding> findings = new HashMap<>();
    private Map<AgentDay, Integer> creationCounts = new HashMap<>();
    private int conflictsRemaining;
    private boolean failProcessedFindingSave;

    @Override
    public synchronized <T> T inTransaction(Function<Session, T> work) {
        if (conflictsRemaining > 0) {
            conflictsRemaining--;
            throw new ConflictException("Injected conflict");
        }
        Map<String, RelationshipTask> taskCopy = new HashMap<>(tasks);
        Map<String, ProcessedFinding> findingCopy = new HashMap<>(findings);
        Map<AgentDay, Integer> creationCountCopy = new HashMap<>(creationCounts);
        T result = work.apply(new FakeSession(taskCopy, findingCopy, creationCountCopy));
        tasks = taskCopy;
        findings = findingCopy;
        creationCounts = creationCountCopy;
        return result;
    }

    public synchronized Optional<RelationshipTask> task(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public synchronized int taskCount() { return tasks.size(); }
    public synchronized int processedFindingCount() { return findings.size(); }
    public synchronized int creationCount(String agentSubject, LocalDate singaporeDate) {
        return creationCounts.getOrDefault(new AgentDay(agentSubject, singaporeDate), 0);
    }
    public synchronized void injectConflicts(int count) { conflictsRemaining = count; }
    public synchronized void failProcessedFindingSave(boolean fail) { failProcessedFindingSave = fail; }

    private final class FakeSession implements Session {
        private final Map<String, RelationshipTask> txTasks;
        private final Map<String, ProcessedFinding> txFindings;
        private final Map<AgentDay, Integer> txCreationCounts;

        private FakeSession(
                Map<String, RelationshipTask> txTasks,
                Map<String, ProcessedFinding> txFindings,
                Map<AgentDay, Integer> txCreationCounts
        ) {
            this.txTasks = txTasks;
            this.txFindings = txFindings;
            this.txCreationCounts = txCreationCounts;
        }

        @Override
        public Optional<ProcessedFinding> findProcessedFinding(String findingId) {
            return Optional.ofNullable(txFindings.get(findingId));
        }

        @Override
        public Optional<RelationshipTask> findActiveTask(String clientId) {
            return txTasks.values().stream()
                    .filter(task -> task.clientId().equals(clientId) && task.active())
                    .findFirst();
        }

        @Override
        public Optional<RelationshipTask> findLatestClosedTask(String clientId) {
            return txTasks.values().stream()
                    .filter(task -> task.clientId().equals(clientId) && !task.active())
                    .max(Comparator.comparing(RelationshipTask::updatedAt)
                            .thenComparing(RelationshipTask::taskId));
        }

        @Override
        public Set<String> findFingerprints(String clientId, Collection<String> fingerprints) {
            Set<String> requested = Set.copyOf(fingerprints);
            Set<String> found = new HashSet<>();
            txTasks.values().stream().filter(task -> task.clientId().equals(clientId))
                    .flatMap(task -> task.evidence().stream())
                    .map(RelationshipTask.Evidence::fingerprint)
                    .filter(requested::contains)
                    .forEach(found::add);
            return Set.copyOf(found);
        }

        @Override
        public Optional<RelationshipTask> findLatestTaskContainingAny(
                String clientId, Collection<String> fingerprints
        ) {
            Set<String> requested = Set.copyOf(fingerprints);
            return txTasks.values().stream()
                    .filter(task -> task.clientId().equals(clientId))
                    .filter(task -> task.evidence().stream()
                            .anyMatch(item -> requested.contains(item.fingerprint())))
                    .max(Comparator.comparing(RelationshipTask::createdAt)
                            .thenComparing(RelationshipTask::taskId));
        }

        @Override
        public Optional<RelationshipTask> findTask(String taskId) {
            return Optional.ofNullable(txTasks.get(taskId));
        }

        @Override
        public boolean tryReserveTaskCreation(String agentSubject, LocalDate singaporeDate, int limit) {
            AgentDay key = new AgentDay(agentSubject, singaporeDate);
            int current = txCreationCounts.getOrDefault(key, 0);
            if (current >= limit) return false;
            txCreationCounts.put(key, current + 1);
            return true;
        }

        @Override
        public void saveTask(RelationshipTask task, long expectedVersion) {
            RelationshipTask stored = txTasks.get(task.taskId());
            if (expectedVersion == 0) {
                if (stored != null || txTasks.values().stream().anyMatch(existing ->
                        existing.clientId().equals(task.clientId()) && existing.active())) {
                    throw new ConflictException("Task creation conflict");
                }
            } else if (stored == null || stored.version() != expectedVersion) {
                throw new ConflictException("Task version conflict");
            }
            if (task.version() != expectedVersion + 1) {
                throw new ConflictException("Task version must advance exactly once");
            }
            txTasks.put(task.taskId(), task);
        }

        @Override
        public void saveProcessedFinding(ProcessedFinding processedFinding) {
            if (failProcessedFindingSave) throw new IllegalStateException("Injected write failure");
            String findingId = processedFinding.finding().findingId();
            if (txFindings.containsKey(findingId)) throw new ConflictException("Finding already processed");
            txFindings.put(findingId, processedFinding);
        }
    }

    private record AgentDay(String agentSubject, LocalDate singaporeDate) { }
}
