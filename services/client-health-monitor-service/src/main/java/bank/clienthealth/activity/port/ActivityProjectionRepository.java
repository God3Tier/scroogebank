package bank.clienthealth.activity.port;

import bank.clienthealth.activity.domain.ProjectedTransaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Atomic persistence boundary for the service-owned activity projection. */
public interface ActivityProjectionRepository {
    <T> T inTransaction(Function<Session, T> work);

    interface Session {
        Optional<ProjectedTransaction> findById(String transactionId);

        void insert(ProjectedTransaction transaction);

        void replace(ProjectedTransaction transaction);

        List<ProjectedTransaction> findWindow(Instant fromInclusive, Instant toExclusive);

        void recordCoverage(Instant fromInclusive, Instant toExclusive);

        boolean covers(Instant fromInclusive, Instant toExclusive);
    }
}
