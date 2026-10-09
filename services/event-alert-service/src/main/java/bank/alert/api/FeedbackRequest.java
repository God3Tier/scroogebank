package bank.alert.api;

import bank.alert.domain.FeedbackRating;
import bank.alert.domain.FeedbackReasonCode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotNull FeedbackRating rating,
        FeedbackReasonCode reasonCode,
        @Size(max = 500) String comment,
        @Min(0) long expectedVersion) {
}
