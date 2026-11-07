package bank.cognito;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Lambda entry point for the user pool's PostAuthentication and PostConfirmation triggers.
 *
 * <p>Fails open (ADR-0020 decision 4, ADR-0040 decision 5): whatever goes wrong, it logs
 * AUDIT_SEND_FAILED and returns the Cognito event, so the sign-in or password reset goes ahead.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    /**
     * Sending must finish within this long of the handler starting. Cognito waits 5 seconds for a
     * trigger, cold start included, and the wait cannot be made longer.
     */
    static final Duration SEND_BUDGET = Duration.ofSeconds(3);
    /** Kept back from Lambda's own timeout, in case it is set below 5 seconds. */
    static final Duration TIMEOUT_MARGIN = Duration.ofMillis(250);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<QueueSender> senderFactory;
    private final PrintStream out;
    private final Duration sendBudget;
    private QueueSender sender;

    /**
     * As Lambda creates it. The SDK is loaded by the first send, when the lambda below runs, so
     * even a broken package fails the send, which is logged, rather than the sign-in.
     */
    public Handler() {
        this(() -> SqsQueueSender.fromEnvironment(System::getenv), System.out, SEND_BUDGET);
    }

    /** Sends the audit event for a login or password reset, and always returns event unchanged. */
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        long started = System.nanoTime();
        AuditMessage message = null;
        try {
            message = AuditMessage.from(event, Instant.now(), UUID.randomUUID().toString()).orElse(null);
            if (message == null) {
                return event;
            }
            int attempts = send(message, started + budget(context).toNanos());
            log("INFO", "AUDIT_SENT",
                    "event_id", message.eventId(), "action", message.action(), "attempts", attempts);
        } catch (Exception | LinkageError e) {
            // The full audit event is logged so an operator can replay it (Replay). It is null when
            // the Cognito event could not be read; the trigger source and pool still say where it
            // came from.
            log("ERROR", "AUDIT_SEND_FAILED",
                    "audit_event", message,
                    "trigger_source", field(event, "triggerSource"),
                    "user_pool_id", field(event, "userPoolId"),
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return event;
    }

    /** Sends message by deadline and returns the number of attempts, or throws the last attempt's error. */
    private int send(AuditMessage message, long deadline) throws JsonProcessingException {
        String body = JSON.writeValueAsString(message);
        if (sender == null) {
            sender = senderFactory.get();
        }
        QueueSender queue = sender;
        SendBudget.Outcome outcome = SendBudget.sendWithin(
                () -> queue.send(body), deadline, System::nanoTime, nanos -> Thread.sleep(Duration.ofNanos(nanos)));
        if (!outcome.sent()) {
            throw outcome.error();
        }
        return outcome.attempts();
    }

    /** The time the handler may spend sending: sendBudget, or less if Lambda would time out first. */
    Duration budget(Context context) {
        if (context == null) {
            return sendBudget;
        }
        Duration remaining = Duration.ofMillis(context.getRemainingTimeInMillis()).minus(TIMEOUT_MARGIN);
        return remaining.compareTo(sendBudget) < 0 ? remaining : sendBudget;
    }

    private static Object field(Map<String, Object> event, String name) {
        return event == null ? null : event.get(name);
    }

    /**
     * Writes one JSON line to standard output, which Lambda sends to CloudWatch Logs. The alarm's
     * metric filter matches { $.msg = "AUDIT_SEND_FAILED" }. Never throws.
     */
    private void log(String level, String msg, Object... fields) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("level", level);
        line.put("msg", msg);
        for (int i = 0; i < fields.length; i += 2) {
            line.put((String) fields[i], fields[i + 1]);
        }
        try {
            out.println(JSON.writeValueAsString(line));
        } catch (Exception | LinkageError e) {
            // Still a line the metric filter matches, even if the JSON library itself fails.
            out.println("{\"level\":\"" + level + "\",\"msg\":\"" + msg + "\"}");
        }
    }
}
