package bank.alert.repository;

import bank.alert.domain.ProcessingWatermark;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessingWatermarkRepository extends JpaRepository<ProcessingWatermark, String> {
}
