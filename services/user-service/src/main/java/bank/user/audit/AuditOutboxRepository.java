package bank.user.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AuditOutboxRepository extends JpaRepository<AuditOutboxEntry, UUID> {

    /**
     * Locks the oldest unsent entries for the rest of the transaction. SKIP
     * LOCKED lets each replica take a different batch instead of waiting for,
     * or double-sending, the rows another replica is working on.
     */
    @Query(value = "SELECT * FROM audit_outbox ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<AuditOutboxEntry> lockOldest(@Param("limit") int limit);
}
