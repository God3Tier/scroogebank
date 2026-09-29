package bank.alert.operations;

import java.time.Instant;

public record ControlAuditEvent(OutboundCapability capability, String action,
        String actorSubject, String correlationId, Instant occurredAt) { }
