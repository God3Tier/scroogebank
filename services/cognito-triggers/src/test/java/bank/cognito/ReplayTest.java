package bank.cognito;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayTest {

    private static final Map<String, String> LOGIN = Map.of("event_id", "evt-1", "action", "LOGIN");
    private static final Map<String, String> RESET = Map.of("event_id", "evt-2", "action", "PASSWORD_RESET");

    @TempDir
    Path dir;

    /** One entry of filter-log-events output; Lambda keeps the line's newline. */
    private static Map<String, Object> logEvent(Object line) throws Exception {
        String text = line instanceof String string ? string : TestEvents.JSON.writeValueAsString(line);
        return Map.of("logStreamName", "2026/10/13/[$LATEST]abc", "timestamp", 0, "message", text + "\n");
    }

    private static Map<String, Object> failed(Map<String, String> auditEvent) throws Exception {
        Map<String, Object> line = new HashMap<>();
        line.put("level", "ERROR");
        line.put("msg", "AUDIT_SEND_FAILED");
        line.put("audit_event", auditEvent);
        return logEvent(line);
    }

    private static JsonNode filterOutput(Map<?, ?>... entries) {
        return TestEvents.JSON.valueToTree(Map.of("events", Arrays.asList(entries), "searchedLogStreams", List.of()));
    }

    /** Records what it sends, and the queue URL it was made for; throws for the event IDs in failFor. */
    @RequiredArgsConstructor
    private static final class FakeQueue implements QueueSender {
        final Set<String> failFor;
        final List<JsonNode> sent = new ArrayList<>();
        String url;

        @Override
        @SneakyThrows
        public void send(String body) {
            JsonNode message = TestEvents.JSON.readTree(body);
            if (failFor.contains(message.path("event_id").asText())) {
                throw new IllegalStateException("queue unreachable");
            }
            sent.add(message);
        }
    }

    private record Result(int code, String out) {
    }

    private Result run(JsonNode output, FakeQueue queue) throws Exception {
        Path file = dir.resolve("failed.json");
        Files.writeString(file, TestEvents.JSON.writeValueAsString(output));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = Replay.run(new String[] {"--queue-url", "https://queue", file.toString()}, url -> {
            queue.url = url;
            return queue;
        }, new PrintStream(out, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void onlyFailedSendsWithAnEventArePickedOut() throws Exception {
        JsonNode output = filterOutput(
                failed(LOGIN),
                logEvent(Map.of("level", "INFO", "msg", "AUDIT_SENT", "event_id", "evt-9")),
                logEvent("START RequestId: 1 Version: $LATEST"),
                failed(null),
                failed(RESET));

        Replay.Logged logged = Replay.loggedEvents(output);

        assertThat(logged.events()).containsExactly(TestEvents.JSON.valueToTree(LOGIN), TestEvents.JSON.valueToTree(RESET));
        assertThat(logged.withoutEvent()).isEqualTo(1);
    }

    @Test
    void eventsAreSentAsLogged() throws Exception {
        FakeQueue queue = new FakeQueue(Set.of());

        Result result = run(filterOutput(failed(LOGIN), failed(RESET)), queue);

        assertThat(result.code()).isZero();
        assertThat(queue.url).isEqualTo("https://queue");
        assertThat(queue.sent).containsExactly(TestEvents.JSON.valueToTree(LOGIN), TestEvents.JSON.valueToTree(RESET));
        assertThat(result.out().lines()).containsExactly("sent evt-1", "sent evt-2", "2 of 2 events sent");
    }

    @Test
    void aSendThatFailsIsReportedAndTheRestStillSent() throws Exception {
        FakeQueue queue = new FakeQueue(Set.of("evt-1"));

        Result result = run(filterOutput(failed(LOGIN), failed(RESET)), queue);

        assertThat(result.code()).isEqualTo(1);
        assertThat(queue.sent).containsExactly(TestEvents.JSON.valueToTree(RESET));
        assertThat(result.out()).contains("not sent evt-1: IllegalStateException: queue unreachable", "1 of 2 events sent");
    }

    @Test
    void failuresWithoutAnEventAreReported() throws Exception {
        Result result = run(filterOutput(failed(null)), new FakeQueue(Set.of()));

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("1 AUDIT_SEND_FAILED lines hold no audit event to replay");
    }

    @Test
    void nothingToReplay() throws Exception {
        Result result = run(filterOutput(), new FakeQueue(Set.of()));

        assertThat(result.code()).isZero();
        assertThat(result.out().lines()).containsExactly("0 of 0 events sent");
    }

    @Test
    void aFileThatIsNotFilterOutputIsAUsageError() throws Exception {
        Result result = run(TestEvents.JSON.valueToTree(List.of()), new FakeQueue(Set.of()));

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).contains("is not the output of aws logs filter-log-events");
    }
}
