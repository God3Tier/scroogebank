package bank.user.identity;

import bank.user.domain.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDisableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRemoveUserFromGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminResetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.util.List;

/**
 * AWS Cognito User Pool. Group membership ("Admin" / "Agent") supplies
 * cross-service authorization; the Root Admin distinction exists only in
 * user-service. MFA and password storage are handled by Cognito.
 */
@Component
public class CognitoIdentityProviderClient implements IdentityProviderClient {

    private final software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient cognito;
    private final String userPoolId;

    public CognitoIdentityProviderClient(
            @Value("${idp.cognito.user-pool-id}") String userPoolId,
            @Value("${idp.cognito.region}") String region
    ) {
        this.userPoolId = userPoolId;
        this.cognito = software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient.builder()
                .region(software.amazon.awssdk.regions.Region.of(region))
                .build();
    }

    @Override
    public String createUser(String email, String firstName, String lastName, Role role) {
        try {
            AdminCreateUserResponse response = cognito.adminCreateUser(AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .userAttributes(
                            AttributeType.builder().name("email").value(email).build(),
                            AttributeType.builder().name("email_verified").value("true").build(),
                            AttributeType.builder().name("given_name").value(firstName).build(),
                            AttributeType.builder().name("family_name").value(lastName).build()
                    )
                    .desiredDeliveryMediums(DeliveryMediumType.EMAIL)
                    .build());
            return subOf(response.user().attributes(), response.user().username());
        } catch (UsernameExistsException e) {
            // The pool already holds this username. Adopt that user rather than
            // failing, because both ways of reaching this state want the same
            // outcome and neither is an error:
            //
            //  - Two replicas ran the same bootstrap concurrently
            //    (the service can run several tasks) and the other one won
            //    this call.
            //  - An earlier attempt died between this call and the local row
            //    being committed, leaving the user in the pool but not in the
            //    database. That state does not recover on its own: every
            //    restart re-runs this and re-throws, which is exactly the
            //    crash loop that surfaced the addToGroup bug.
            //
            // Returning the existing subject id makes this method "ensure the
            // IdP user exists" rather than "create it once", which is what
            // every caller actually wants.
            AdminGetUserResponse existing = cognito.adminGetUser(AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .build());
            return subOf(existing.userAttributes(), existing.username());
        }
    }

    /**
     * Pulls the immutable "sub" out of a user's attributes, falling back to the
     * username when the pool does not return it. AdminCreateUser nests the
     * attributes under user(), AdminGetUser returns them at the top level, so
     * both shapes funnel through here.
     */
    private static String subOf(List<AttributeType> attributes, String fallbackUsername) {
        return attributes.stream()
                .filter(a -> "sub".equals(a.name()))
                .findFirst()
                .map(AttributeType::value)
                .orElse(fallbackUsername);
    }

    @Override
    public void addToGroup(String username, Role role) {
        cognito.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(userPoolId)
                .username(username)
                .groupName(role.name())
                .build());
    }

    @Override
    public void removeFromGroup(String username, Role role) {
        cognito.adminRemoveUserFromGroup(AdminRemoveUserFromGroupRequest.builder()
                .userPoolId(userPoolId)
                .username(username)
                .groupName(role.name())
                .build());
    }

    @Override
    public void disableUser(String idpSub) {
        cognito.adminDisableUser(AdminDisableUserRequest.builder()
                .userPoolId(userPoolId)
                .username(idpSub)
                .build());
    }

    @Override
    public void updateUser(String idpSub, String firstName, String lastName) {
        cognito.adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder()
                .userPoolId(userPoolId)
                .username(idpSub)
                .userAttributes(
                        AttributeType.builder().name("given_name").value(firstName).build(),
                        AttributeType.builder().name("family_name").value(lastName).build()
                )
                .build());
    }

    @Override
    public void resetPassword(String idpSub) {
        cognito.adminResetUserPassword(AdminResetUserPasswordRequest.builder()
                .userPoolId(userPoolId)
                .username(idpSub)
                .build());
    }
}
