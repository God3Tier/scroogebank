package bank.clienthealth.activity.application;

import bank.clienthealth.activity.domain.ProjectedTransaction;
import bank.clienthealth.activity.port.ActivityProjectionRepository;
import bank.clienthealth.transaction.application.TransactionReplayService.ReplayResult;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import java.util.Objects;

/** Atomically applies one complete replay to the local activity projection. */
public final class ActivityProjectionService {
    private final ActivityProjectionRepository repository;

    public ActivityProjectionService(ActivityProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public ApplyResult apply(ReplayResult replay) {
        Objects.requireNonNull(replay, "replay is required");
        return repository.inTransaction(session -> {
            int inserted = 0;
            int updated = 0;
            int unchanged = 0;

            for (var incoming : replay.transactions()) {
                ProjectedTransaction projected = ProjectedTransaction.from(incoming);
                var existing = session.findById(projected.transactionId());
                if (existing.isEmpty()) {
                    session.insert(projected);
                    inserted++;
                    continue;
                }

                ProjectedTransaction current = existing.get();
                if (!current.hasSameImmutableFields(projected)) {
                    throw new ProjectionConflictException(
                            "Immutable transaction fields changed: " + projected.transactionId());
                }
                if (current.status() == projected.status()) {
                    unchanged++;
                } else if (current.status() == Status.PENDING
                        && (projected.status() == Status.COMPLETED
                        || projected.status() == Status.FAILED)) {
                    session.replace(current.withStatus(projected.status()));
                    updated++;
                } else {
                    throw new ProjectionConflictException(
                            "Invalid status transition for transaction: " + projected.transactionId());
                }
            }

            session.recordCoverage(replay.from(), replay.to());
            return new ApplyResult(inserted, updated, unchanged);
        });
    }

    public record ApplyResult(int inserted, int updated, int unchanged) {
        public ApplyResult {
            if (inserted < 0 || updated < 0 || unchanged < 0) {
                throw new IllegalArgumentException("projection counts cannot be negative");
            }
        }
    }

    public static final class ProjectionConflictException extends RuntimeException {
        public ProjectionConflictException(String message) {
            super(message);
        }
    }
}
