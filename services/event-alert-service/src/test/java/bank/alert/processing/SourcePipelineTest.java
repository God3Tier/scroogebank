package bank.alert.processing;

import bank.alert.ai.BriefingStatus;
import bank.alert.ai.ClassificationStatus;
import bank.alert.config.AiProperties;
import bank.alert.config.PipelineProperties;
import bank.alert.domain.Instrument;
import bank.alert.domain.SourceResult;
import bank.alert.matching.InstrumentMatch;
import bank.alert.matching.InstrumentMatcher;
import bank.alert.repository.InstrumentRepository;
import bank.alert.repository.SourceResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourcePipelineTest {

    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");

    private final SourceResultRepository sources = mock(SourceResultRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final InstrumentMatcher matcher = mock(InstrumentMatcher.class);
    private final SourceResultProcessor processor = mock(SourceResultProcessor.class);
    private final List<Instrument> master = List.of(mock(Instrument.class));

    @BeforeEach
    void stubMaster() {
        when(instruments.findByActiveTrueOrderByIdAsc()).thenReturn(master);
    }

    private SourcePipeline pipeline(boolean aiEnabled) {
        AiProperties ai = new AiProperties(aiEnabled, null, null, 0, 0, 0, 0, Duration.ofSeconds(20), null);
        return new SourcePipeline(sources, instruments, matcher, processor, ai, new PipelineProperties(50),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SourceResult pending(String title, String excerpt) {
        return new SourceResult(UUID.randomUUID(), UUID.randomUUID(), null, "https://www.sgx.com/" + title,
                "a".repeat(64), title, "sgx.com", NOW.minusSeconds(60), NOW.minusSeconds(30), null,
                NOW.plus(Duration.ofDays(90)), excerpt);
    }

    private void givenPending(SourceResult... results) {
        when(sources.findByProcessedAtIsNullAndExpiresAtAfterOrderByRetrievedAtAsc(eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(results));
    }

    private static SourceFanOutOutcome accepted(int created, String reason) {
        return new SourceFanOutOutcome(ClassificationStatus.MODEL_ACCEPTED, BriefingStatus.GENERATED, reason,
                2, created, 0, 0, 0, 0);
    }

    @Test
    void doesNothingWhileTheModelIsSwitchedOffAndLeavesSourcesPending() {
        PipelineSummary summary = pipeline(false).processPending();

        assertThat(summary.skippedReason()).isEqualTo("MODEL_DISABLED");
        verify(sources, never()).findByProcessedAtIsNullAndExpiresAtAfterOrderByRetrievedAtAsc(any(), any());
        verify(processor, never()).processForAgents(any());
    }

    @Test
    void aSourceNamingNoInstrumentIsFinishedWithoutAnyModelCall() {
        SourceResult source = pending("Quiet day", null);
        givenPending(source);
        when(matcher.match(eq("Quiet day"), eq(null), any())).thenReturn(List.of());

        PipelineSummary summary = pipeline(true).processPending();

        assertThat(summary.noMatch()).isOne();
        assertThat(source.getProcessingOutcome()).isEqualTo("NO_MATCH");
        verify(processor, never()).processForAgents(any());
        verify(sources).save(source);
    }

    @Test
    void aMatchedSourceGoesToTheProcessorWithItsExcerptAndRelevance() {
        SourceResult source = pending("DBS earnings", "DBS reported earnings.");
        givenPending(source);
        when(matcher.match(any(), any(), any())).thenReturn(List.of(
                new InstrumentMatch("SGX-DBS", new BigDecimal("1.00"), "TICKER_AND_ISSUER")));
        when(processor.processForAgents(any())).thenReturn(accepted(2, null));

        PipelineSummary summary = pipeline(true).processPending();

        var request = org.mockito.ArgumentCaptor.forClass(SourceFanOutRequest.class);
        verify(processor).processForAgents(request.capture());
        assertThat(request.getValue().excerpt()).isEqualTo("DBS reported earnings.");
        assertThat(request.getValue().candidateRelevance()).containsEntry("SGX-DBS", new BigDecimal("1.00"));
        assertThat(summary.alertsCreated()).isEqualTo(2);
        assertThat(source.getProcessingOutcome()).isEqualTo("ALERTS_CREATED");
    }

    @Test
    void theTitleIsTheExcerptWhenTheProviderGaveNoSnippet() {
        SourceResult source = pending("DBS earnings", null);
        givenPending(source);
        when(matcher.match(any(), any(), any())).thenReturn(List.of(
                new InstrumentMatch("SGX-DBS", new BigDecimal("0.80"), "TICKER")));
        when(processor.processForAgents(any())).thenReturn(accepted(0, null));

        pipeline(true).processPending();

        var request = org.mockito.ArgumentCaptor.forClass(SourceFanOutRequest.class);
        verify(processor).processForAgents(request.capture());
        assertThat(request.getValue().excerpt()).isEqualTo("DBS earnings");
        assertThat(source.getProcessingOutcome()).isEqualTo("NO_ALERTS");
    }

    @Test
    void anAcceptedEventNobodyHoldsIsFinishedAsNoHolders() {
        SourceResult source = pending("DBS earnings", "x");
        givenPending(source);
        when(matcher.match(any(), any(), any())).thenReturn(List.of(
                new InstrumentMatch("SGX-DBS", new BigDecimal("0.80"), "TICKER")));
        when(processor.processForAgents(any())).thenReturn(new SourceFanOutOutcome(
                ClassificationStatus.MODEL_ACCEPTED, null, "NO_HOLDERS", 0, 0, 0, 0, 0, 0));

        pipeline(true).processPending();

        assertThat(source.getProcessingOutcome()).isEqualTo("NO_HOLDERS");
    }

    @Test
    void aDefiniteModelRefusalFinishesTheSource() {
        SourceResult source = pending("DBS earnings", "x");
        givenPending(source);
        when(matcher.match(any(), any(), any())).thenReturn(List.of(
                new InstrumentMatch("SGX-DBS", new BigDecimal("0.80"), "TICKER")));
        when(processor.processForAgents(any())).thenReturn(
                SourceFanOutOutcome.notAccepted(ClassificationStatus.ABSTAINED, "MODEL_ABSTAINED"));

        PipelineSummary summary = pipeline(true).processPending();

        assertThat(summary.processed()).isOne();
        assertThat(source.getProcessingOutcome()).isEqualTo("ABSTAINED");
    }

    @Test
    void whenTheModelIsUnavailableTheSourceStaysPendingAndThePassStops() {
        SourceResult first = pending("one", "x");
        SourceResult second = pending("two", "y");
        givenPending(first, second);
        when(matcher.match(any(), any(), any())).thenReturn(List.of(
                new InstrumentMatch("SGX-DBS", new BigDecimal("0.80"), "TICKER")));
        when(processor.processForAgents(any())).thenReturn(
                SourceFanOutOutcome.notAccepted(ClassificationStatus.ABSTAINED, "MODEL_TOKEN_QUOTA_REACHED"));

        PipelineSummary summary = pipeline(true).processPending();

        assertThat(summary.deferred()).isOne();
        assertThat(first.getProcessedAt()).isNull();
        assertThat(second.getProcessedAt()).isNull();
        verify(processor).processForAgents(any()); // the second source was never tried
    }

    @Test
    void oneFailingSourceIsCountedAndTheNextOneStillRuns() {
        SourceResult broken = pending("broken", "x");
        SourceResult fine = pending("fine", "y");
        givenPending(broken, fine);
        when(matcher.match(any(), any(), any())).thenReturn(List.of(
                new InstrumentMatch("SGX-DBS", new BigDecimal("0.80"), "TICKER")));
        when(processor.processForAgents(any()))
                .thenThrow(new IllegalStateException("audit outbox is at capacity"))
                .thenReturn(accepted(1, null));

        PipelineSummary summary = pipeline(true).processPending();

        assertThat(summary.errors()).isOne();
        assertThat(summary.processed()).isOne();
        assertThat(broken.getProcessedAt()).isNull();
        assertThat(fine.getProcessingOutcome()).isEqualTo("ALERTS_CREATED");
    }
}
