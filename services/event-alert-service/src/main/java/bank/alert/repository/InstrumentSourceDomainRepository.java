package bank.alert.repository;

import bank.alert.domain.InstrumentSourceDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstrumentSourceDomainRepository extends JpaRepository<InstrumentSourceDomain, UUID> {

    List<InstrumentSourceDomain> findByInstrumentIdAndActiveTrueOrderByAddedAtAsc(String instrumentId);

    List<InstrumentSourceDomain> findByInstrumentIdInAndActiveTrueOrderByAddedAtAsc(Collection<String> instrumentIds);

    Optional<InstrumentSourceDomain> findByIdAndInstrumentIdAndActiveTrue(UUID id, String instrumentId);

    long countByInstrumentIdAndActiveTrue(String instrumentId);
}
