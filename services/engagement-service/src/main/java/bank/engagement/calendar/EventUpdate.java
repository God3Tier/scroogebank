package bank.engagement.calendar;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The contract's {@code EventUpdate}: an {@code EventInput} plus the version it replaces. */
public record EventUpdate(
        @NotBlank @Size(max = 120) String title,
        @Size(max = 1000) String description,
        @Size(max = 200) String location,
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,
        UUID clientId,
        @Size(max = 2) List<Integer> reminderOffsetsMinutes,
        @NotNull @Min(0) Integer version) {
}
