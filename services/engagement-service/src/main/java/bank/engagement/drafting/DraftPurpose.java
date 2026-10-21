package bank.engagement.drafting;

public enum DraftPurpose {
    CHECK_IN("A friendly check-in with a client the agent has not spoken to for a while."),
    MEETING_FOLLOW_UP("A thank-you after a meeting that has just taken place."),
    HOLIDAY_GREETING("Warm wishes for the upcoming public holiday."),
    PRE_HOLIDAY_REVIEW("An invitation to meet and plan ahead before the upcoming public holiday.");

    private final String description;

    DraftPurpose(String description) {
        this.description = description;
    }

    /** Sent to the model with the purpose; public text, no client data. */
    public String description() {
        return description;
    }

    /** Holiday purposes need a holiday in the next 30 days (ADR-0054 decision 8). */
    public boolean needsHoliday() {
        return this == HOLIDAY_GREETING || this == PRE_HOLIDAY_REVIEW;
    }
}
