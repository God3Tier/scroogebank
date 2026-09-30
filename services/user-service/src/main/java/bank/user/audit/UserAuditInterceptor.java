package bank.user.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
        String action = actionFor(request.getMethod(), request.getRequestURI());
        if (action == null) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorId = authentication instanceof JwtAuthenticationToken jwtAuthentication
                ? jwtAuthentication.getToken().getSubject()
                : null;

        Object role = request.getAttribute("audit.user_role");
        publisher.publish(action, actorId, resourceId(request), endpointFor(request),
                request.getMethod(), response.getStatus(), role == null ? null : role.toString());
    }

    private static String actionFor(String method, String uri) {
        if ("/api/users".equals(uri) && "POST".equals(method)) return "CREATE";
        if (uri.matches("/api/users/[^/]+") && "GET".equals(method)) return "READ";
        if (uri.matches("/api/users/[^/]+") && "PUT".equals(method)) return "UPDATE";
        if (uri.matches("/api/users/[^/]+/disable") && "PATCH".equals(method)) return "DISABLE";
        if (uri.matches("/api/users/[^/]+/reset-password") && "POST".equals(method)) return "PASSWORD_RESET";
        if ("/api/corrupted-logs".equals(uri) && "GET".equals(method)) return "READ";
        if ("/api/corrupted-logs".equals(uri) && "POST".equals(method)) return "CREATE";
        if (uri.matches("/api/corrupted-logs/[^/]+") && "DELETE".equals(method)) return "DELETE";
        if (uri.matches("/api/accounts/[^/]+/transactions") && "GET".equals(method)) return "READ";
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
