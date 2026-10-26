package bank.clienthealth.explanation;

import bank.clienthealth.audit.RelationshipHealthAuditEvent;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExplanationRepository {
    Optional<Record> find(String taskId, String findingId, long taskVersion, String promptVersion);
    Optional<Record> findForTaskVersion(String taskId, long taskVersion);
    Record saveIfAbsent(Record explanation, RelationshipHealthAuditEvent audit);

    record Record(
            String explanationId, String taskId, String findingId, long taskVersion,
            String summary, String suggestedReviewStep, String disclaimer,
            List<SignalType> evidenceCodes, ExplanationResult.Source source,
            String promptTemplateVersion, String modelId, Instant generatedAt
    ) {
        public Record { evidenceCodes = List.copyOf(evidenceCodes); }
        public String displayText() {
            return String.join(" ", summary, suggestedReviewStep, disclaimer + ".");
        }
    }
}
