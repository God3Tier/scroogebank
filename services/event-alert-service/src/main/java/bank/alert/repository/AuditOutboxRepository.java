package bank.alert.repository;

import bank.alert.domain.AuditOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Collection;

public interface AuditOutboxRepository extends JpaRepository<AuditOutbox, UUID> {

    long countByStatusIn(Collection<String> statuses);
}
