package bank.alert.repository;

import bank.alert.domain.InstrumentSearchCoverage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentSearchCoverageRepository extends JpaRepository<InstrumentSearchCoverage, String> {
}
