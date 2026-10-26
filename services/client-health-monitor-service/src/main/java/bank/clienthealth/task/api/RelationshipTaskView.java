package bank.clienthealth.task.api;

import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.task.domain.RelationshipTask;
import bank.clienthealth.task.domain.RelationshipTask.Action;
import bank.clienthealth.task.domain.RelationshipTask.Status;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import bank.clienthealth.explanation.ExplanationRepository;

/** Contract-D response models; internal fingerprints and evidence details are excluded. */
public final class RelationshipTaskView {
    public static final String SCHEMA_VERSION = "1";

    private RelationshipTaskView() { }

    public static Page page(List<RelationshipTask> tasks, String nextCursor) {
        return new Page(SCHEMA_VERSION, tasks.stream().map(RelationshipTaskView::task).toList(), nextCursor);
    }

    public static Page pageViews(List<Task> tasks, String nextCursor) {
        return new Page(SCHEMA_VERSION, List.copyOf(tasks), nextCursor);
    }

    public static Task task(RelationshipTask value) {
        return task(value, null);
    }

    public static Task task(RelationshipTask value, ExplanationRepository.Record explanation) {
        List<Evidence> evidence = value.evidence().stream()
                .map(item -> new Evidence(item.signal().type(), item.signal().accountId(),
                        item.signal().ruleVersion(), item.signal().severity()))
                .toList();
        return new Task(SCHEMA_VERSION, value.taskId(), value.clientId(),
                value.assignment().agentSubject(), value.status(), value.severity(),
                explanation == null ? null : new Explanation(explanation.displayText(),
                        explanation.source().name(), explanation.generatedAt()),
                evidence, value.version(), value.createdAt(), value.updatedAt());
    }

    public static History history(RelationshipTask value) {
        List<HistoryItem> items = value.history().stream()
                .map(item -> new HistoryItem(item.taskVersion(), item.action(), item.actor(),
                        item.occurredAt(), item.beforeStatus(), item.afterStatus(),
                        item.beforeSeverity(), item.afterSeverity(), item.addedFingerprints().size(),
                        item.dispositionCode(), item.note()))
                .toList();
        return new History(SCHEMA_VERSION, value.taskId(), items);
    }

    public record Page(String schemaVersion, List<Task> items, String nextCursor) { }

    public record Task(
            String schemaVersion,
            String taskId,
            String clientId,
            String assignedAgentSubject,
            Status status,
            Severity severity,
            @JsonInclude(JsonInclude.Include.NON_NULL) Explanation explanation,
            List<Evidence> evidence,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record Explanation(String text, String source, Instant generatedAt) { }

    public record Evidence(SignalType type, String accountId, String ruleVersion, Severity severity) { }

    public record History(String schemaVersion, String taskId, List<HistoryItem> items) { }

    public record HistoryItem(
            long taskVersion,
            Action action,
            String actor,
            Instant occurredAt,
            Status beforeStatus,
            Status afterStatus,
            Severity beforeSeverity,
            Severity afterSeverity,
            int evidenceAddedCount,
            String dispositionCode,
            String note
    ) { }
}
