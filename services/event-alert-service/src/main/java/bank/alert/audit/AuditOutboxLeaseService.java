package bank.alert.audit;

import bank.alert.domain.AuditOutbox;
import bank.alert.repository.AuditOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuditOutboxLeaseService {

    private final AuditOutboxRepository repository;

    public AuditOutboxLeaseService(AuditOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<AuditOutbox> claim(Instant now, int batchSize, Duration leaseDuration,
            int maxAttempts) {
        if (batchSize < 1) throw new IllegalArgumentException("batch size must be positive");
        if (maxAttempts < 1) throw new IllegalArgumentException("max attempts must be positive");
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        List<AuditOutbox> claimed = repository.findClaimable(now, batchSize, maxAttempts);
        claimed.forEach(event -> event.leaseUntil(now.plus(leaseDuration)));
        return repository.saveAll(claimed);
    }

    @Transactional
    public void delivered(UUID eventId, Instant now) {
        AuditOutbox event = repository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("audit event does not exist"));
        event.delivered(now);
    }

    @Transactional
    public void failed(UUID eventId) {
        AuditOutbox event = repository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("audit event does not exist"));
        event.failed();
    }
}
