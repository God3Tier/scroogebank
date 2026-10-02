package bank.alert.ingestion;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.SearchRun;
import bank.alert.repository.ProcessingWatermarkRepository;
import bank.alert.repository.SearchRunRepository;
import bank.alert.repository.SourceResultRepository;
import bank.alert.operations.OutboundControlService;
import bank.alert.operations.ReservationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class IngestionCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-09-28T04:00:00Z");
    private static final List<InstrumentSearchTerms> CATALOGUE = List.of(
            new InstrumentSearchTerms("SYN", "Synthetic Issuer", "Synthetic",
                    List.of(), List.of("earnings")));

    @Mock PublicSearchProvider provider;
    @Mock SearchRunRepository searchRuns;
    @Mock SourceResultRepository sourceResults;
    @Mock ProcessingWatermarkRepository watermarks;
    @Mock IngestionRunFinalizer finalizer;
    @Mock OutboundControlService outboundControls;

    private IngestionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = coordinator(true);
        lenient().when(watermarks.findById("agentcore-web-search-1.2.0")).thenReturn(Optional.empty());
        lenient().when(searchRuns.findByWindowFromAndWindowTo(any(), any())).thenReturn(Optional.empty());
        lenient().when(searchRuns.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(outboundControls.reserveSearches(any(Integer.class)))
                .thenReturn(ReservationResult.allowed(999));
    }

    @Test
    void deduplicatesCanonicalUrlsAndRejectsUnapprovedSources() {
        when(sourceResults.existsByCanonicalUrlHash(any())).thenReturn(false);
        when(sourceResults.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(provider.search(any())).thenReturn(List.of(
                result("r1", "https://www.sgx.com/news/1?utm_source=test"),
                result("r2", "https://www.sgx.com/news/1"),
                result("r3", "https://publisher.invalid/news/2")));

        IngestionOutcome outcome = coordinator.run(NOW, CATALOGUE);

        assertThat(outcome.status()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(outcome.acceptedResults()).isOne();
        assertThat(outcome.duplicateResults()).isOne();
        assertThat(outcome.rejectedResults()).isOne();
        verify(finalizer).succeed(any(SearchRun.class), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void retainsTheWatermarkWhenTheProviderExhaustsRetries() {
        when(provider.search(any())).thenThrow(new SearchProviderException("TIMEOUT", true, "timeout"));

        IngestionOutcome outcome = coordinator.run(NOW, CATALOGUE);

        assertThat(outcome.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(outcome.searchAttempts()).isEqualTo(3);
        assertThat(outcome.errorCode()).isEqualTo("TIMEOUT");
        verify(finalizer).fail(any(SearchRun.class), any(), any());
        verify(finalizer, never()).succeed(any(), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void killSwitchPreventsProviderAndDatabaseCalls() {
        coordinator = coordinator(false);

        IngestionOutcome outcome = coordinator.run(NOW, CATALOGUE);

        assertThat(outcome.status()).isEqualTo(IngestionStatus.DISABLED);
        verify(provider, never()).search(any());
        verify(searchRuns, never()).saveAndFlush(any());
    }

    @Test
    void operationalControlPreventsProviderAndRunPersistence() {
        when(outboundControls.reserveSearches(any(Integer.class)))
                .thenReturn(ReservationResult.denied("SEARCH_QUOTA_REACHED", 0));

        IngestionOutcome outcome = coordinator.run(NOW, CATALOGUE);

        assertThat(outcome.status()).isEqualTo(IngestionStatus.CONTROL_BLOCKED);
        assertThat(outcome.errorCode()).isEqualTo("SEARCH_QUOTA_REACHED");
        verify(provider, never()).search(any());
        verify(searchRuns, never()).saveAndFlush(any());
    }

    @Test
    void overlappingWorkerLosesTheUniqueWindowRaceWithoutCallingTheProvider() {
        SearchRun winner = new SearchRun(java.util.UUID.randomUUID(), NOW.minus(Duration.ofHours(4)), NOW,
                NOW.minusSeconds(1));
        winner.claimUntil(NOW.plus(Duration.ofMinutes(15)));
        when(searchRuns.findByWindowFromAndWindowTo(any(), any()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(searchRuns.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("synthetic race"));

        IngestionOutcome outcome = coordinator.run(NOW, CATALOGUE);

        assertThat(outcome.status()).isEqualTo(IngestionStatus.ALREADY_RUNNING);
        assertThat(outcome.runId()).isEqualTo(winner.getId());
        verify(provider, never()).search(any());
    }

    @Test
    void retryFinalizesUsingAllDurableResultsFromTheExistingRun() {
        SearchRun retry = new SearchRun(java.util.UUID.randomUUID(),
                NOW.minus(Duration.ofHours(4)), NOW, NOW.minus(Duration.ofMinutes(20)));
        retry.fail("TIMEOUT", NOW.minus(Duration.ofMinutes(10)));
        when(searchRuns.findByWindowFromAndWindowTo(any(), any())).thenReturn(Optional.of(retry));
        when(provider.search(any())).thenReturn(List.of());
        when(sourceResults.countBySearchRunId(retry.getId())).thenReturn(2L);

        IngestionOutcome outcome = coordinator.run(NOW, CATALOGUE);

        assertThat(outcome.status()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(outcome.acceptedResults()).isZero();
        verify(finalizer).succeed(retry, "agentcore-web-search-1.2.0", NOW,
                Duration.ofMinutes(5), 2, NOW);
    }

    @Test
    void boundedHistoricalReplayDoesNotAdvanceTheSchedulerWatermark() {
        Instant replayFrom = NOW.minus(Duration.ofHours(2));
        when(provider.search(any())).thenReturn(List.of());

        IngestionOutcome outcome = coordinator.replay(replayFrom, NOW, CATALOGUE);

        assertThat(outcome.status()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(outcome.windowFrom()).isEqualTo(replayFrom);
        assertThat(outcome.windowTo()).isEqualTo(NOW);
        verify(finalizer).succeedReplay(any(SearchRun.class), any(Integer.class), any());
        verify(finalizer, never()).succeed(any(), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void rejectsUnboundedOrFutureReplayWindowsBeforeProviderUse() {
        assertThatThrownBy(() -> coordinator.replay(
                NOW.minus(Duration.ofHours(5)), NOW, CATALOGUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum span");
        assertThatThrownBy(() -> coordinator.replay(
                NOW.minus(Duration.ofHours(1)), NOW.plusSeconds(1), CATALOGUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");

        verify(provider, never()).search(any());
        verify(searchRuns, never()).saveAndFlush(any());
    }

    @Test
    void performsNoSearchWhenNoHoldingMeetsTheUniverseControls() {
        IngestionOutcome outcome = coordinator(true).run(NOW, List.of());

        assertThat(outcome.status()).isEqualTo(IngestionStatus.NO_QUERIES);
        verify(provider, never()).search(any());
        verify(searchRuns, never()).saveAndFlush(any());
    }

    private IngestionCoordinator coordinator(boolean enabled) {
        IngestionProperties properties = new IngestionProperties(enabled,
                "agentcore-web-search-1.2.0", Duration.ofMinutes(5), Duration.ofHours(4),
                4, 10, 3, Duration.ofDays(90), new BigDecimal("0.05"), 2,
                List.of("mas.gov.sg", "sgx.com"), List.of("earnings"));
        return new IngestionCoordinator(properties, provider, new GroupedQueryBuilder(),
                new BoundedRetryExecutor(), new PublicUrlCanonicalizer(), searchRuns, sourceResults,
                watermarks, finalizer, outboundControls, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PublicSearchResult result(String id, String url) {
        return new PublicSearchResult(id, url, "Synthetic public announcement",
                Instant.parse("2026-09-28T03:00:00Z"), NOW, null);
    }
}
