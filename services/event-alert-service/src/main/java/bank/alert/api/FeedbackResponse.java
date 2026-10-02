package bank.alert.api;

import bank.alert.domain.AlertFeedback;
import bank.alert.domain.FeedbackRating;
import bank.alert.domain.FeedbackReasonCode;

import java.time.Instant;
import java.util.UUID;

public record FeedbackResponse(
        UUID id,
        UUID alertId,
        FeedbackRating rating,
        FeedbackReasonCode reasonCode,
        String actorSubject,
        Instant recordedAt,
        long alertVersion) {

    public static FeedbackResponse from(AlertFeedback feedback) {
        return new FeedbackResponse(
                feedback.getId(), feedback.getAlertId(), feedback.getRating(),
                feedback.getReasonCode(), feedback.getActorSubject(),
                feedback.getRecordedAt(), feedback.getAlertVersion());
    }
}
