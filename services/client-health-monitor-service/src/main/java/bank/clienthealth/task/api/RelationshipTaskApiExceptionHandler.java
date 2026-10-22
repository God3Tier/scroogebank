package bank.clienthealth.task.api;

import bank.clienthealth.task.application.RelationshipTaskService.TaskNotFoundException;
import bank.clienthealth.task.domain.RelationshipTask.InvalidTransitionException;
import bank.clienthealth.task.domain.RelationshipTask.OptimisticLockException;
import bank.clienthealth.task.domain.RelationshipTask.TaskAccessException;
import bank.clienthealth.task.port.RelationshipTaskQueryPort.InvalidCursorException;
import bank.clienthealth.task.port.DispositionPolicy.InvalidDispositionException;
import bank.clienthealth.task.port.TaskRepository.ConflictException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class RelationshipTaskApiExceptionHandler {
    @ExceptionHandler(TaskNotFoundException.class)
    ProblemDetail notFound(TaskNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Relationship task not found", exception.getMessage());
    }

    @ExceptionHandler(TaskAccessException.class)
    ProblemDetail hidden(TaskAccessException exception) {
        return problem(HttpStatus.NOT_FOUND, "Relationship task not found", "Relationship task not found");
    }

    @ExceptionHandler({InvalidTransitionException.class, OptimisticLockException.class, ConflictException.class})
    ProblemDetail conflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "Relationship task conflict", exception.getMessage());
    }

    @ExceptionHandler(InvalidDispositionException.class)
    ProblemDetail unprocessable(InvalidDispositionException exception) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid disposition", exception.getMessage());
    }

    @ExceptionHandler(bank.clienthealth.task.port.ClientAssignmentPort.AssignmentUnavailableException.class)
    ResponseEntity<ProblemDetail> assignmentUnavailable(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE,
                        "Assignment dependency unavailable", exception.getMessage()));
    }

    @ExceptionHandler({
            InvalidCursorException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ProblemDetail badRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "request validation failed");
    }

    static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:scrooge:client-health:" + status.value()));
        problem.setTitle(title);
        problem.setProperty("correlationId", UUID.randomUUID());
        return problem;
    }
}
