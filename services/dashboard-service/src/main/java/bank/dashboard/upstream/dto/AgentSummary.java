package bank.dashboard.upstream.dto;

/**
 * One CRM user from user-service's admin-only {@code GET /api/users}.
 *
 * @param idpSub the user's Cognito {@code sub}. Client and account figures key
 *               agents by it, so it is what joins a figure to a name.
 */
public record AgentSummary(
        String id,
        String idpSub,
        String firstName,
        String lastName,
        String email,
        String role,
        boolean enabled
) {

    public String displayName() {
        return firstName + " " + lastName;
    }
}
