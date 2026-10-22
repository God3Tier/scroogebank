package bank.engagement.notifications;

import bank.engagement.audit.AuditAction;
import bank.engagement.audit.AuditTrail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The dispatcher's short transactions: claim a batch, then record each result
 * on its own (ADR-0051 decision 4). Sending happens between them, outside any
 * transaction.
 */
@Service
@RequiredArgsConstructor
public class MailDispatchService {

    private final MailQueueRepository repository;
    private final AuditTrail audit;

    /** How long the oldest due row has waited; zero when nothing is waiting. */
    @Transactional(readOnly = true)
    public Duration lag(Instant now) {
        return repository.findOldestDue(now).map(due -> Duration.between(due, now)).orElse(Duration.ZERO);
    }

    @Transactional
    public List<QueuedMail> claim(Instant now, int batchSize, Duration lease) {
        List<MailQueueEntry> rows = repository.findClaimable(now, batchSize);
        rows.forEach(row -> row.lease(now.plus(lease)));
        return repository.saveAll(rows).stream().map(MailQueueEntry::toQueuedMail).toList();
    }

    /** SES accepted the message. Recorded even if the row was replaced meanwhile: it went. */
    @Transactional
    public void sent(UUID id, String messageId, Instant now) {
        repository.findById(id).ifPresent(row -> {
            row.sent(messageId, now);
            Map<String, Object> data = data(row);
            data.put("sesMessageId", messageId);
            audit.record(row.getKind().isCalendar() ? AuditAction.INVITE_SENT : AuditAction.REMINDER_SENT,
                    AuditTrail.SYSTEM, data);
        });
    }

    /** Not sent: {@code MISSED} for a meeting that has started, {@code CANCELLED} otherwise. */
    @Transactional
    public void skipped(UUID id, MailStatus status, Instant now) {
        repository.findById(id).filter(row -> row.getStatus() == MailStatus.PENDING).ifPresent(row -> {
            row.close(status, null, now);
            if (status == MailStatus.MISSED) {
                audit.record(AuditAction.REMINDER_MISSED, AuditTrail.SYSTEM, data(row));
            }
        });
    }

    /** Cannot be sent at all, such as an agent with no address. */
    @Transactional
    public void failed(UUID id, String errorCode, Instant now) {
        repository.findById(id).filter(row -> row.getStatus() == MailStatus.PENDING).ifPresent(row -> {
            row.close(MailStatus.FAILED, errorCode, now);
            auditFailure(row, errorCode);
        });
    }

    /** A send that may work later: retried after the next delay, or {@code FAILED} when none is left. */
    @Transactional
    public void retry(UUID id, String errorCode, List<Duration> retryDelays, Instant now) {
        repository.findById(id).filter(row -> row.getStatus() == MailStatus.PENDING).ifPresent(row -> {
            if (!row.failedAttempt(errorCode, retryDelays, now)) {
                auditFailure(row, errorCode);
            }
        });
    }

    private void auditFailure(MailQueueEntry row, String errorCode) {
        Map<String, Object> data = data(row);
        data.put("errorCode", errorCode);
        audit.record(row.getKind().isCalendar() ? AuditAction.INVITE_FAILED : AuditAction.REMINDER_FAILED,
                AuditTrail.SYSTEM, data);
    }

    private static Map<String, Object> data(MailQueueEntry row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mailId", row.getId().toString());
        data.put("calendarEventId", row.getEventId().toString());
        data.put("kind", row.getKind().name());
        if (row.getKind() == MailKind.REMINDER) {
            data.put("offsetMinutes", row.getOffsetMinutes());
        } else {
            data.put("sequence", row.getIcsSequence());
        }
        data.put("attempts", row.getAttempts());
        return data;
    }
}
