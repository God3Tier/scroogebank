package bank.alert.repository;

import bank.alert.domain.SearchWebsite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SearchWebsiteRepository extends JpaRepository<SearchWebsite, UUID> {

    List<SearchWebsite> findByActiveTrueOrderByAddedAtAsc();

    Optional<SearchWebsite> findByIdAndActiveTrue(UUID id);

    long countByActiveTrue();
}
