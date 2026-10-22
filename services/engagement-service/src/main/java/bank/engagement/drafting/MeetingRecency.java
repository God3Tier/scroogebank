package bank.engagement.drafting;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * How long since the agent last met the client, in bands, so the model never
 * sees a date tied to a client (ADR-0054 decision 2).
 */
@RequiredArgsConstructor
enum MeetingRecency {
    NONE("NONE"),
    UNDER_1_MONTH("UNDER_1_MONTH"),
    ONE_TO_3_MONTHS("1_TO_3_MONTHS"),
    OVER_3_MONTHS("OVER_3_MONTHS");

    private final String label;

    String label() {
        return label;
    }

    static MeetingRecency of(Optional<Instant> lastMeeting, Instant now) {
        if (lastMeeting.isEmpty()) {
            return NONE;
        }
        Duration since = Duration.between(lastMeeting.get(), now);
        if (since.compareTo(Duration.ofDays(30)) < 0) {
            return UNDER_1_MONTH;
        }
        return since.compareTo(Duration.ofDays(91)) <= 0 ? ONE_TO_3_MONTHS : OVER_3_MONTHS;
    }
}
