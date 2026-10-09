package bank.dashboard.exception;

import bank.dashboard.dto.AgentSortMetric;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Error bodies match the shape user-service returns, so a client sees one
 * error format across the CRM.
 *
 * <p>Note what is deliberately absent: there is no handler turning an upstream
 * failure into a 5xx. An unreachable upstream degrades the panel that needed
 * it and the request still succeeds -- see PanelAssembler.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidWindowException.class)
    public ResponseEntity<Object> handleInvalidWindow(InvalidWindowException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // An unparseable parameter, including a sort metric outside the
        // contract enum. Handled here so it returns the same error body as
        // every other 400 rather than Spring Boot's default, which carries a
        // different set of fields.
        String detail = AgentSortMetric.class.equals(ex.getRequiredType())
                ? "'sort' must be one of " + AgentSortMetric.accepted()
                : "'" + ex.getName() + "' is not a valid value";
        return body(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        // Raised by the bean-validation constraints on query parameters, such
        // as a staleDays outside the range the contract fixes.
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> lastNode(violation.getPropertyPath().toString()) + " " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied() {
        // Raised by @PreAuthorize, e.g. an agent calling the admin-only
        // per-agent performance endpoint. The message is deliberately generic:
        // it should not describe the authorisation rule it just enforced.
        return body(HttpStatus.FORBIDDEN, "Not permitted");
    }

    private String lastNode(String propertyPath) {
        int lastDot = propertyPath.lastIndexOf('.');
        return lastDot < 0 ? propertyPath : propertyPath.substring(lastDot + 1);
    }

    private ResponseEntity<Object> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        ));
    }
}
