package bank.alert.repository;

import bank.alert.domain.SourceResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SourceResultRepository extends JpaRepository<SourceResult, UUID> {
    boolean existsByCanonicalUrlHash(String canonicalUrlHash);
}
