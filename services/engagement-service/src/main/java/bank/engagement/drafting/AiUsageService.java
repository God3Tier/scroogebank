package bank.engagement.drafting;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The daily draft cap per agent and the monthly token quota (ADR-0054
 * decision 7). Reaching either gives a template, never an error.
 */
@Service
class AiUsageService {

    private final AiUsageRepository repository;
    private final AiProperties properties;

    AiUsageService(AiUsageRepository repository, AiProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** @return the {@code sourceReason} when a cap is reached */
    @Transactional(readOnly = true)
    Optional<String> capReached(String agentSub, LocalDate today) {
        if (repository.draftsOn(today, agentSub) >= properties.dailyDraftsPerAgent()) {
            return Optional.of(GeneratedDraft.DAILY_CAP_REACHED);
        }
        if (repository.tokensBetween(today.withDayOfMonth(1), today) >= properties.monthlyTokenQuota()) {
            return Optional.of(GeneratedDraft.MONTHLY_QUOTA_REACHED);
        }
        return Optional.empty();
    }

    /** One model call, counted whether or not its output was used. */
    @Transactional
    void record(String agentSub, LocalDate today, long inputTokens, long outputTokens) {
        repository.addDraft(today, agentSub, inputTokens, outputTokens);
    }
}
