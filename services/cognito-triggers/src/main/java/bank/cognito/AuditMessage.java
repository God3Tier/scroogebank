package bank.cognito;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import lombok.experimental.StandardException;

/**
 * The auditor's message for a login or a forgotten-password reset (ADR-0040 decision 3).
 *
 * <p>Only the user's sub and the user pool and app client IDs are copied from the Cognito event.
 * The user's attributes (email, phone number, name) never leave the function.
 */
@JsonPropertyOrder({"event_id", "event_type", "action", "source_service", "actor_id", "payload"})
record AuditMessage(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("action") String action,
        @JsonProperty("source_service") String sourceService,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("payload") Payload payload) {

    static final String SOURCE_SERVICE = "COGNITO_TRIGGERS";
    static final String EVENT_TYPE = "AUTHENTICATION";

    /**
     * The trigger sources audited, and the action each is recorded as. Cognito also runs the
     * PostConfirmation trigger when a sign-up is confirmed (PostConfirmation_ConfirmSignUp); that
     * and every other source are not audited.
     */
    static final Map<String, String> ACTIONS = Map.of(
            "PostAuthentication_Authentication", "LOGIN",
            "PostConfirmation_ConfirmForgotPassword", "PASSWORD_RESET");

    /** RFC 3339 in UTC to the millisecond, for example 2026-10-13T05:54:00.123Z. */
    private static final DateTimeFormatter UTC_MILLIS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** What the auditor checks for COGNITO_TRIGGERS; app_client_id is left out when Cognito names none. */
    @JsonPropertyOrder({"user_pool_id", "trigger_source", "datetime", "app_client_id"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Payload(
            @JsonProperty("user_pool_id") String userPoolId,
            @JsonProperty("trigger_source") String triggerSource,
            @JsonProperty("datetime") String dateTime,
            @JsonProperty("app_client_id") String appClientId) {
    }

    /** The Cognito event lacks a field the audit event needs. */
    @StandardException
    static class InvalidTriggerEventException extends IllegalArgumentException {
    }

    /**
     * Returns the message for cognitoEvent, or empty if its trigger source is not audited.
     *
     * @throws InvalidTriggerEventException if the event names no user or user pool
     */
    static Optional<AuditMessage> from(Map<String, ?> cognitoEvent, Instant now, String eventId) {
        String triggerSource = text(cognitoEvent.get("triggerSource"));
        String action = triggerSource == null ? null : ACTIONS.get(triggerSource);
        if (action == null) {
            return Optional.empty();
        }

        String sub = text(object(object(cognitoEvent, "request"), "userAttributes").get("sub"));
        if (sub == null || sub.isBlank()) {
            throw new InvalidTriggerEventException("request.userAttributes.sub is missing");
        }
        String userPoolId = text(cognitoEvent.get("userPoolId"));
        if (userPoolId == null || userPoolId.isEmpty()) {
            throw new InvalidTriggerEventException("userPoolId is missing");
        }
        String appClientId = text(object(cognitoEvent, "callerContext").get("clientId"));

        return Optional.of(new AuditMessage(eventId, EVENT_TYPE, action, SOURCE_SERVICE, sub,
                new Payload(userPoolId, triggerSource, UTC_MILLIS.format(now),
                        appClientId == null || appClientId.isEmpty() ? null : appClientId)));
    }

    private static Map<?, ?> object(Map<?, ?> parent, String name) {
        return parent.get(name) instanceof Map<?, ?> child ? child : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }
}
