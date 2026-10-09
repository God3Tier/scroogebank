package bank.user.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class UserAuditInterceptor implements HandlerInterceptor {

    private final AuditEventPublisher publisher;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        Operation operation = operationFor(request.getMethod(), request.getRequestURI());
        if (operation == null) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorId = authentication instanceof JwtAuthenticationToken jwtAuthentication
                ? jwtAuthentication.getToken().getSubject()
                : null;

        Object role = request.getAttribute("audit.user_role");
        publisher.publish(operation.action(), actorId, operation.resourceType(), resourceId(request),
                endpointFor(request), request.getMethod(), statusFor(response, exception),
                role == null ? null : role.toString());
    }

    /**
     * An exception no handler resolved, such as the AccessDeniedException from
     * a failed @PreAuthorize check, becomes an error response only after this
     * interceptor runs, so the response still says 200 here.
     */
    private static int statusFor(HttpServletResponse response, Exception exception) {
        if (exception == null || response.getStatus() >= 400) {
            return response.getStatus();
        }
        return exception instanceof AccessDeniedException ? 403 : 500;
    }

    private record Operation(String action, String resourceType) {
    }

    private static Operation operationFor(String method, String uri) {
        if ("/api/users".equals(uri) && "POST".equals(method)) return new Operation("CREATE", "USER");
        if (uri.matches("/api/users/[^/]+") && "GET".equals(method)) return new Operation("READ", "USER");
        if (uri.matches("/api/users/[^/]+") && "PUT".equals(method)) return new Operation("UPDATE", "USER");
        if (uri.matches("/api/users/[^/]+/role") && "PATCH".equals(method)) return new Operation("ROLE_CHANGE", "USER");
        if (uri.matches("/api/users/[^/]+/disable") && "PATCH".equals(method)) return new Operation("DISABLE", "USER");
        if (uri.matches("/api/users/[^/]+/reset-password") && "POST".equals(method)) return new Operation("PASSWORD_RESET", "USER");
        if ("/api/users/me/logout".equals(uri) && "POST".equals(method)) return new Operation("LOGOUT", "USER");
        if ("/api/corrupted-logs".equals(uri) && "GET".equals(method)) return new Operation("READ", "CORRUPTED_AUDIT_LOG");
        if ("/api/corrupted-logs".equals(uri) && "POST".equals(method)) return new Operation("CREATE", "CORRUPTED_AUDIT_LOG");
        if (uri.matches("/api/corrupted-logs/[^/]+") && "DELETE".equals(method)) return new Operation("DELETE", "CORRUPTED_AUDIT_LOG");
        if (uri.matches("/api/accounts/[^/]+/transactions") && "GET".equals(method)) return new Operation("READ", "ACCOUNT");
        return null;
    }

    private static String resourceId(HttpServletRequest request) {
        Object responseResourceId = request.getAttribute("audit.resource_id");
        if (responseResourceId != null) {
            return responseResourceId.toString();
        }
        Object attributes = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attributes instanceof Map<?, ?> variables) {
            Object id = variables.get("id");
            if (id == null) id = variables.get("accountId");
            return id == null ? null : id.toString();
        }
        return null;
    }

    private static String endpointFor(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? request.getRequestURI() : pattern.toString();
    }
}
