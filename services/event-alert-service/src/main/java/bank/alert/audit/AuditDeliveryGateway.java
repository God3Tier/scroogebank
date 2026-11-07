package bank.alert.audit;

public interface AuditDeliveryGateway {

    void publish(String eventId, AuditorEnvelope envelope);
}
