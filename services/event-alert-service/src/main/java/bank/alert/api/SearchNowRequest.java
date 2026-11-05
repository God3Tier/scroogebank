package bank.alert.api;

import jakarta.validation.constraints.Size;

/** Search one company, or every company the caller may search when {@code instrumentId} is absent. */
public record SearchNowRequest(@Size(max = 32) String instrumentId) {
}
