package bank.alert.repository;

import bank.alert.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    Optional<Alert> findByIdAndAssignedAgentSubject(UUID id, String assignedAgentSubject);

    List<Alert> findAllByAssignedAgentSubjectOrderByCreatedAtDesc(String assignedAgentSubject);
}
