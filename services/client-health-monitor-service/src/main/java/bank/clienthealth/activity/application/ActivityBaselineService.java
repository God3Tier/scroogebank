package bank.clienthealth.activity.application;

import bank.clienthealth.activity.domain.ProjectedTransaction;
import bank.clienthealth.activity.port.ActivityProjectionRepository;
import bank.clienthealth.transaction.contract.MatchedTransaction.ActivityKind;
import bank.clienthealth.transaction.contract.MatchedTransaction.Status;
import bank.clienthealth.transaction.contract.MatchedTransaction.TransactionType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Builds deterministic 90-day baseline and 30-day comparison summaries. */
public final class ActivityBaselineService {
    public static final ZoneId ANALYSIS_ZONE = ZoneId.of("Asia/Singapore");
    private static final LocalTime ANALYSIS_TIME = LocalTime.of(8, 0);
    private static final String MVP_CURRENCY = "SGD";
    private final ActivityProjectionRepository repository;

    public ActivityBaselineService(ActivityProjectionRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public static Instant analysisCutoff(LocalDate analysisDate) {
        return Objects.requireNonNull(analysisDate, "analysisDate is required")
                .atTime(ANALYSIS_TIME).atZone(ANALYSIS_ZONE).toInstant();
    }

    public static Instant historyStart(LocalDate analysisDate) {
        return Objects.requireNonNull(analysisDate, "analysisDate is required")
                .minusMonths(6).atTime(ANALYSIS_TIME).atZone(ANALYSIS_ZONE).toInstant();
    }

    public BaselineResult calculate(LocalDate analysisDate) {
        Objects.requireNonNull(analysisDate, "analysisDate is required");
        Instant cutoff = analysisCutoff(analysisDate);
        Instant historyStart = historyStart(analysisDate);
        Instant comparisonStart = analysisDate.minusDays(30)
                .atTime(ANALYSIS_TIME).atZone(ANALYSIS_ZONE).toInstant();
        Instant baselineStart = analysisDate.minusDays(120)
                .atTime(ANALYSIS_TIME).atZone(ANALYSIS_ZONE).toInstant();

        return repository.inTransaction(session -> {
            if (!session.covers(historyStart, cutoff)) {
                throw new InsufficientCoverageException("Projection does not cover the complete six-month window");
            }
            List<ProjectedTransaction> transactions = session.findWindow(historyStart, cutoff);
            transactions.stream()
                    .filter(transaction -> !MVP_CURRENCY.equals(transaction.currency()))
                    .findFirst()
                    .ifPresent(transaction -> {
                        throw new UnsupportedCurrencyException(transaction.currency());
                    });

            Map<String, List<ProjectedTransaction>> byClient = transactions.stream()
                    .collect(Collectors.groupingBy(
                            ProjectedTransaction::clientId, LinkedHashMap::new, Collectors.toList()));
            Map<String, ClientBaseline> clients = new LinkedHashMap<>();
            byClient.forEach((clientId, clientTransactions) -> clients.put(
                    clientId,
                    clientBaseline(clientId, clientTransactions, baselineStart, comparisonStart, cutoff)));
            return new BaselineResult(historyStart, baselineStart, comparisonStart, cutoff, clients);
        });
    }

    private static ClientBaseline clientBaseline(
            String clientId,
            List<ProjectedTransaction> transactions,
            Instant baselineStart,
            Instant comparisonStart,
            Instant cutoff
    ) {
        Map<String, List<ProjectedTransaction>> byAccount = transactions.stream()
                .collect(Collectors.groupingBy(
                        ProjectedTransaction::accountId, LinkedHashMap::new, Collectors.toList()));
        List<AccountBaseline> accounts = byAccount.entrySet().stream()
                .map(entry -> accountBaseline(
                        entry.getKey(), entry.getValue(), baselineStart, comparisonStart, cutoff))
                .sorted(Comparator.comparing(AccountBaseline::accountId))
                .toList();
        ActivitySummary baseline = summarize(transactions, baselineStart, comparisonStart);
        ActivitySummary comparison = summarize(transactions, comparisonStart, cutoff);
        return new ClientBaseline(
                clientId, accounts, baseline, comparison, baseline.completedCount() > 0);
    }

    private static AccountBaseline accountBaseline(
            String accountId,
            List<ProjectedTransaction> transactions,
            Instant baselineStart,
            Instant comparisonStart,
            Instant cutoff
    ) {
        Map<String, List<RecurringCredit>> recurring = transactions.stream()
                .filter(transaction -> transaction.status() == Status.COMPLETED)
                .filter(transaction -> transaction.type() == TransactionType.DEPOSIT)
                .filter(transaction -> transaction.sourceFingerprint() != null)
                .collect(Collectors.groupingBy(
                        ProjectedTransaction::sourceFingerprint,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                transaction -> new RecurringCredit(
                                        transaction.transactionDate(), transaction.amount()),
                                Collectors.toList())));
        List<RecurringCreditSeries> series = recurring.entrySet().stream()
                .map(entry -> new RecurringCreditSeries(
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(Comparator.comparing(RecurringCredit::transactionDate))
                                .toList()))
                .sorted(Comparator.comparing(RecurringCreditSeries::sourceFingerprint))
                .toList();
        return new AccountBaseline(
                accountId,
                summarize(transactions, baselineStart, comparisonStart),
                summarize(transactions, comparisonStart, cutoff),
                series,
                periods(transactions, cutoff));
    }

