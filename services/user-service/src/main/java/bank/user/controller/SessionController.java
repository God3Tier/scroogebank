package bank.user.controller;

import bank.user.domain.User;
import bank.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints about the caller's own session rather than a user chosen by an
 * admin, so any signed-in user may call them.
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class SessionController {

    private final UserService userService;

    /**
     * Records that the caller signed out, so the logout reaches the audit log
     * (UserAuditInterceptor publishes it as LOGOUT). Cognito has no sign-out
     * trigger (ADR-0025), so the frontend calls this first and then revokes
     * the refresh token with Cognito itself. Nothing is revoked here.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        User user = userService.getUserByIdpSub(jwt.getSubject());
        // There is no user in the URL or the response body, so name the
        // audited resource here. UserAuditInterceptor reads these attributes.
        request.setAttribute("audit.resource_id", user.getId().toString());
        request.setAttribute("audit.user_role", user.getRole().name());
        return ResponseEntity.noContent().build();
    }
}
