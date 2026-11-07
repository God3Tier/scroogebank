package bank.clienthealth.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Role policy shared by the task controllers introduced in later SF06 slices. */
@Component("taskAuthorization")
public final class TaskAuthorization {
    public boolean canRead(Authentication authentication) {
        return hasRole(authentication, "ROLE_AGENT") || hasRole(authentication, "ROLE_ADMIN");
    }

    public boolean canMutate(Authentication authentication) {
        return hasRole(authentication, "ROLE_AGENT") && !hasRole(authentication, "ROLE_ADMIN");
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> role.equals(authority.getAuthority()));
    }
}
