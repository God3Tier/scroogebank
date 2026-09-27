package bank.alert.repository;

import bank.alert.domain.AlertDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AlertDecisionRepository extends JpaRepository<AlertDecision, UUID> {

    Optional<AlertDecision> findByActorSubjectAndIdempotencyKey(String actorSubject, String idempotencyKey);

    Optional<AlertDecision> findFirstByAlertIdOrderByDecidedAtDesc(UUID alertId);
}
