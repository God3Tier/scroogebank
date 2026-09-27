package bank.alert.ingestion;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.SearchRun;
import bank.alert.domain.SourceResult;
import bank.alert.repository.ProcessingWatermarkRepository;
import bank.alert.repository.SearchRunRepository;
import bank.alert.repository.SourceResultRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class IngestionCoordinator {

    private static final Duration RUN_LEASE = Duration.ofMinutes(15);

    private final IngestionProperties properties;
    private final PublicSearchProvider provider;
    private final GroupedQueryBuilder queryBuilder;
    private final BoundedRetryExecutor retryExecutor;
    private final PublicUrlCanonicalizer canonicalizer;
    private final SearchRunRepository searchRuns;
    private final SourceResultRepository sourceResults;
    private final ProcessingWatermarkRepository watermarks;
    private final IngestionRunFinalizer finalizer;
    private final Clock clock;

    public IngestionCoordinator(IngestionProperties properties, PublicSearchProvider provider,
            GroupedQueryBuilder queryBuilder, BoundedRetryExecutor retryExecutor,
            PublicUrlCanonicalizer canonicalizer, SearchRunRepository searchRuns,
            SourceResultRepository sourceResults, ProcessingWatermarkRepository watermarks,
            IngestionRunFinalizer finalizer, Clock clock) {
        this.properties = properties;
        this.provider = provider;
        this.queryBuilder = queryBuilder;
        this.retryExecutor = retryExecutor;
        this.canonicalizer = canonicalizer;
        this.searchRuns = searchRuns;
        this.sourceResults = sourceResults;
        this.watermarks = watermarks;
        this.finalizer = finalizer;
        this.clock = clock;
    }

    public IngestionOutcome run(Instant scheduledWindowTo, List<InstrumentSearchTerms> catalogue) {
        if (!properties.enabled()) return IngestionOutcome.skipped(IngestionStatus.DISABLED, scheduledWindowTo);

        List<String> queries = queryBuilder.build(catalogue, properties.maxQueriesPerRun());
        if (queries.isEmpty()) return IngestionOutcome.skipped(IngestionStatus.NO_QUERIES, scheduledWindowTo);

        Instant windowFrom = watermarks.findById(properties.provider())
                .map(watermark -> watermark.getLastSuccessfulTo().minus(properties.overlap()))
                .orElseGet(() -> scheduledWindowTo.minus(properties.initialLookback()));
        if (!windowFrom.isBefore(scheduledWindowTo)) {
            throw new IllegalArgumentException("scheduled window must advance beyond the current watermark");
        }

        Instant now = clock.instant();
        SearchRun run = searchRuns.findByWindowFromAndWindowTo(windowFrom, scheduledWindowTo)
                .orElseGet(() -> new SearchRun(UUID.randomUUID(), windowFrom, scheduledWindowTo, now));
        if ("SUCCEEDED".equals(run.getStatus())) {
            return outcome(IngestionStatus.ALREADY_SUCCEEDED, run, 0, run.getResultCount(), 0, 0, null);
        }
        if (run.hasActiveLeaseAt(now)) {
            return outcome(IngestionStatus.ALREADY_RUNNING, run, 0, 0, 0, 0, null);
        }
        run.restart();
        run.claimUntil(now.plus(RUN_LEASE));
        try {
            searchRuns.saveAndFlush(run);
        } catch (DataIntegrityViolationException concurrentClaim) {
            SearchRun winner = searchRuns.findByWindowFromAndWindowTo(windowFrom, scheduledWindowTo)
                    .orElseThrow(() -> concurrentClaim);
            IngestionStatus status = "SUCCEEDED".equals(winner.getStatus())
                    ? IngestionStatus.ALREADY_SUCCEEDED
                    : IngestionStatus.ALREADY_RUNNING;
            return outcome(status, winner, 0, winner.getResultCount(), 0, 0, null);
        }

        int attempts = 0;
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        Set<String> batchHashes = new HashSet<>();

        for (String query : queries) {
            RetryResult<List<PublicSearchResult>> response;
            try {
                response = retryExecutor.execute(properties.maxAttempts(), () -> provider.search(
                        new PublicSearchRequest(query, properties.approvedDomains(), windowFrom,
                                scheduledWindowTo, properties.maxResultsPerQuery())));
                attempts += response.attempts();
                run.recordSearchAttempts(response.attempts());
            } catch (RetryExhaustedException exception) {
                attempts += exception.attempts();
                run.recordSearchAttempts(exception.attempts());
                finalizer.fail(run, safeCode(exception.code()), clock.instant());
                return outcome(IngestionStatus.FAILED, run, attempts, accepted, duplicates, rejected,
                        safeCode(exception.code()));
            } catch (RuntimeException exception) {
                attempts++;
                run.recordSearchAttempts(1);
                finalizer.fail(run, "UNEXPECTED_PROVIDER_FAILURE", clock.instant());
                return outcome(IngestionStatus.FAILED, run, attempts, accepted, duplicates, rejected,
                        "UNEXPECTED_PROVIDER_FAILURE");
            }

            if (response.value().size() > properties.maxResultsPerQuery()) {
                finalizer.fail(run, "RESULT_LIMIT_EXCEEDED", clock.instant());
                return outcome(IngestionStatus.FAILED, run, attempts, accepted, duplicates, rejected,
                        "RESULT_LIMIT_EXCEEDED");
            }

            for (PublicSearchResult result : response.value()) {
                if (!insideWindow(result.publishedAt(), windowFrom, scheduledWindowTo)) {
                    rejected++;
                    continue;
                }
                CanonicalPublicUrl url;
                try {
                    url = canonicalizer.canonicalize(result.url(), properties.approvedDomains());
                } catch (IllegalArgumentException exception) {
                    rejected++;
                    continue;
                }
                if (!batchHashes.add(url.sha256()) || sourceResults.existsByCanonicalUrlHash(url.sha256())) {
                    duplicates++;
                    continue;
                }
                SourceResult source = new SourceResult(
                        UUID.randomUUID(), run.getId(), result.providerResultId(), url.value(), url.sha256(),
                        result.title(), url.host(), result.publishedAt(), result.retrievedAt(),
                        result.contentHash(), result.retrievedAt().plus(properties.retention()));
                try {
                    sourceResults.saveAndFlush(source);
                    accepted++;
                } catch (DataIntegrityViolationException duplicateRace) {
                    duplicates++;
                }
            }
        }

        finalizer.succeed(run, properties.provider(), scheduledWindowTo, properties.overlap(),
                accepted, clock.instant());
        return outcome(IngestionStatus.SUCCEEDED, run, attempts, accepted, duplicates, rejected, null);
    }

    private static boolean insideWindow(Instant publishedAt, Instant from, Instant to) {
        return publishedAt == null || (!publishedAt.isBefore(from) && !publishedAt.isAfter(to));
    }

    private static String safeCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) return "PROVIDER_FAILURE";
        return code;
    }

    private static IngestionOutcome outcome(IngestionStatus status, SearchRun run, int attempts,
            int accepted, int duplicates, int rejected, String errorCode) {
        return new IngestionOutcome(status, run.getId(), run.getWindowFrom(), run.getWindowTo(),
                attempts, accepted, duplicates, rejected, errorCode);
    }
}
