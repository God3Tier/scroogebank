package bank.alert.repository;

import bank.alert.domain.AlertFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AlertFeedbackRepository extends JpaRepository<AlertFeedback, UUID> {

    Optional<AlertFeedback> findByActorSubjectAndIdempotencyKey(
            String actorSubject,
            String idempotencyKey);
}
