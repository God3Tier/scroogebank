package bank.alert.repository;

import bank.alert.domain.SourceSearchRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface SourceSearchRequestRepository extends JpaRepository<SourceSearchRequest, UUID> {

    /** Searches that were actually started for this company since the given time (the cooldown). */
    boolean existsByInstrumentIdAndStatusAndRequestedAtAfter(String instrumentId, String status, Instant since);

    /** Searches that were actually started since the given time, across all companies (the daily cap). */
    long countByStatusAndRequestedAtAfter(String status, Instant since);
}
