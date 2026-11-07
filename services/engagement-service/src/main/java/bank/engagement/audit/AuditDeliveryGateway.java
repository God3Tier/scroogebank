package bank.engagement.audit;

public interface AuditDeliveryGateway {

    void publish(String eventId, AuditorEnvelope envelope);
}
