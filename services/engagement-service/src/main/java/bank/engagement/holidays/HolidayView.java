package bank.engagement.holidays;

import java.time.LocalDate;
import java.util.UUID;

/** The contract's {@code Holiday}: public data, safe to show and to send to the model. */
public record HolidayView(UUID id, String countryCode, LocalDate date, String name, boolean observed) {
}
