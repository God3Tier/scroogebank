package bank.alert.repository;

import bank.alert.domain.HoldingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HoldingSnapshotRepository extends JpaRepository<HoldingSnapshot, UUID> {

    List<HoldingSnapshot> findAllByInstrumentId(String instrumentId);
}
