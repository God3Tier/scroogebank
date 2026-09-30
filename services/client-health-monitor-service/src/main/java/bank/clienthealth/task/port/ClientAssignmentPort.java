package bank.clienthealth.task.port;

import bank.clienthealth.task.domain.RelationshipTask.AssignmentSnapshot;

public interface ClientAssignmentPort {
    AssignmentSnapshot resolve(String clientId);

    class AssignmentUnavailableException extends RuntimeException {
        public AssignmentUnavailableException(String message) { super(message); }
    }

    class AssignmentRejectedException extends RuntimeException {
        public AssignmentRejectedException(String message) { super(message); }
    }
}
