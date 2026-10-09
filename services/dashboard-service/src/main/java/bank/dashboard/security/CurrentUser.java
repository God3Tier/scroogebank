package bank.dashboard.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The authenticated caller, read off the validated JWT.
 *
 * <p>{@link #tokenValue()} exposes the raw token so this service can
 * re-present the caller's own credential when it asks an upstream service for
 * figures. That is what keeps the ownership rule in exactly one place: the
 * dashboard holds no service credential and can therefore read nothing the
 * caller could not already read (ADR-0033).
 *
 * <p>{@link #subject()} is also the audit {@code actor_id} (ADR-0033) and part
 * of every cache key (ADR-0033) -- the latter being what stops one caller's
 * scoped figures reaching another.
 */
@Component
public class CurrentUser {

    public String subject() {
        return jwt().getSubject();
    }

    public String tokenValue() {
        return jwt().getTokenValue();
    }

    public boolean isAdmin() {
        return authentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    private Jwt jwt() {
        return authentication().getToken();
    }

    private JwtAuthenticationToken authentication() {
        return (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
    }
}
