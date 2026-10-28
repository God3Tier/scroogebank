package bank.alert.ingestion;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.InstrumentSearchCoverage;
import bank.alert.operations.OperationalMetrics;
import bank.alert.repository.InstrumentSearchCoverageRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstrumentCoverageServiceTest {
    private static final Instant TO = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void selectsAtMostThirtyTwoAndResumesFromOldestCoverageWithOverlap() {
        InstrumentSearchCoverageRepository repository = mock(InstrumentSearchCoverageRepository.class);
        List<String> ids = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> "I%02d".formatted(i)).toList();
        List<String> selected = ids.subList(32, 40); // slot 124527 selects batch 1 of 2
        when(repository.findAllById(selected)).thenReturn(List.of(
                new InstrumentSearchCoverage("I32", TO.minus(Duration.ofHours(3)), TO)));
        InstrumentCoverageService service = service(repository);

        InstrumentCoverageService.CoverageBatch batch = service.scheduled(ids, TO);

        assertThat(batch.instrumentIds()).containsExactlyElementsOf(selected);
        assertThat(batch.windowFrom()).isEqualTo(TO.minus(Duration.ofHours(3)).minus(Duration.ofMinutes(5)));
    }

    @Test
    void skipsAnAlreadyCoveredBatchAndReplayNeverWritesCoverage() {
        InstrumentSearchCoverageRepository repository = mock(InstrumentSearchCoverageRepository.class);
        when(repository.findAllById(List.of("I1"))).thenReturn(List.of(
                new InstrumentSearchCoverage("I1", TO, TO)));
        InstrumentCoverageService service = service(repository);

        assertThat(service.scheduled(List.of("I1"), TO).instrumentIds()).isEmpty();
        assertThat(service.replay(List.of("I1"), TO)).containsExactly("I1");
        verify(repository).findAllById(List.of("I1"));
    }

    @Test
    void rotatesOneHundredEligibleInstrumentsAcrossFourSlots() {
        InstrumentCoverageService service = service(mock(InstrumentSearchCoverageRepository.class));
        List<String> ids = IntStream.range(0, 100).mapToObj(i -> "I%03d".formatted(i)).toList();

        List<List<String>> batches = IntStream.range(0, 4)
                .mapToObj(slot -> service.replay(ids, TO.plus(Duration.ofHours(4L * slot))))
                .toList();

        assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(32));
        assertThat(batches.stream().flatMap(List::stream).toList())
                .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(service.replay(ids, TO)).isEqualTo(batches.getFirst());
    }

    private static InstrumentCoverageService service(InstrumentSearchCoverageRepository repository) {
        return new InstrumentCoverageService(repository, new IngestionProperties(true, "tavily",
                Duration.ofMinutes(5), Duration.ofHours(4), 4, 10, 3, Duration.ofDays(90),
                new BigDecimal("0.20"), 2, new BigDecimal("0.50"), List.of("sgx.com"),
                List.of("earnings")), Clock.fixed(TO, ZoneOffset.UTC), mock(OperationalMetrics.class));
    }
}
