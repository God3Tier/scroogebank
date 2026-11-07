package bank.engagement.integration.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Looks agents up with Cognito {@code ListUsers} filtered by {@code sub},
 * asking only for the address and name attributes (ADR-0053 decision 4).
 * Found agents are cached; a failed lookup is not, so the next try asks again.
 * Cognito refuses the whole call with {@code InvalidParameterException} if the
 * user lacks a requested attribute, so only the three user-service always sets
 * are requested.
 */
@Slf4j
public class CognitoAgentDirectory implements AgentDirectory {

    /** A sub is a UUID; anything else would be unsafe inside the filter string. */
    private static final Pattern SUB = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private static final List<String> ATTRIBUTES = List.of("email", "given_name", "family_name");

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;
    private final Cache<String, AgentProfile> cache;

    public CognitoAgentDirectory(CognitoIdentityProviderClient cognito, IdentityProperties properties) {
        this.cognito = cognito;
        this.userPoolId = properties.userPoolId();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(1_000)
                .build();
    }

    @Override
    public Optional<AgentProfile> find(String sub) {
        if (sub == null || !SUB.matcher(sub).matches()) {
            return Optional.empty();
        }
        AgentProfile cached = cache.getIfPresent(sub);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            ListUsersResponse response = cognito.listUsers(request -> request
                    .userPoolId(userPoolId)
                    .filter("sub = \"" + sub + "\"")
                    .attributesToGet(ATTRIBUTES)
                    .limit(1));
            Optional<AgentProfile> found = response.users().stream().findFirst().map(user -> profile(sub, user));
            found.ifPresentOrElse(profile -> cache.put(sub, profile),
                    () -> log.warn("No agent found for sub {}", sub));
            return found;
        } catch (SdkException ex) {
            log.warn("Agent lookup failed for sub {}: {}", sub, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static AgentProfile profile(String sub, UserType user) {
        Map<String, String> attributes = user.attributes().stream()
                .filter(attribute -> attribute.value() != null)
                .collect(Collectors.toMap(AttributeType::name, AttributeType::value, (first, second) -> first));
        return new AgentProfile(sub, attributes.get("email"), displayName(attributes),
                Boolean.TRUE.equals(user.enabled()));
    }

    /** Given and family name, as user-service stores them. */
    static String displayName(Map<String, String> attributes) {
        String joined = (attributes.getOrDefault("given_name", "") + " " + attributes.getOrDefault("family_name", ""))
                .strip();
        return joined.isEmpty() ? null : joined;
    }
}
