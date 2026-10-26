package bank.clienthealth.explanation;

import bank.clienthealth.audit.RelationshipHealthAuditEventFactory;
import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.RelationshipTaskPort;
import bank.clienthealth.contract.TaskUpsertResult;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.task.port.TaskRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/** Adds wording after deterministic task admission; provider failures always return templates. */
public final class ExplainingRelationshipTaskPort implements RelationshipTaskPort {
    private final RelationshipTaskPort tasks;
    private final TaskRepository taskRepository;
    private final ExplanationRepository explanations;
    private final ExplanationProvider provider;
    private final Clock clock;

    public ExplainingRelationshipTaskPort(RelationshipTaskPort tasks, TaskRepository taskRepository,
            ExplanationRepository explanations, ExplanationProvider provider, Clock clock) {
        this.tasks = tasks; this.taskRepository = taskRepository; this.explanations = explanations;
        this.provider = provider; this.clock = clock;
    }

    @Override
    public TaskUpsertResult upsertFinding(ClientHealthFinding finding) {
        TaskUpsertResult result = tasks.upsertFinding(finding);
        if (result.outcome() != Outcome.CREATED
                && result.outcome() != Outcome.EVIDENCE_ATTACHED
                && result.outcome() != Outcome.DUPLICATE) {
            return result;
        }
        String prompt = ExplanationRequest.PROMPT_VERSION;
        if (explanations.find(result.taskId(), finding.findingId(), result.taskVersion(), prompt).isPresent()) {
            return result;
        }
        var task = taskRepository.inTransaction(session -> session.findTask(result.taskId()).orElseThrow());
        ExplanationResult generated = provider.explain(ExplanationRequestFactory.from(task));
        String explanationId = hash(result.taskId() + "|" + finding.findingId() + "|"
                + result.taskVersion() + "|" + prompt);
        var record = new ExplanationRepository.Record(explanationId, result.taskId(), finding.findingId(),
                result.taskVersion(), generated.summary(), generated.suggestedReviewStep(),
                generated.disclaimer(), generated.evidenceCodes(), generated.source(), prompt,
                generated.modelId(), clock.instant());
        explanations.saveIfAbsent(record, RelationshipHealthAuditEventFactory.explanation(
                explanationId, task, finding.correlationId(), record.generatedAt(), generated.source(), prompt));
        return result;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
