package bank.user.identity;

import bank.user.domain.Role;

/**
 * Delegates credential storage, MFA and token issuance to AWS Cognito so this
 * service never handles or stores passwords itself. Kept as an interface so the
 * IdP can be swapped without touching controllers or the security config.
 */
public interface IdentityProviderClient {

    /**
     * Ensures a user with this email exists in the IdP and returns its subject
     * id. If one already exists it is adopted rather than treated as an error,
     * so a caller that died before committing its own record can retry, and
     * concurrent callers converge instead of one of them failing.
     */
    String createUser(String email, String firstName, String lastName, Role role);

    /**
     * Adds a user to a role group. Must be called with the IdP username (the
     * email, in this pool — see CognitoIdentityProviderClient.createUser),
     * never the sub: a user created by createUser() is left in an unconfirmed
     * state (Cognito: FORCE_CHANGE_PASSWORD), and admin lookups by sub only
     * resolve for confirmed users, so calling this with a freshly-created
     * user's sub fails with UserNotFoundException. The literal username
     * always resolves, confirmed or not.
     */
    void addToGroup(String username, Role role);

    /**
     * Removes a user from a role group. Same username contract as addToGroup:
     * pass the email, never the sub. Removing a user who is not in the group
     * is not an error, so a retried role change can repeat this safely.
     */
    void removeFromGroup(String username, Role role);

    void disableUser(String idpSub);

    void updateUser(String idpSub, String firstName, String lastName);

    void resetPassword(String idpSub);
}
