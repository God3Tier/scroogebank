package bank.cognito;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/** The handler and the replay tool against SQS in LocalStack (Testcontainers, so Docker is needed). */
@Testcontainers
class LocalStackTest {

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
            .withServices(LocalStackContainer.Service.SQS);

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private SqsClient sqs;
    private String queueUrl;

    @BeforeEach
    void createQueue() {
        sqs = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(region())
                .credentialsProvider(credentials())
                .build();
        queueUrl = sqs.createQueue(r -> r.queueName("audit-insert-" + UUID.randomUUID())).queueUrl();
    }

    @AfterEach
    void deleteQueue() {
        sqs.deleteQueue(r -> r.queueUrl(queueUrl));
        sqs.close();
    }

    private static Region region() {
        return Region.of(LOCALSTACK.getRegion());
    }

    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
    }

    private Handler handlerSendingTo(String url) {
        return new Handler(() -> SqsQueueSender.create(url, region(), credentials(), LOCALSTACK.getEndpoint()),
                new PrintStream(output, true, StandardCharsets.UTF_8), Handler.SEND_BUDGET);
    }

    private List<JsonNode> received() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        for (Message message : sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(2))
                .messages()) {
            bodies.add(TestEvents.JSON.readTree(message.body()));
        }
        return bodies;
    }

    @Test
    void aLoginReachesTheQueueAsTheAuditorReadsIt() throws Exception {
        handlerSendingTo(queueUrl).handleRequest(TestEvents.load("post-authentication"), null);

        JsonNode message = received().getFirst();
        assertThat(message.path("source_service").asText()).isEqualTo("COGNITO_TRIGGERS");
        assertThat(message.path("event_type").asText()).isEqualTo("AUTHENTICATION");
        assertThat(message.path("action").asText()).isEqualTo("LOGIN");
        assertThat(message.path("actor_id").asText()).isEqualTo("4a1b2c3d-0000-4000-8000-000000000001");
        assertThat(message.path("payload").path("user_pool_id").asText()).isEqualTo("ap-southeast-1_Example01");
        assertThat(TestEvents.lines(output.toString(StandardCharsets.UTF_8))).singleElement().satisfies(line -> {
            assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SENT");
            assertThat(line.path("event_id").asText()).isEqualTo(message.path("event_id").asText());
        });
    }

    @Test
    void aFailedSendReplaysWithTheSameEventId(@TempDir Path dir) throws Exception {
        // A queue that does not exist makes every attempt fail.
        handlerSendingTo(queueUrl + "-missing").handleRequest(TestEvents.load("confirm-forgot-password"), null);
        String logged = output.toString(StandardCharsets.UTF_8);
        JsonNode line = TestEvents.lines(logged).getFirst();
        assertThat(line.path("msg").asText()).isEqualTo("AUDIT_SEND_FAILED");
        assertThat(received()).isEmpty();

        Path file = dir.resolve("failed.json");
        Files.writeString(file, TestEvents.JSON.writeValueAsString(Map.of("events", List.of(Map.of("message", logged)))));
        int code = Replay.run(new String[] {"--queue-url", queueUrl, file.toString()},
                url -> body -> sqs.sendMessage(r -> r.queueUrl(url).messageBody(body)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        assertThat(received()).singleElement().satisfies(message -> {
            assertThat(message).isEqualTo(line.path("audit_event"));
            assertThat(message.path("action").asText()).isEqualTo("PASSWORD_RESET");
        });
    }
}
