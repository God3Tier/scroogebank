package bank.alert.repository;

import bank.alert.domain.SourceResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SourceResultRepository extends JpaRepository<SourceResult, UUID> {
    boolean existsByCanonicalUrlHash(String canonicalUrlHash);

    long countBySearchRunId(UUID searchRunId);

    /** Sources the pipeline has not yet finished, oldest first, still inside retention. */
    List<SourceResult> findByProcessedAtIsNullAndExpiresAtAfterOrderByRetrievedAtAsc(
            Instant now, Pageable limit);
}
