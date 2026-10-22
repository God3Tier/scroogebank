package bank.engagement.drafting;

import java.util.UUID;

/**
 * A new draft's text and where it came from.
 *
 * @param sourceReason why a template was used; null for an AI draft
 * @param holidayId    the holiday the text refers to, if any
 */
record GeneratedDraft(String subject, String body, DraftSource source, String sourceReason, String modelId,
        String promptVersion, UUID holidayId) {

    // The contract's sourceReason values.
    static final String AI_DISABLED = "ai_disabled";
    static final String DAILY_CAP_REACHED = "daily_cap_reached";
    static final String MONTHLY_QUOTA_REACHED = "monthly_quota_reached";
    static final String MODEL_TIMEOUT = "model_timeout";
    static final String MODEL_ERROR = "model_error";
    static final String SCREEN_REJECTED = "screen_rejected";
}
