package bank.engagement.drafting;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum DraftPurpose {
    CHECK_IN("A friendly check-in with a client the agent has not spoken to for a while."),
    MEETING_FOLLOW_UP("A thank-you after a meeting that has just taken place."),
    HOLIDAY_GREETING("Warm wishes for the upcoming public holiday."),
    PRE_HOLIDAY_REVIEW("An invitation to meet and plan ahead before the upcoming public holiday."),
    HEALTH_CHECK_IN("A friendly note offering a conversation. The agent adds their own reason for getting in "
            + "touch at {{agent_details}}. Never suggest that anything about the client has been noticed."),
    ALERT_FOLLOW_UP("A short note offering to talk through recent developments the client may want to discuss. "
            + "The agent adds the topic at {{agent_details}}. Never name a company, market, event or figure.");

    private final String description;

    /** Sent to the model with the purpose; public text, no client data. */
    public String description() {
        return description;
    }

    /** Holiday purposes need a holiday in the next 30 days (ADR-0054 decision 8). */
    public boolean needsHoliday() {
        return this == HOLIDAY_GREETING || this == PRE_HOLIDAY_REVIEW;
    }

    /** Follow-ups from another feature carry the agent's details slot (ADR-0068 decision 2). */
    public boolean hasDetailsSlot() {
        return this == HEALTH_CHECK_IN || this == ALERT_FOLLOW_UP;
    }
}
