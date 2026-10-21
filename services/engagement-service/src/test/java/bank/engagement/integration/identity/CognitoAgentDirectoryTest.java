package bank.engagement.integration.identity;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InternalErrorException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CognitoAgentDirectoryTest {

    private static final String SUB = "11111111-1111-4111-8111-111111111111";

    private final CognitoIdentityProviderClient cognito = mock(CognitoIdentityProviderClient.class);
    private final CognitoAgentDirectory directory = new CognitoAgentDirectory(cognito,
            new IdentityProperties("ap-southeast-1_test", null, null, Duration.ofMinutes(15)));

    @Test
    @SuppressWarnings("unchecked")
    void findsAnAgentBySubAndCachesIt() {
        when(cognito.listUsers(any(Consumer.class))).thenAnswer(invocation -> {
            ListUsersRequest.Builder builder = ListUsersRequest.builder();
            ((Consumer<ListUsersRequest.Builder>) invocation.getArgument(0)).accept(builder);
            ListUsersRequest request = builder.build();
            assertThat(request.filter()).isEqualTo("sub = \"" + SUB + "\"");
            assertThat(request.attributesToGet()).containsExactly("email", "name", "given_name", "family_name");
            return ListUsersResponse.builder().users(UserType.builder().enabled(true).attributes(
                    AttributeType.builder().name("email").value("synthetic.agent@example.test").build(),
                    AttributeType.builder().name("given_name").value("Synthetic").build(),
                    AttributeType.builder().name("family_name").value("Agent").build()).build()).build();
        });

        AgentProfile profile = directory.find(SUB).orElseThrow();
        directory.find(SUB);

        assertThat(profile.email()).isEqualTo("synthetic.agent@example.test");
        assertThat(profile.displayName()).isEqualTo("Synthetic Agent");
        assertThat(profile.enabled()).isTrue();
        verify(cognito, times(1)).listUsers(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedLookupIsEmptyAndNotCached() {
        when(cognito.listUsers(any(Consumer.class))).thenThrow(InternalErrorException.builder().build());

        assertThat(directory.find(SUB)).isEmpty();
        assertThat(directory.find(SUB)).isEmpty();
        verify(cognito, times(2)).listUsers(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSubThatIsNotAUuidIsNeverPutInTheFilter() {
        assertThat(directory.find("x\" or email ^= \"")).isEmpty();
        verify(cognito, never()).listUsers(any(Consumer.class));
    }

    @Test
    void displayNamePrefersNameThenGivenAndFamilyName() {
        assertThat(CognitoAgentDirectory.displayName(Map.of("name", "Full Name", "given_name", "G")))
                .isEqualTo("Full Name");
        assertThat(CognitoAgentDirectory.displayName(Map.of("given_name", "Given"))).isEqualTo("Given");
        assertThat(CognitoAgentDirectory.displayName(Map.of())).isNull();
    }
}
