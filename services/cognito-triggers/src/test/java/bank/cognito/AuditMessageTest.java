package bank.cognito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AuditMessageTest {

    private static final Instant NOW = Instant.parse("2026-10-13T05:54:00.123456Z");

    private static AuditMessage build(Map<String, Object> event) {
        return AuditMessage.from(event, NOW, "evt-1").orElseThrow();
    }

    @Test
    void aLoginBecomesTheMessageTheAuditorChecks() throws Exception {
        String json = TestEvents.JSON.writeValueAsString(build(TestEvents.load("post-authentication")));

        // The same text as cognitoLogin in the auditor's payload_unmarshalling_test.go.
        assertThat(json).isEqualTo("{\"event_id\":\"evt-1\",\"event_type\":\"AUTHENTICATION\",\"action\":\"LOGIN\","
                + "\"source_service\":\"COGNITO_TRIGGERS\",\"actor_id\":\"4a1b2c3d-0000-4000-8000-000000000001\","
                + "\"payload\":{\"user_pool_id\":\"ap-southeast-1_Example01\",\"trigger_source\":\"PostAuthentication_Authentication\","
                + "\"datetime\":\"2026-10-13T05:54:00.123Z\",\"app_client_id\":\"example0app0client0id0001\"}}");
    }

    @Test
    void aForgottenPasswordResetBecomesAPasswordReset() {
        AuditMessage message = build(TestEvents.load("confirm-forgot-password"));

        assertThat(message.action()).isEqualTo("PASSWORD_RESET");
        assertThat(message.actorId()).isEqualTo("4a1b2c3d-0000-4000-8000-000000000001");
        assertThat(message.payload().triggerSource()).isEqualTo("PostConfirmation_ConfirmForgotPassword");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PostConfirmation_ConfirmSignUp", "PostAuthentication_Something", "PreSignUp_SignUp"})
    void otherTriggerSourcesAreNotAudited(String triggerSource) {
        assertThat(AuditMessage.from(Map.of("triggerSource", triggerSource), NOW, "evt-1")).isEmpty();
    }

    @Test
    void anEventWithoutATriggerSourceIsNotAudited() {
        assertThat(AuditMessage.from(Map.of(), NOW, "evt-1")).isEmpty();
    }

    @Test
    void theSampleSignUpConfirmationIsNotAudited() {
        assertThat(AuditMessage.from(TestEvents.load("confirm-sign-up"), NOW, "evt-1")).isEmpty();
    }

    @Test
    void userAttributesNeverReachTheMessage() throws Exception {
        for (String name : new String[] {"post-authentication", "confirm-forgot-password"}) {
            String json = TestEvents.JSON.writeValueAsString(build(TestEvents.load(name)));
            for (String value : TestEvents.PERSONAL_DATA) {
                assertThat(json).doesNotContain(value);
            }
        }
    }

    @Test
    void theTimeIsRecordedInUtcToTheMillisecond() {
        Instant singaporeAfternoon = OffsetDateTime.of(2026, 10, 13, 13, 54, 0, 0, ZoneOffset.ofHours(8)).toInstant();

        AuditMessage message = AuditMessage.from(TestEvents.load("post-authentication"), singaporeAfternoon, "evt-1")
                .orElseThrow();

        assertThat(message.payload().dateTime()).isEqualTo("2026-10-13T05:54:00.000Z");
    }

    @Test
    void theAppClientIsLeftOutWhenCognitoNamesNone() throws Exception {
        Map<String, Object> event = TestEvents.load("post-authentication");
        event.remove("callerContext");

        assertThat(TestEvents.JSON.writeValueAsString(build(event))).doesNotContain("app_client_id");
    }

    static Stream<Consumer<Map<String, Object>>> withoutUserOrPool() {
        return Stream.of(
                event -> TestEvents.attributes(event).remove("sub"),
                event -> TestEvents.attributes(event).put("sub", " "),
                event -> event.remove("request"),
                event -> event.remove("userPoolId"));
    }

    @ParameterizedTest
    @MethodSource("withoutUserOrPool")
    void anEventWithoutTheUserOrPoolIsRefused(Consumer<Map<String, Object>> change) {
        Map<String, Object> event = TestEvents.load("post-authentication");
        change.accept(event);

        assertThatThrownBy(() -> AuditMessage.from(event, NOW, "evt-1"))
                .isInstanceOf(AuditMessage.InvalidTriggerEventException.class);
    }
}
