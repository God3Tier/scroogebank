package bank.clienthealth.analysis.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Durable lease, run status, and success-watermark boundary for RH-SF04. */
public interface AnalysisRunPort {
    Acquisition acquire(
            String runId,
            Instant analysisCutoff,
            Instant now,
            Duration leaseDuration
    );

    Lease renew(Lease lease, Instant now, Duration leaseDuration);

    void succeed(Lease lease, Instant watermark, Instant now);

    void fail(Lease lease, FailureCategory category, Instant now);

    record Acquisition(AcquisitionStatus status, Lease lease) {
        public Acquisition {
            status = Objects.requireNonNull(status);
            if ((status == AcquisitionStatus.ACQUIRED) != (lease != null)) {
                throw new IllegalArgumentException("only an acquired run has a lease");
            }
        }
    }

    record Lease(String runId, String token, Instant expiresAt) {
        public Lease {
            runId = Objects.requireNonNull(runId);
            token = Objects.requireNonNull(token);
            expiresAt = Objects.requireNonNull(expiresAt);
        }
    }

    enum AcquisitionStatus {
        ACQUIRED,
        ALREADY_SUCCEEDED,
        LEASE_HELD
    }

    enum FailureCategory {
        REPLAY,
        PROJECTION,
        BASELINE,
        RULE_EVALUATION,
        TASK_HANDOFF,
        UNEXPECTED
    }
}
