package bank.alert.repository;

import bank.alert.domain.AuditOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Collection;

public interface AuditOutboxRepository extends JpaRepository<AuditOutbox, UUID> {

    long countByStatusIn(Collection<String> statuses);

    @Query(value = """
            SELECT * FROM audit_outbox
             WHERE attempt_count < :maxAttempts
               AND (status IN ('PENDING', 'FAILED')
                    OR (status = 'LEASED' AND lease_until < :now))
             ORDER BY created_at, event_id
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AuditOutbox> findClaimable(@Param("now") Instant now,
            @Param("batchSize") int batchSize, @Param("maxAttempts") int maxAttempts);
}
