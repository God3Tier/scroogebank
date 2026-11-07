package bank.clienthealth.audit;

@FunctionalInterface
public interface AuditDeliveryGateway {
    void send(RelationshipHealthAuditEvent event);

    final class DeliveryException extends RuntimeException {
        private final AuditOutboxRepository.FailureCategory category;

        public DeliveryException(
                AuditOutboxRepository.FailureCategory category,
                String message
        ) {
            super(message);
            this.category = category;
        }

        public AuditOutboxRepository.FailureCategory category() {
            return category;
        }
    }
}
