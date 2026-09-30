package bank.alert.repository;

import bank.alert.domain.SearchRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SearchRunRepository extends JpaRepository<SearchRun, UUID> {
    Optional<SearchRun> findByWindowFromAndWindowTo(Instant windowFrom, Instant windowTo);
}
