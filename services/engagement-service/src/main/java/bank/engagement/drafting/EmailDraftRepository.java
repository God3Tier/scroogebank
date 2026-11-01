package bank.engagement.drafting;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EmailDraftRepository extends JpaRepository<EmailDraft, UUID> {

    Optional<EmailDraft> findByIdAndOwnerAgentSub(UUID id, String ownerAgentSub);

    List<EmailDraft> findByOwnerAgentSubAndStatusInOrderByCreatedAtDesc(String owner,
            Collection<DraftStatus> statuses, Pageable page);

    List<EmailDraft> findByOwnerAgentSubAndClientIdAndStatusInOrderByCreatedAtDesc(String owner, UUID clientId,
            Collection<DraftStatus> statuses, Pageable page);

    /**
     * Holds the draft for one send. Only a {@code DRAFT} at the version the
     * agent saw can be claimed, so a second send finds nothing to claim. A
     * claim older than {@code staleBefore} belonged to a task that died.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE email_drafts SET status = 'SENDING', updated_at = :now
             WHERE id = :id AND owner_agent_sub = :owner AND version = :version
               AND (status = 'DRAFT' OR (status = 'SENDING' AND updated_at < :staleBefore))
            """, nativeQuery = true)
    int claimForSend(@Param("id") UUID id, @Param("owner") String owner, @Param("version") int version,
            @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    /**
     * Open drafts not changed since {@code before}, locked for the expiry job
     * (ADR-0068 decision 4). {@code SKIP LOCKED} lets every task run the job:
     * a row another task holds is left to it. A draft being sent is not open.
     */
    @Query(value = """
            SELECT * FROM email_drafts
             WHERE status = 'DRAFT' AND updated_at < :before
             ORDER BY updated_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<EmailDraft> lockExpired(@Param("before") Instant before, @Param("limit") int limit);

    /** Gives a draft back after a failed send, so the agent can try again. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE email_drafts SET status = 'DRAFT' WHERE id = :id AND status = 'SENDING'",
            nativeQuery = true)
    int release(@Param("id") UUID id);
}
