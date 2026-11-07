package bank.user.dto;

import bank.user.domain.Role;
import bank.user.domain.User;

import java.time.Instant;
import java.util.UUID;

/**
 * A CRM user as returned by the admin-only user endpoints.
 *
 * <p>{@code idpSub} is the user's Cognito {@code sub}. Other services identify
 * agents by it rather than by {@code id}: client-profile-management-service
 * stores it as {@code assigned_agent_id}, and audit events carry it as
 * {@code actor_id}. Exposing it lets an admin view join those records back to
 * a named user. Every endpoint returning this record is admin-only; if a
 * non-admin endpoint ever needs a user, give it a narrower response instead.
 */
public record UserResponse(
        UUID id,
        String idpSub,
        String firstName,
        String lastName,
        String email,
        Role role,
        boolean enabled,
        boolean deletable,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getIdpSub(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.isDeletable(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
