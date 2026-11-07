package bank.engagement.drafting;

import bank.engagement.audit.AuditAction;
import bank.engagement.audit.AuditTrail;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Discards open drafts the agent has left alone for
 * {@link DraftProperties#expireAfter()}, clearing what they typed, so a
 * draft's text is kept only while it is in use (ADR-0068 decision 4). It runs
 * soon after start-up, since the stack is stopped between sessions, then
 * daily, in every task: rows are locked with {@code SKIP LOCKED}, so two tasks
 * never expire the same draft.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
class DraftExpiry {

    static final int BATCH_SIZE = 100;

    private final EmailDraftRepository repository;
    private final AuditTrail audit;
    private final DraftProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final NewsDraftStore newsStore;

    @Scheduled(initialDelayString = "${engagement.drafts.expiry-initial-delay:5m}",
            fixedDelayString = "${engagement.drafts.expiry-interval:24h}")
    void run() {
        try {
            int expired = expire();
            if (expired > 0) {
                log.info("Expired {} open drafts", expired);
            }
        } catch (RuntimeException ex) {
            log.warn("draft_expiry_failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** @return how many drafts were expired */
    int expire() {
        Instant before = clock.instant().minus(properties.expireAfter());
        int total = 0;
        int batch;
        do {
            batch = transactions.execute(status -> {
                List<EmailDraft> expired = repository.lockExpired(before, BATCH_SIZE);
                for (EmailDraft draft : expired) {
                    draft.discard(clock.instant());
                    // A News Alert draft gives its reports back; the clients keep their status.
                    newsStore.release(draft.getId());
                    Map<String, Object> data = DraftService.auditData(draft);
                    data.put("reason", "expired");
                    audit.record(AuditAction.DRAFT_DISCARD, AuditTrail.SYSTEM, data);
                }
                repository.saveAllAndFlush(expired);
                return expired.size();
            });
            total += batch;
        } while (batch == BATCH_SIZE);
        return total;
    }
}
