package bank.cognito;

import java.time.Duration;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/** Reads a user's MFA settings with one AdminGetUser call, inside the trigger's time limit. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class CognitoMfaLookup implements MfaLookup {

    /** Cognito waits 5 seconds for the trigger, cold start included. */
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final CognitoIdentityProviderClient client;

    /** The region and the role's keys, which Lambda sets, so a cold start searches no default chain. */
    static CognitoMfaLookup fromEnvironment(UnaryOperator<String> env) {
        String region = env.apply("AWS_REGION");
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("AWS_REGION is not set");
        }
        return new CognitoMfaLookup(CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(CONNECT_TIMEOUT)
                        .socketTimeout(READ_TIMEOUT)
                        .build())
                .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()))
                .build());
    }

    @Override
    public boolean hasSoftwareToken(String userPoolId, String userName) {
        return client.adminGetUser(request -> request.userPoolId(userPoolId).username(userName))
                .userMFASettingList()
                .contains("SOFTWARE_TOKEN_MFA");
    }
}
