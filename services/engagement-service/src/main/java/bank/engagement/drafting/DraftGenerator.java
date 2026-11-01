package bank.engagement.drafting;

import bank.engagement.holidays.HolidayView;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes a draft: the model when allowed, the template otherwise (ADR-0054
 * decisions 3 to 7). The order is the kill switch, the caps, the model call,
 * then the safety screen; any of them failing gives the template, never an
 * error.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
class DraftGenerator {

    private final AiProperties properties;
    private final AiUsageService usage;
    private final ObjectProvider<DraftModelGateway> models;
    private final DraftSafetyScreen screen;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    /**
     * @param holidays up to three public holidays in the next 30 days, nearest
     *                 first; required non-empty for the holiday purposes
     */
    GeneratedDraft generate(String agentSub, DraftPurpose purpose, DraftTone tone, List<HolidayView> holidays,
            MeetingRecency recency, LocalDate today) {
        DraftModelGateway model = models.getIfAvailable();
        if (!properties.enabled() || model == null) {
            return template(purpose, tone, holidays, GeneratedDraft.AI_DISABLED);
        }
        Optional<String> cap = usage.capReached(agentSub, today);
        if (cap.isPresent()) {
            return template(purpose, tone, holidays, cap.get());
        }

        DraftModelGateway.ModelReply reply;
        try {
            reply = model.invoke(DraftPrompt.SYSTEM, DraftPrompt.userInput(purpose, tone, holidays, recency, mapper));
        } catch (DraftModelException ex) {
            usage.record(agentSub, today, 0, 0);
            return template(purpose, tone, holidays, ex.reason());
        }
        usage.record(agentSub, today, reply.inputTokens(), reply.outputTokens());

        DraftSafetyScreen.Result result = screen.inspect(reply.text(), purpose);
        if (!result.accepted()) {
            log.info("Draft output rejected by the safety screen: {} (blocklist {})", result.reason(),
                    DraftSafetyScreen.BLOCKLIST_VERSION);
            meters.counter("engagement.drafts.screen.rejected", "reason", result.reason()).increment();
            return template(purpose, tone, holidays, GeneratedDraft.SCREEN_REJECTED);
        }
        count(DraftSource.AI, "none");
        return new GeneratedDraft(result.subject(), result.body(), DraftSource.AI, null, properties.modelId(),
                DraftPrompt.VERSION, mentionedHoliday(holidays, result.subject() + "\n" + result.body()));
    }

    private GeneratedDraft template(DraftPurpose purpose, DraftTone tone, List<HolidayView> holidays, String reason) {
        HolidayView holiday = purpose.needsHoliday() && !holidays.isEmpty() ? holidays.getFirst() : null;
        DraftTemplates.Text text = DraftTemplates.render(purpose, tone, holiday);
        count(DraftSource.TEMPLATE, reason);
        return new GeneratedDraft(text.subject(), text.body(), DraftSource.TEMPLATE, reason, null,
                DraftTemplates.VERSION, holiday == null ? null : holiday.id());
    }

    /** The first holiday the model's text names, so the agent sees which one it used. */
    private static UUID mentionedHoliday(List<HolidayView> holidays, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return holidays.stream()
                .filter(holiday -> lower.contains(holiday.name().toLowerCase(Locale.ROOT)))
                .map(HolidayView::id)
                .findFirst()
                .orElse(null);
    }

    private void count(DraftSource source, String reason) {
        meters.counter("engagement.drafts", "source", source.name(), "reason", reason).increment();
    }
}
