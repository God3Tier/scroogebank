package bank.user.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** An audit event that has been saved but not yet sent to SQS. */
@Entity
@Table(name = "audit_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditOutboxEntry {

    @Id
    private UUID eventId;

    /** The serialized AuditEvent, sent to SQS unchanged. */
    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private Instant createdAt;

    public AuditOutboxEntry(UUID eventId, String body, Instant createdAt) {
        this.eventId = eventId;
        this.body = body;
        this.createdAt = createdAt;
    }
}
