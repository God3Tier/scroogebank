package bank.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.experimental.UtilityClass;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Sends audit events the triggers logged as AUDIT_SEND_FAILED back to the audit queue (ADR-0040
 * decision 6): {@code Replay --queue-url URL FILE}, where FILE is the JSON output of
 * {@code aws logs filter-log-events}.
 *
 * <p>Each event is sent exactly as logged, with its event_id, so the auditor drops any it had
 * stored after all. Exits 1 if any logged failure could not be replayed, and 2 on a usage error.
 */
@UtilityClass
public class Replay {

    private final ObjectMapper JSON = new ObjectMapper();

    /** The audit events found, and how many AUDIT_SEND_FAILED lines held none. */
    record Logged(List<JsonNode> events, int withoutEvent) {
    }

    public void main(String[] args) {
        System.exit(run(args, Replay::sqs, System.out));
    }

    /** Sends with the SDK's defaults, retries included: an operator is waiting, not Cognito. */
    private QueueSender sqs(String queueUrl) {
        SqsClient client = SqsClient.create();
        return body -> client.sendMessage(request -> request.queueUrl(queueUrl).messageBody(body));
    }

    int run(String[] args, Function<String, QueueSender> senderFor, PrintStream out) {
        if (args.length != 3 || !args[0].equals("--queue-url")) {
            out.println("usage: Replay --queue-url URL FILE");
            return 2;
        }
        JsonNode filterOutput;
        try {
            filterOutput = JSON.readTree(new File(args[2]));
        } catch (IOException e) {
            out.println("cannot read " + args[2] + ": " + e.getMessage());
            return 2;
        }
        if (filterOutput == null || !filterOutput.isObject()) {
            out.println(args[2] + " is not the output of aws logs filter-log-events");
            return 2;
        }

        Logged logged = loggedEvents(filterOutput);
        QueueSender sender = senderFor.apply(args[1]);
        int failed = 0;
        for (JsonNode event : logged.events()) {
            String eventId = event.path("event_id").asText();
            try {
                sender.send(JSON.writeValueAsString(event));
                out.println("sent " + eventId);
            } catch (Exception e) {
                failed++;
                out.println("not sent " + eventId + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        out.printf("%d of %d events sent%n", logged.events().size() - failed, logged.events().size());
        if (logged.withoutEvent() > 0) {
            out.printf("%d AUDIT_SEND_FAILED lines hold no audit event to replay; check them in the log%n",
                    logged.withoutEvent());
        }
        return failed > 0 || logged.withoutEvent() > 0 ? 1 : 0;
    }

    /** Picks the AUDIT_SEND_FAILED lines out of filter-log-events output; other lines are ignored. */
    Logged loggedEvents(JsonNode filterOutput) {
        List<JsonNode> events = new ArrayList<>();
        int withoutEvent = 0;
        for (JsonNode entry : filterOutput.path("events")) {
            JsonNode line;
            try {
                line = JSON.readTree(entry.path("message").asText());
            } catch (IOException e) {
                continue;
            }
            if (line == null || !"AUDIT_SEND_FAILED".equals(line.path("msg").asText())) {
                continue;
            }
            if (line.path("audit_event").isObject()) {
                events.add(line.get("audit_event"));
            } else {
                withoutEvent++;
            }
        }
        return new Logged(events, withoutEvent);
    }
}
