package bank.cognito;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * The time budget with the real SDK. One SQS endpoint refuses connections and one never answers.
 * Both are sockets on this machine, so these tests need no LocalStack.
 */
class RealSdkTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private static QueueSender senderFor(int port) {
        URI endpoint = URI.create("http://127.0.0.1:" + port);
        return SqsQueueSender.create(endpoint + "/000000000000/audit-insert", Region.US_EAST_1,
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")), endpoint);
    }

    /** Runs a login through a handler using sender, and returns how long it took. */
    private Duration invoke(QueueSender sender) {
        Handler handler = new Handler(() -> sender, new PrintStream(output, true, StandardCharsets.UTF_8),
                Handler.SEND_BUDGET);
        long started = System.nanoTime();
        var event = TestEvents.load("post-authentication");
        assertThat(handler.handleRequest(event, null)).isSameAs(event);
        return Duration.ofNanos(System.nanoTime() - started);
    }

    @Test
    void aRefusedConnectionFailsOpenWithinTheBudget() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        Duration took = invoke(senderFor(closedPort));

        assertThat(took).isLessThan(Handler.SEND_BUDGET);
        assertThat(TestEvents.lines(output.toString(StandardCharsets.UTF_8))).singleElement().satisfies(line -> {
            assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SEND_FAILED");
            assertThat(line.path("error").asText()).startsWith("SdkClientException");
            assertThat(line.path("audit_event").path("action").asText()).isEqualTo("LOGIN");
        });
    }

    @Test
    void anEndpointThatNeverAnswersFailsOpenWithinTheBudget() throws Exception {
        List<Socket> held = new CopyOnWriteArrayList<>();
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        held.add(silent.accept());
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            });
            // Created first, so that loading the SDK does not change how many attempts fit.
            QueueSender sender = senderFor(silent.getLocalPort());

            Duration took = invoke(sender);

            // Two attempts, each cut off by the read timeout: the second ends near 2.05 seconds,
            // and a third might not have finished within 3.
            assertThat(took).isBetween(SendBudget.READ_TIMEOUT.multipliedBy(2), Handler.SEND_BUDGET);
            assertThat(TestEvents.lines(output.toString(StandardCharsets.UTF_8))).singleElement().satisfies(line -> {
                assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SEND_FAILED");
                assertThat(line.path("error").asText()).contains("timed out");
            });
            silent.close();
            acceptor.join();
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }
}