    private static List<PeriodSummary> periods(
            List<ProjectedTransaction> transactions, Instant cutoff
    ) {
        List<PeriodSummary> periods = new ArrayList<>();
        for (int remaining = 6; remaining > 0; remaining--) {
            Instant from = cutoff.minus(Duration.ofDays(30L * remaining));
            Instant to = cutoff.minus(Duration.ofDays(30L * (remaining - 1)));
            periods.add(new PeriodSummary(from, to, summarize(transactions, from, to)));
        }
        return List.copyOf(periods);
    }

    private static ActivitySummary summarize(
            List<ProjectedTransaction> transactions, Instant from, Instant to
    ) {
        List<ProjectedTransaction> completed = transactions.stream()
                .filter(transaction -> !transaction.transactionDate().isBefore(from))
                .filter(transaction -> transaction.transactionDate().isBefore(to))
                .filter(transaction -> transaction.status() == Status.COMPLETED)
                .toList();
        long customerInitiated = completed.stream()
                .filter(transaction -> transaction.activityKind() == ActivityKind.CUSTOMER_INITIATED)
                .count();
        long automated = completed.stream()
                .filter(transaction -> transaction.activityKind() == ActivityKind.AUTOMATED)
                .count();
        long unknown = completed.stream()
                .filter(transaction -> transaction.activityKind() == ActivityKind.UNKNOWN)
                .count();
        BigDecimal deposits = amount(completed, TransactionType.DEPOSIT);
        BigDecimal withdrawals = amount(completed, TransactionType.WITHDRAWAL);
        return new ActivitySummary(
                completed.size(), customerInitiated, automated, unknown,
                deposits, withdrawals, deposits.subtract(withdrawals), MVP_CURRENCY);
    }

    private static BigDecimal amount(
            List<ProjectedTransaction> transactions, TransactionType type
    ) {
        return transactions.stream()
                .filter(transaction -> transaction.type() == type)
                .map(ProjectedTransaction::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public record BaselineResult(
            Instant historyStart,
            Instant baselineStart,
            Instant comparisonStart,
            Instant analysisCutoff,
            Map<String, ClientBaseline> clients
    ) {
        public BaselineResult {
            clients = Map.copyOf(clients);
        }
    }

    public record ClientBaseline(
            String clientId,
            List<AccountBaseline> accounts,
            ActivitySummary baseline,
            ActivitySummary comparison,
            boolean hasBaselineActivity
    ) {
        public ClientBaseline {
            accounts = List.copyOf(accounts);
        }
    }

    public record AccountBaseline(
            String accountId,
            ActivitySummary baseline,
            ActivitySummary comparison,
            List<RecurringCreditSeries> recurringCredits,
            List<PeriodSummary> periods
    ) {
        public AccountBaseline {
            recurringCredits = List.copyOf(recurringCredits);
            periods = List.copyOf(periods);
        }
    }

    public record PeriodSummary(
            Instant fromInclusive,
            Instant toExclusive,
            ActivitySummary summary
    ) { }

    public record ActivitySummary(
            long completedCount,
            long customerInitiatedCount,
            long automatedCount,
            long unknownCount,
            BigDecimal deposits,
            BigDecimal withdrawals,
            BigDecimal netFlow,
            String currency
    ) { }

    public record RecurringCreditSeries(
            String sourceFingerprint,
            List<RecurringCredit> occurrences
    ) {
        public RecurringCreditSeries {
            occurrences = List.copyOf(occurrences);
        }
    }

    public record RecurringCredit(Instant transactionDate, BigDecimal amount) { }

    public static final class InsufficientCoverageException extends RuntimeException {
        public InsufficientCoverageException(String message) {
            super(message);
        }
    }

    public static final class UnsupportedCurrencyException extends RuntimeException {
        public UnsupportedCurrencyException(String currency) {
            super("Unsupported currency in activity window: " + currency);
        }
    }
}
