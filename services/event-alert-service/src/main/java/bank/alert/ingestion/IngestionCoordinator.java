package bank.alert.ingestion;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.SearchRun;
import bank.alert.domain.SourceResult;
import bank.alert.repository.ProcessingWatermarkRepository;
import bank.alert.repository.SearchRunRepository;
import bank.alert.repository.SourceResultRepository;
import bank.alert.operations.OutboundControlService;
import bank.alert.operations.ReservationResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class IngestionCoordinator {

    private static final Duration RUN_LEASE = Duration.ofMinutes(15);

    private final IngestionProperties properties;
    private final PublicSearchProvider provider;
    private final IssuerQueryBuilder queryBuilder;
    private final BoundedRetryExecutor retryExecutor;
    private final PublicUrlCanonicalizer canonicalizer;
    private final SearchRunRepository searchRuns;
    private final SourceResultRepository sourceResults;
    private final ProcessingWatermarkRepository watermarks;
    private final IngestionRunFinalizer finalizer;
    private final OutboundControlService outboundControls;
    private final IngestionRunLock runLock;
    private final Clock clock;
    private static final Duration RESEARCH_POLL = Duration.ofSeconds(5);
    private static final int RESEARCH_MAX_WAITS = 120; // 10 minutes
    private Pause pause = IngestionCoordinator::sleep;

    public IngestionCoordinator(IngestionProperties properties, PublicSearchProvider provider,
            IssuerQueryBuilder queryBuilder, BoundedRetryExecutor retryExecutor,
            PublicUrlCanonicalizer canonicalizer, SearchRunRepository searchRuns,
            SourceResultRepository sourceResults, ProcessingWatermarkRepository watermarks,
            IngestionRunFinalizer finalizer, OutboundControlService outboundControls,
            IngestionRunLock runLock, Clock clock) {
        this.properties = properties;
        this.provider = provider;
        this.queryBuilder = queryBuilder;
        this.retryExecutor = retryExecutor;
        this.canonicalizer = canonicalizer;
        this.searchRuns = searchRuns;
        this.sourceResults = sourceResults;
        this.watermarks = watermarks;
        this.finalizer = finalizer;
        this.outboundControls = outboundControls;
        this.runLock = runLock;
        this.clock = clock;
    }

    public IngestionOutcome run(Instant scheduledWindowTo, List<InstrumentSearchTerms> catalogue) {
        if (!properties.enabled()) return IngestionOutcome.skipped(IngestionStatus.DISABLED, scheduledWindowTo);
        Instant windowFrom = watermarks.findById(properties.provider())
                .map(watermark -> watermark.getLastSuccessfulTo().minus(properties.overlap()))
                .orElseGet(() -> scheduledWindowTo.minus(properties.initialLookback()));
        return execute(windowFrom, scheduledWindowTo, catalogue, true, properties.approvedDomains());
    }

    public IngestionOutcome run(Instant windowFrom, Instant windowTo,
            List<InstrumentSearchTerms> catalogue) {
        if (!properties.enabled()) return IngestionOutcome.skipped(IngestionStatus.DISABLED, windowTo);
        return execute(windowFrom, windowTo, catalogue, true, properties.approvedDomains());
    }

    /** Reprocesses one historical bounded window without moving the scheduler watermark. */
    public IngestionOutcome replay(Instant windowFrom, Instant windowTo,
            List<InstrumentSearchTerms> catalogue) {
        if (!properties.enabled()) return IngestionOutcome.skipped(IngestionStatus.DISABLED, windowTo);
        requireReplayWindow(windowFrom, windowTo);
        return execute(windowFrom, windowTo, catalogue, false, properties.approvedDomains());
    }

    /**
     * One targeted search over a recent window (ADR-0081), started when an agent adds a website or presses
     * Search now. The caller says which websites are searched (the added one alone, or all of them). Like a
     * replay it does not move the scheduler watermark.
     */
    public IngestionOutcome research(Instant windowFrom, Instant windowTo, List<InstrumentSearchTerms> catalogue,
            List<String> sharedDomains) {
        if (!properties.enabled()) return IngestionOutcome.skipped(IngestionStatus.DISABLED, windowTo);
        IngestionOutcome outcome = execute(windowFrom, windowTo, catalogue, false, sharedDomains);
        // Only one search runs at a time. Searches started a moment apart (one Search now per company) must take turns,
        // not be dropped: wait for the lock, a few seconds at a time, for up to RESEARCH_MAX_WAITS * RESEARCH_POLL.
        for (int waits = 0; waits < RESEARCH_MAX_WAITS && waitingForTheLock(outcome); waits++) {
            if (!pause.pause(RESEARCH_POLL)) break;
            outcome = execute(windowFrom, windowTo, catalogue, false, sharedDomains);
        }
        return outcome;
    }

    /** True when the search was refused only because another search holds the lock (no run was started). */
    private static boolean waitingForTheLock(IngestionOutcome outcome) {
        return outcome.status() == IngestionStatus.ALREADY_RUNNING && outcome.runId() == null;
    }

    /** Waits between tries; false if the wait was interrupted, so the caller stops waiting. Replaceable in tests. */
    interface Pause {
        boolean pause(Duration duration);
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void usePause(Pause replacement) {
        this.pause = replacement;
    }

    private IngestionOutcome execute(Instant windowFrom, Instant windowTo,
            List<InstrumentSearchTerms> catalogue, boolean advanceWatermark, List<String> sharedDomains) {
        if (!windowFrom.isBefore(windowTo)) {
            throw new IllegalArgumentException("ingestion window start must precede its end");
        }
        // Windows that start at different times have different search_run rows, so
        // only this lock keeps two runs, scheduled or manual, from both searching.
        Optional<IngestionRunLock.Hold> hold = runLock.tryAcquire();
        if (hold.isEmpty()) {
            return new IngestionOutcome(IngestionStatus.ALREADY_RUNNING, null, windowFrom, windowTo,
                    0, 0, 0, 0, null);
        }
        try (IngestionRunLock.Hold ignored = hold.get()) {
            return executeLocked(windowFrom, windowTo, catalogue, advanceWatermark, sharedDomains);
        }
    }

    private IngestionOutcome executeLocked(Instant windowFrom, Instant windowTo,
            List<InstrumentSearchTerms> catalogue, boolean advanceWatermark, List<String> sharedDomains) {
        List<IssuerSearchQuery> queries = queryBuilder.build(
                catalogue, sharedDomains);
        if (queries.isEmpty()) return IngestionOutcome.skipped(IngestionStatus.NO_QUERIES, windowTo);

        Instant now = clock.instant();
        SearchRun run = searchRuns.findByWindowFromAndWindowTo(windowFrom, windowTo)
                .orElseGet(() -> new SearchRun(UUID.randomUUID(), windowFrom, windowTo, now));
        if ("SUCCEEDED".equals(run.getStatus())) {
            return outcome(IngestionStatus.ALREADY_SUCCEEDED, run, 0, run.getResultCount(), 0, 0, null);
        }
        if (run.hasActiveLeaseAt(now)) {
            return outcome(IngestionStatus.ALREADY_RUNNING, run, 0, 0, 0, 0, null);
        }
        ReservationResult reservation = outboundControls.reserveSearches(queries.size());
        if (!reservation.allowed()) {
            return new IngestionOutcome(IngestionStatus.CONTROL_BLOCKED, null, windowFrom, windowTo,
                    0, 0, 0, 0, reservation.code());
        }
        run.restart();
        run.claimUntil(now.plus(RUN_LEASE));
        try {
            searchRuns.saveAndFlush(run);
        } catch (DataIntegrityViolationException concurrentClaim) {
            SearchRun winner = searchRuns.findByWindowFromAndWindowTo(windowFrom, windowTo)
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

        for (IssuerSearchQuery query : queries) {
            RetryResult<List<PublicSearchResult>> response;
            AtomicInteger queryAttempts = new AtomicInteger();
            try {
                response = retryExecutor.execute(properties.maxAttempts(), () -> {
                    // The first attempt was reserved with the batch; retries consume quota too.
                    if (queryAttempts.get() > 0) {
                        ReservationResult retryReservation = outboundControls.reserveSearches(1);
                        if (!retryReservation.allowed()) {
                            throw new SearchProviderException(retryReservation.code(), false,
                                    "search retry blocked by outbound controls");
                        }
                    }
                    queryAttempts.incrementAndGet();
                    return provider.search(new PublicSearchRequest(query.query(), query.approvedDomains(),
                            windowFrom, windowTo, properties.maxResultsPerQuery()));
                });
                attempts += queryAttempts.get();
                run.recordSearchAttempts(queryAttempts.get());
            } catch (RetryExhaustedException exception) {
                attempts += queryAttempts.get();
                run.recordSearchAttempts(queryAttempts.get());
                finalizer.fail(run, safeCode(exception.code()), clock.instant());
                return outcome(IngestionStatus.FAILED, run, attempts, accepted, duplicates, rejected,
                        safeCode(exception.code()));
            } catch (RuntimeException exception) {
                attempts += queryAttempts.get();
                run.recordSearchAttempts(queryAttempts.get());
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
                if (!query.accepts(result)) {
                    rejected++;
                    continue;
                }
                if (!insideWindow(result.publishedAt(), windowFrom, windowTo)) {
                    rejected++;
                    continue;
                }
                CanonicalPublicUrl url;
                try {
                    url = canonicalizer.canonicalize(result.url(), query.approvedDomains());
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
                        result.contentHash(), result.retrievedAt().plus(properties.retention()),
                        result.snippet());
                try {
                    sourceResults.saveAndFlush(source);
                    accepted++;
                } catch (DataIntegrityViolationException duplicateRace) {
                    duplicates++;
                }
            }
        }

        int durableResultCount = Math.toIntExact(sourceResults.countBySearchRunId(run.getId()));
        if (advanceWatermark) {
            finalizer.succeed(run, properties.provider(), windowTo, properties.overlap(),
                    durableResultCount, clock.instant());
        } else {
            finalizer.succeedReplay(run, durableResultCount, clock.instant());
        }
        return outcome(IngestionStatus.SUCCEEDED, run, attempts, accepted, duplicates, rejected, null);
    }

    private void requireReplayWindow(Instant windowFrom, Instant windowTo) {
        if (windowFrom == null || windowTo == null || !windowFrom.isBefore(windowTo)) {
            throw new IllegalArgumentException("replay window start must precede its end");
        }
        if (windowTo.isAfter(clock.instant())) {
            throw new IllegalArgumentException("replay window cannot extend into the future");
        }
        if (Duration.between(windowFrom, windowTo).compareTo(properties.initialLookback()) > 0) {
            throw new IllegalArgumentException("replay window exceeds the configured maximum span");
        }
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
