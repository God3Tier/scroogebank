package bank.engagement.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The authenticated agent. {@link #subject()} owns every event, draft and
 * preference; no route takes an agent ID. {@link #tokenValue()} is passed on
 * to client-service, so this service can read no client the agent could not
 * (ADR-0053 decision 5).
 */
@Component
public class CurrentUser {

    public String subject() {
        return authentication().getToken().getSubject();
    }

    public String tokenValue() {
        return authentication().getToken().getTokenValue();
    }

    private static JwtAuthenticationToken authentication() {
        return (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
    }
}
