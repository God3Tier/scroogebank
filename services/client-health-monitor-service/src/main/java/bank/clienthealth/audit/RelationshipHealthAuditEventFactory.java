package bank.clienthealth.audit;

import bank.clienthealth.audit.RelationshipHealthAuditEvent.Action;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.AuditState;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.EntityType;
import bank.clienthealth.audit.RelationshipHealthAuditEvent.Payload;
import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.TaskUpsertResult.Outcome;
import bank.clienthealth.task.domain.RelationshipTask.HistoryEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Builds deterministic, redacted audit events from Client Health facts. */
public final class RelationshipHealthAuditEventFactory {
    private RelationshipHealthAuditEventFactory() { }

    public static RelationshipHealthAuditEvent taskHistory(
            String taskId, String clientId, HistoryEntry history
    ) {
        Action action = switch (history.action()) {
            case CREATED -> Action.TASK_CREATED;
            case EVIDENCE_ADDED -> Action.TASK_EVIDENCE_ADDED;
            case ACCEPTED -> Action.TASK_ACCEPTED;
            case COMPLETED -> Action.TASK_COMPLETED;
            case DISMISSED -> Action.TASK_DISMISSED;
        };
        Payload payload = new Payload(
                "1",
                EntityType.RELATIONSHIP_TASK,
                taskId,
                history.occurredAt(),
                history.correlationId(),
                hash(clientId),
                state(history.beforeStatus(), history.beforeSeverity(), history.taskVersion() - 1),
                state(history.afterStatus(), history.afterSeverity(), history.taskVersion()),
                history.dispositionCode());
        return envelope(action, history.actor(), payload);
    }

    public static RelationshipHealthAuditEvent suppression(
            ClientHealthFinding finding, Outcome outcome
    ) {
        if (outcome == null || !outcome.suppressed()) {
            throw new IllegalArgumentException("suppression outcome is required");
        }
        Payload payload = new Payload(
                "1",
                EntityType.FINDING,
                finding.findingId(),
                finding.detectedAt(),
                finding.correlationId(),
                hash(finding.clientId()),
                null,
                new AuditState(null, finding.priorityInputs().highestSeverity(), null),
                outcome.name());
        return envelope(Action.SIGNAL_SUPPRESSED, "SYSTEM", payload);
    }

    public static RelationshipHealthAuditEvent analysis(
            String runId,
            Action action,
            Instant occurredAt,
            String correlationId,
            String dispositionCode
    ) {
        if (action != Action.ANALYSIS_RUN_STARTED
                && action != Action.ANALYSIS_RUN_COMPLETED
                && action != Action.ANALYSIS_RUN_FAILED) {
            throw new IllegalArgumentException("analysis action is required");
        }
        Payload payload = new Payload(
                "1", EntityType.ANALYSIS_RUN, runId, occurredAt,
                correlationId, null, null, null, dispositionCode);
        return envelope(action, "SYSTEM", payload);
    }

    private static RelationshipHealthAuditEvent envelope(
            Action action, String actorId, Payload payload
    ) {
        String eventIdentity = action + "|" + payload.entityType() + "|"
                + payload.entityId() + "|" + payload.correlationId() + "|"
                + payload.occurredAt() + "|" + payload.dispositionCode();
        return new RelationshipHealthAuditEvent(hash(eventIdentity), action, actorId, payload);
    }

    private static AuditState state(
            bank.clienthealth.task.domain.RelationshipTask.Status status,
            ClientHealthFinding.Severity severity,
            long taskVersion
    ) {
        if (status == null && severity == null) {
            return null;
        }
        return new AuditState(status, severity, taskVersion > 0 ? taskVersion : null);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
