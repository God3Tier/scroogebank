package bank.cognito;

import java.net.URI;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/** Sends to the audit queue with the SDK's retries off, since SendBudget does the retrying. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class SqsQueueSender implements QueueSender {

    private final SqsClient client;
    private final String queueUrl;

    /**
     * As configured on Lambda: AUDIT_QUEUE_URL, plus the region and the role's keys, which Lambda
     * sets. AWS_ENDPOINT_URL is for local runs only.
     */
    static SqsQueueSender fromEnvironment(UnaryOperator<String> env) {
        String endpoint = env.apply("AWS_ENDPOINT_URL");
        return create(required(env, "AUDIT_QUEUE_URL"), Region.of(required(env, "AWS_REGION")),
                EnvironmentVariableCredentialsProvider.create(),
                endpoint == null || endpoint.isBlank() ? null : URI.create(endpoint));
    }

    /** A fixed region and credentials provider, so a cold start does not search the SDK's default chains. */
    static SqsQueueSender create(String queueUrl, Region region, AwsCredentialsProvider credentials, URI endpoint) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(region)
                .credentialsProvider(credentials)
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(SendBudget.CONNECT_TIMEOUT)
                        .socketTimeout(SendBudget.READ_TIMEOUT)
                        .build())
                .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()));
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return new SqsQueueSender(builder.build(), queueUrl);
    }

    @Override
    public void send(String body) {
        client.sendMessage(request -> request.queueUrl(queueUrl).messageBody(body));
    }

    private static String required(UnaryOperator<String> env, String name) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
