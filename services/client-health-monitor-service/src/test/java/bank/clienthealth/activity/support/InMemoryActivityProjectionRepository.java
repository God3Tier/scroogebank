package bank.clienthealth.activity.support;

import bank.clienthealth.activity.domain.ProjectedTransaction;
import bank.clienthealth.activity.port.ActivityProjectionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class InMemoryActivityProjectionRepository implements ActivityProjectionRepository {
    private Map<String, ProjectedTransaction> transactions = new LinkedHashMap<>();
    private List<Coverage> coverage = new ArrayList<>();

    @Override
    public synchronized <T> T inTransaction(Function<Session, T> work) {
        Map<String, ProjectedTransaction> before = new LinkedHashMap<>(transactions);
        List<Coverage> coverageBefore = new ArrayList<>(coverage);
        try {
            return work.apply(new MemorySession());
        } catch (RuntimeException failure) {
            transactions = before;
            coverage = coverageBefore;
            throw failure;
        }
    }

    public List<ProjectedTransaction> all() {
        return transactions.values().stream()
                .sorted(Comparator.comparing(ProjectedTransaction::transactionDate)
                        .thenComparing(ProjectedTransaction::transactionId))
                .toList();
    }

    private final class MemorySession implements Session {
        @Override
        public Optional<ProjectedTransaction> findById(String transactionId) {
            return Optional.ofNullable(transactions.get(transactionId));
        }

        @Override
        public void insert(ProjectedTransaction transaction) {
            if (transactions.putIfAbsent(transaction.transactionId(), transaction) != null) {
                throw new IllegalStateException("duplicate transaction");
            }
        }

        @Override
        public void replace(ProjectedTransaction transaction) {
            if (transactions.replace(transaction.transactionId(), transaction) == null) {
                throw new IllegalStateException("missing transaction");
            }
        }

        @Override
        public List<ProjectedTransaction> findWindow(Instant fromInclusive, Instant toExclusive) {
            return all().stream()
                    .filter(transaction -> !transaction.transactionDate().isBefore(fromInclusive))
                    .filter(transaction -> transaction.transactionDate().isBefore(toExclusive))
                    .toList();
        }

        @Override
        public void recordCoverage(Instant fromInclusive, Instant toExclusive) {
            coverage.add(new Coverage(fromInclusive, toExclusive));
        }

        @Override
        public boolean covers(Instant fromInclusive, Instant toExclusive) {
            return coverage.stream().anyMatch(window ->
                    !fromInclusive.isBefore(window.fromInclusive())
                            && !toExclusive.isAfter(window.toExclusive()));
        }
    }

    private record Coverage(Instant fromInclusive, Instant toExclusive) { }
}
