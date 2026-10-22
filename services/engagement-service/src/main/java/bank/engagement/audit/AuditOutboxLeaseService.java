package bank.engagement.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditOutboxLeaseService {

    private final AuditOutboxRepository repository;

    @Transactional
    public List<AuditOutbox> claim(Instant now, int batchSize, Duration leaseDuration, int maxAttempts) {
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
        repository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("audit event does not exist"))
                .delivered(now);
    }

    @Transactional
    public void failed(UUID eventId) {
        repository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("audit event does not exist"))
                .failed();
    }
}
