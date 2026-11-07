package bank.cognito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenScopeHandlerTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final List<String> lookedUp = new ArrayList<>();

    private TokenScopeHandler handler(MfaLookup lookup) {
        MfaLookup recording = (pool, user) -> {
            lookedUp.add(pool + "/" + user);
            return lookup.hasSoftwareToken(pool, user);
        };
        return new TokenScopeHandler(() -> recording, new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> event(String... groups) {
        Map<String, Object> event = TestEvents.load("token-generation");
        groupConfiguration(event).put("groupsToOverride", List.of(groups));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> groupConfiguration(Map<String, Object> event) {
        return (Map<String, Object>) ((Map<String, Object>) event.get("request")).get("groupConfiguration");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> result) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("response")).get("claimsAndScopeOverrideDetails");
    }

    @SuppressWarnings("unchecked")
    private static List<String> scopesAdded(Map<String, Object> result) {
        return (List<String>) ((Map<String, Object>) details(result).get("accessTokenGeneration")).get("scopesToAdd");
    }

    @SuppressWarnings("unchecked")
    private static List<String> groups(Map<String, Object> result) {
        return (List<String>) ((Map<String, Object>) details(result).get("groupOverrideDetails")).get("groupsToOverride");
    }

    private List<JsonNode> logs() {
        return TestEvents.lines(output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aUserWithTotpEnrolledGetsTheApiScope() {
        Map<String, Object> result = handler((pool, user) -> true).handleRequest(event("AGENT"), null);

        assertThat(scopesAdded(result)).containsExactly("crm/api");
        assertThat(groups(result)).containsExactly("AGENT");
        assertThat(lookedUp).containsExactly("ap-southeast-1_Example01/4a1b2c3d-0000-4000-8000-000000000001");
    }

    @Test
    void aUserWithoutTotpSignsInButGetsNoApiScope() {
        Map<String, Object> result = handler((pool, user) -> false).handleRequest(event("ADMIN"), null);

        assertThat(scopesAdded(result)).isEmpty();
        assertThat(groups(result)).containsExactly("ADMIN");
        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line.path("msg").asText()).isEqualTo("TOKEN_SCOPE");
            assertThat(line.path("api_access").asBoolean()).isFalse();
        });
    }

    @Test
    void anExemptUserGetsTheScopeWithoutALookupAndTheGroupStaysOutOfTheTokens() {
        Map<String, Object> result = handler((pool, user) -> {
            throw new AssertionError("an exempt user needs no lookup");
        }).handleRequest(event("ADMIN", "MFA_EXEMPT"), null);

        assertThat(scopesAdded(result)).containsExactly("crm/api");
        assertThat(groups(result)).containsExactly("ADMIN");
        assertThat(lookedUp).isEmpty();
        assertThat(logs()).singleElement().satisfies(line -> assertThat(line.path("mfa_exempt").asBoolean()).isTrue());
    }

    @Test
    void theOtherGroupSettingsAreCopiedAcross() {
        Map<String, Object> event = event("AGENT");
        groupConfiguration(event).put("iamRolesToOverride", List.of("arn:aws:iam::123456789012:role/example"));

        Map<String, Object> result = handler((pool, user) -> true).handleRequest(event, null);

        assertThat(((Map<?, ?>) details(result).get("groupOverrideDetails")).get("iamRolesToOverride"))
                .isEqualTo(List.of("arn:aws:iam::123456789012:role/example"));
    }

    @Test
    void aFailedLookupFailsTheSignInAndIsLogged() {
        TokenScopeHandler handler = handler((pool, user) -> {
            throw new IllegalStateException("Cognito unreachable");
        });

        assertThatThrownBy(() -> handler.handleRequest(event("AGENT"), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line.path("level").asText()).isEqualTo("ERROR");
            assertThat(line.path("msg").asText()).isEqualTo("MFA_LOOKUP_FAILED");
            assertThat(line.path("error").asText()).contains("Cognito unreachable");
        });
    }

    @Test
    void noUserAttributeReachesTheLog() {
        handler((pool, user) -> true).handleRequest(event("AGENT"), null);

        String log = output.toString(StandardCharsets.UTF_8);
        TestEvents.PERSONAL_DATA.forEach(value -> assertThat(log).doesNotContain(value));
    }
}
