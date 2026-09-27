package bank.alert.api;

import bank.alert.service.AlertMutationConflictException;
import bank.alert.service.AlertNotFoundException;
import bank.alert.service.AuditUnavailableException;
import bank.alert.service.InvalidAlertTransitionException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.UUID;

@RestControllerAdvice
public class AlertApiExceptionHandler {

    @ExceptionHandler(AlertNotFoundException.class)
    ProblemDetail notFound(AlertNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Alert not found", exception.getMessage());
    }

    @ExceptionHandler({AlertMutationConflictException.class, InvalidAlertTransitionException.class})
    ProblemDetail conflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "Alert mutation conflict", exception.getMessage());
    }

    @ExceptionHandler(AuditUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(AuditUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(problem(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Audit persistence unavailable",
                        exception.getMessage()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    ProblemDetail badRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "request validation failed");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:scrooge:event-alerts:" + status.value()));
        problem.setTitle(title);
        problem.setProperty("correlationId", UUID.randomUUID());
        return problem;
    }
}
