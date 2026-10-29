package bank.clienthealth.analysis.support;

import bank.clienthealth.analysis.port.AnalysisRunPort;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class InMemoryAnalysisRunPort implements AnalysisRunPort {
    private final Map<String, RunRecord> runs = new HashMap<>();
    private final AtomicInteger tokenSequence = new AtomicInteger();

    @Override
    public synchronized Acquisition acquire(
            String runId, Instant cutoff, Instant now, Duration leaseDuration
    ) {
        RunRecord current = runs.get(runId);
        if (current != null && current.status == RunState.SUCCEEDED) {
            return new Acquisition(AcquisitionStatus.ALREADY_SUCCEEDED, null);
        }
        if (current != null && current.status == RunState.RUNNING
                && current.lease.expiresAt().isAfter(now)) {
            return new Acquisition(AcquisitionStatus.LEASE_HELD, null);
        }
        Lease lease = new Lease(
                runId, "lease-" + tokenSequence.incrementAndGet(), now.plus(leaseDuration));
        runs.put(runId, new RunRecord(
                RunState.RUNNING, lease, cutoff, current == null ? 1 : current.attempts + 1,
                current == null ? null : current.watermark, null));
        return new Acquisition(AcquisitionStatus.ACQUIRED, lease);
    }

    @Override
    public synchronized Lease renew(Lease lease, Instant now, Duration leaseDuration) {
        RunRecord current = requireLive(lease, now);
        Lease renewed = new Lease(lease.runId(), lease.token(), now.plus(leaseDuration));
        runs.put(lease.runId(), current.withLease(renewed));
        return renewed;
    }

    @Override
    public synchronized void succeed(Lease lease, Instant watermark, Instant now) {
        RunRecord current = requireLive(lease, now);
        runs.put(lease.runId(), new RunRecord(
                RunState.SUCCEEDED, lease, current.cutoff, current.attempts,
                watermark, null));
    }

    @Override
    public synchronized void fail(Lease lease, FailureCategory category, Instant now) {
        RunRecord current = requireToken(lease);
        runs.put(lease.runId(), new RunRecord(
                RunState.FAILED, lease, current.cutoff, current.attempts,
                current.watermark, category));
    }

    public synchronized RunView view(String runId) {
        RunRecord record = runs.get(runId);
        return record == null ? null : new RunView(
                record.status, record.attempts, record.watermark, record.failure);
    }

    private RunRecord requireLive(Lease lease, Instant now) {
        RunRecord current = requireToken(lease);
        if (current.status != RunState.RUNNING || !current.lease.expiresAt().isAfter(now)) {
            throw new IllegalStateException("analysis lease is not live");
        }
        return current;
    }

    private RunRecord requireToken(Lease lease) {
        RunRecord current = runs.get(lease.runId());
        if (current == null || !current.lease.token().equals(lease.token())) {
            throw new IllegalStateException("analysis lease token does not own the run");
        }
        return current;
    }

    public enum RunState { RUNNING, SUCCEEDED, FAILED }

    public record RunView(
            RunState status,
            int attempts,
            Instant watermark,
            FailureCategory failure
    ) { }

    private record RunRecord(
            RunState status,
            Lease lease,
            Instant cutoff,
            int attempts,
            Instant watermark,
            FailureCategory failure
    ) {
        RunRecord withLease(Lease next) {
            return new RunRecord(status, next, cutoff, attempts, watermark, failure);
        }
    }
}
