package bank.engagement.notifications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MailQueueRepository extends JpaRepository<MailQueueEntry, UUID> {

    /**
     * Due rows no other task holds (ADR-0051 decision 4). SKIP LOCKED passes
     * over rows another transaction is claiming, and the lease keeps them
     * claimed after it commits, so two tasks never send the same row at once.
     */
    @Query(value = """
            SELECT * FROM mail_queue
             WHERE status = 'PENDING'
               AND due_at <= :now
               AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
               AND (leased_until IS NULL OR leased_until < :now)
             ORDER BY due_at, id
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MailQueueEntry> findClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE MailQueueEntry m SET m.status = bank.engagement.notifications.MailStatus.CANCELLED,
                   m.leasedUntil = null, m.updatedAt = :now
             WHERE m.eventId = :eventId AND m.kind IN :kinds
               AND m.status = bank.engagement.notifications.MailStatus.PENDING
            """)
    int cancelPending(@Param("eventId") UUID eventId, @Param("kinds") Collection<MailKind> kinds,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE MailQueueEntry m SET m.status = bank.engagement.notifications.MailStatus.CANCELLED,
                   m.leasedUntil = null, m.updatedAt = :now
             WHERE m.ownerAgentSub = :owner AND m.kind = bank.engagement.notifications.MailKind.REMINDER
               AND m.status = bank.engagement.notifications.MailStatus.PENDING
            """)
    int cancelPendingReminders(@Param("owner") String owner, @Param("now") Instant now);

    List<MailQueueEntry> findByEventIdIn(Collection<UUID> eventIds);

    /** The oldest due row still waiting, for the dispatcher-lag alarm. */
    @Query("""
            SELECT min(m.dueAt) FROM MailQueueEntry m
             WHERE m.status = bank.engagement.notifications.MailStatus.PENDING AND m.dueAt <= :now
            """)
    Optional<Instant> findOldestDue(@Param("now") Instant now);
}
