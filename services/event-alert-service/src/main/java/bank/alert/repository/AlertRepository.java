package bank.alert.repository;

import bank.alert.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    Optional<Alert> findByIdAndAssignedAgentSubject(UUID id, String assignedAgentSubject);

    /** The alert, if the actor is its client's current agent (not merely the agent it was created for). */
    @Query(value = """
            SELECT a.* FROM alert a
              LEFT JOIN client_assignment ca ON ca.client_ref = a.client_ref
             WHERE a.id = :id
               AND (CASE WHEN ca.client_ref IS NULL THEN a.assigned_agent_subject
                         ELSE ca.agent_subject END) = :agent
            """, nativeQuery = true)
    Optional<Alert> findOwnedByCurrentAgent(@Param("id") UUID id, @Param("agent") String agent);

    List<Alert> findAllByAssignedAgentSubjectOrderByCreatedAtDesc(String assignedAgentSubject);

    Optional<Alert> findByClientRefAndPublicEventIdAndInstrumentId(
            String clientRef, UUID publicEventId, String instrumentId);

    long countByAssignedAgentSubjectAndCreatedAtGreaterThanEqual(
            String assignedAgentSubject, Instant createdAt);
}
