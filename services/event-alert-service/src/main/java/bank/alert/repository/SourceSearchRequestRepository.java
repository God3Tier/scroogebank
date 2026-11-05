package bank.alert.repository;

import bank.alert.domain.SourceSearchRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SourceSearchRequestRepository extends JpaRepository<SourceSearchRequest, UUID> {

    /** When this company was last searched for an agent, for the cooldown. */
    Optional<SourceSearchRequest> findFirstByInstrumentIdAndStatusOrderByRequestedAtDesc(String instrumentId, String status);

    /** Company searches that were actually started since the given time (an optional daily cap). */
    long countByStatusAndRequestedAtAfter(String status, Instant since);
}
