package bank.cognito;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

class HandlerTest {

    /** Records the bodies it is given; throws for the first `failures` sends. */
    private static final class FakeQueue implements QueueSender {
        int failures;
        int calls;
        final List<JsonNode> sent = new ArrayList<>();

        @Override
        @SneakyThrows
        public void send(String body) {
            calls++;
            if (calls <= failures) {
                throw new IllegalStateException("queue unreachable");
            }
            sent.add(TestEvents.JSON.readTree(body));
        }
    }

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final FakeQueue queue = new FakeQueue();

    private Handler handler(Supplier<QueueSender> senders, Duration budget) {
        return new Handler(senders, new PrintStream(output, true, StandardCharsets.UTF_8), budget);
    }

    private Handler handler() {
        return handler(() -> queue, Handler.SEND_BUDGET);
    }

    private String out() {
        return output.toString(StandardCharsets.UTF_8);
    }

    private List<JsonNode> logs() {
        return TestEvents.lines(out());
    }

    /** A Lambda context with remainingMillis left before Lambda's timeout. */
    private static Context context(int remainingMillis) {
        return (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[] {Context.class},
                (proxy, method, args) -> method.getName().equals("getRemainingTimeInMillis") ? remainingMillis : null);
    }

    @Test
    void aLoginIsSentAndTheEventReturnedUnchanged() {
        Map<String, Object> event = TestEvents.load("post-authentication");

        Map<String, Object> result = handler().handleRequest(event, context(5000));

        assertThat(result).isSameAs(event).isEqualTo(TestEvents.load("post-authentication"));
        assertThat(queue.sent).hasSize(1);
        JsonNode message = queue.sent.getFirst();
        assertThat(message.path("action").asText()).isEqualTo("LOGIN");
        assertThat(message.path("actor_id").asText()).isEqualTo("4a1b2c3d-0000-4000-8000-000000000001");
        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line.path("level").asText()).isEqualTo("INFO");
            assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SENT");
            assertThat(line.path("event_id").asText()).isEqualTo(message.path("event_id").asText());
            assertThat(line.path("action").asText()).isEqualTo("LOGIN");
            assertThat(line.path("attempts").asInt()).isEqualTo(1);
        });
    }

    @Test
    void aForgottenPasswordResetIsSent() {
        handler().handleRequest(TestEvents.load("confirm-forgot-password"), null);

        assertThat(queue.sent).singleElement()
                .satisfies(message -> assertThat(message.path("action").asText()).isEqualTo("PASSWORD_RESET"));
    }

    @Test
    void aSignUpConfirmationSendsAndLogsNothing() {
        Map<String, Object> event = TestEvents.load("confirm-sign-up");

        assertThat(handler().handleRequest(event, null)).isSameAs(event);
        assertThat(queue.calls).isZero();
        assertThat(out()).isEmpty();
    }

    @Test
    void eachCallHasANewEventId() {
        Handler handler = handler();
        handler.handleRequest(TestEvents.load("post-authentication"), null);
        handler.handleRequest(TestEvents.load("post-authentication"), null);

        assertThat(queue.sent.get(0).path("event_id").asText()).isNotEqualTo(queue.sent.get(1).path("event_id").asText());
    }

    @Test
    void aFailedSendIsRetried() {
        queue.failures = 2;

        handler().handleRequest(TestEvents.load("post-authentication"), null);

        assertThat(queue.sent).hasSize(1);
        assertThat(logs().getFirst().path("attempts").asInt()).isEqualTo(3);
    }

    @Test
    void whenEverySendFailsTheEventIsLoggedForReplay() {
        queue.failures = 1000;
        Map<String, Object> event = TestEvents.load("post-authentication");

        assertThat(handler(() -> queue, Duration.ZERO).handleRequest(event, null)).isSameAs(event);

        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line.path("level").asText()).isEqualTo("ERROR");
            assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SEND_FAILED");
            assertThat(line.path("error").asText()).isEqualTo("IllegalStateException: queue unreachable");
            assertThat(line.path("audit_event").path("action").asText()).isEqualTo("LOGIN");
            assertThat(line.path("audit_event").path("actor_id").asText()).isEqualTo("4a1b2c3d-0000-4000-8000-000000000001");
            assertThat(line.path("audit_event").path("event_id").asText()).isNotBlank();
            assertThat(line.path("trigger_source").asText()).isEqualTo("PostAuthentication_Authentication");
        });
    }

    @Test
    void withoutAQueueUrlTheEventIsLoggedForReplay() {
        Map<String, Object> event = TestEvents.load("post-authentication");

        Handler handler = handler(() -> SqsQueueSender.fromEnvironment(Map.<String, String>of()::get), Handler.SEND_BUDGET);

        assertThat(handler.handleRequest(event, null)).isSameAs(event);
        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SEND_FAILED");
            assertThat(line.path("error").asText()).isEqualTo("IllegalStateException: AUDIT_QUEUE_URL is not set");
            assertThat(line.path("audit_event").path("action").asText()).isEqualTo("LOGIN");
        });
    }

    @Test
    void whenTheSdkCannotLoadTheLoginStillGoesAhead() {
        Map<String, Object> event = TestEvents.load("post-authentication");
        Handler handler = handler(() -> {
            throw new NoClassDefFoundError("software/amazon/awssdk/services/sqs/SqsClient");
        }, Handler.SEND_BUDGET);

        assertThat(handler.handleRequest(event, null)).isSameAs(event);
        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SEND_FAILED");
            assertThat(line.path("error").asText())
                    .isEqualTo("NoClassDefFoundError: software/amazon/awssdk/services/sqs/SqsClient");
        });
    }

    @Test
    void anUnreadableEventIsLoggedWithoutAnAuditEvent() {
        Map<String, Object> event = TestEvents.load("post-authentication");
        TestEvents.attributes(event).remove("sub");

        assertThat(handler().handleRequest(event, null)).isSameAs(event);
        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SEND_FAILED");
            assertThat(line.path("audit_event").isNull()).isTrue();
            assertThat(line.path("trigger_source").asText()).isEqualTo("PostAuthentication_Authentication");
            assertThat(line.path("user_pool_id").asText()).isEqualTo("ap-southeast-1_Example01");
        });
        assertThat(queue.calls).isZero();
    }

    @Test
    void evenANullEventIsReturned() {
        assertThat(handler().handleRequest(null, null)).isNull();

        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SEND_FAILED");
            assertThat(line.path("trigger_source").isNull()).isTrue();
        });
    }

    @Test
    void logsNeverHoldUserAttributes() {
        handler().handleRequest(TestEvents.load("post-authentication"), null);
        queue.failures = 1000;
        handler(() -> queue, Duration.ZERO).handleRequest(TestEvents.load("confirm-forgot-password"), null);

        assertThat(out()).contains("AUDIT_SENT", "AUDIT_SEND_FAILED");
        for (String value : TestEvents.PERSONAL_DATA) {
            assertThat(out()).doesNotContain(value);
        }
    }

    @Test
    void theSenderIsCreatedOnceAndReused() {
        List<FakeQueue> created = new ArrayList<>();
        Handler handler = handler(() -> {
            created.add(new FakeQueue());
            return created.getLast();
        }, Handler.SEND_BUDGET);

        handler.handleRequest(TestEvents.load("post-authentication"), null);
        handler.handleRequest(TestEvents.load("confirm-forgot-password"), null);

        assertThat(created).singleElement().satisfies(sender -> assertThat(sender.sent).hasSize(2));
    }

    @Test
    void theBudgetIsCutWhenLambdaWouldTimeOutSooner() {
        Handler handler = handler();

        assertThat(handler.budget(null)).isEqualTo(Handler.SEND_BUDGET);
        assertThat(handler.budget(context(5000))).isEqualTo(Handler.SEND_BUDGET);
        assertThat(handler.budget(context(2000))).isEqualTo(Duration.ofMillis(2000).minus(Handler.TIMEOUT_MARGIN));
    }
}
