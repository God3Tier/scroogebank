package bank.alert.repository;

import bank.alert.domain.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstrumentRepository extends JpaRepository<Instrument, String> {
    List<Instrument> findByActiveTrueOrderByIdAsc();
}
