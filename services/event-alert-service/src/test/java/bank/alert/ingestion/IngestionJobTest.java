package bank.alert.ingestion;

import bank.alert.config.AlertMode;
import bank.alert.config.AlertModeProperties;
import bank.alert.config.IngestionProperties;
import bank.alert.domain.Instrument;
import bank.alert.domain.SearchWebsite;
import bank.alert.repository.InstrumentRepository;
import bank.alert.repository.SearchWebsiteRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionJobTest {

    private static final Instant FROM = Instant.parse("2026-10-21T04:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-04T04:00:00Z");

    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final SearchWebsiteRepository websites = mock(SearchWebsiteRepository.class);
    private final SearchUniverseProvider searchUniverse = mock(SearchUniverseProvider.class);
    private final IngestionCoordinator coordinator = mock(IngestionCoordinator.class);
    private final InstrumentCoverageService coverage = mock(InstrumentCoverageService.class);

    private IngestionJob job(AlertMode mode) {
        return new IngestionJob(new AlertModeProperties(mode), properties(), searchUniverse, instruments, websites,
                coordinator, coverage);
    }

    private static Instrument dbs() {
        Instrument instrument = mock(Instrument.class);
        when(instrument.getId()).thenReturn("SGX-D05");
        when(instrument.getTicker()).thenReturn("D05");
        when(instrument.getIssuerName()).thenReturn("DBS Group Holdings Ltd");
        when(instrument.getSector()).thenReturn("Financials");
        when(instrument.getSearchAliases()).thenReturn(List.of("DBS"));
        when(instrument.getOfficialDomains()).thenReturn(List.of("sgx.com"));
        when(instrument.isActive()).thenReturn(true);
        return instrument;
    }

    private static SearchWebsite website(String domain) {
        return new SearchWebsite(UUID.randomUUID(), domain, "agent-a", FROM);
    }

    @Test
    void refusesToRunInsideTheApiProcess() {
        assertThatThrownBy(() -> job(AlertMode.API).runScheduledWindow(Instant.parse("2026-09-28T04:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKER mode");
        verify(searchUniverse, never()).eligibleInstrumentIds();
    }

    @Test
    void searchesNewlyRegisteredInstrumentAndSkipsUnknownOrInactiveHoldings() {
        Instrument instrument = mock(Instrument.class);
        when(instrument.getTicker()).thenReturn("7203");
        when(instrument.getIssuerName()).thenReturn("Toyota Motor Corporation");
        when(instrument.getSector()).thenReturn("Automotive");
        when(instrument.getSearchAliases()).thenReturn(List.of("Toyota"));
        when(instrument.getOfficialDomains()).thenReturn(List.of("global.toyota", "jpx.co.jp"));
        when(instrument.getId()).thenReturn("TSE-7203");
        when(instrument.isActive()).thenReturn(true);
        Instrument inactive = mock(Instrument.class);
        when(inactive.getId()).thenReturn("INACTIVE");
        when(inactive.isActive()).thenReturn(false);
        when(searchUniverse.eligibleInstrumentIds()).thenReturn(List.of("TSE-7203", "INACTIVE", "UNKNOWN"));
        when(instruments.findAllById(any())).thenReturn(List.of(inactive, instrument));
        when(coverage.replay(any(), eq(TO))).thenReturn(List.of("TSE-7203"));

        job(AlertMode.WORKER).replayWindow(FROM, TO);

        verify(coordinator).replay(eq(FROM), eq(TO), argThat(terms ->
                terms.size() == 1 && terms.getFirst().ticker().equals("7203")
                        && terms.getFirst().aliases().equals(List.of("Toyota"))
                        && terms.getFirst().officialDomains().equals(List.of("global.toyota", "jpx.co.jp"))));
    }

    @Test
    void everyCompanyIsSearchedOnTheWebsitesAgentsAdded() {
        when(searchUniverse.eligibleInstrumentIds()).thenReturn(List.of("SGX-D05"));
        Instrument dbs = dbs();
        when(instruments.findAllById(any())).thenReturn(List.of(dbs));
        when(websites.findByActiveTrueOrderByAddedAtAsc()).thenReturn(List.of(website("businesstimes.com.sg")));
        when(coverage.replay(any(), eq(TO))).thenReturn(List.of("SGX-D05"));

        job(AlertMode.WORKER).replayWindow(FROM, TO);

        verify(coordinator).replay(eq(FROM), eq(TO), argThat(terms -> terms.size() == 1
                && terms.getFirst().officialDomains().equals(List.of("sgx.com"))
                && terms.getFirst().customDomains().equals(List.of("businesstimes.com.sg"))));
    }

    @Test
    void researchWithAWebsiteSearchesOnlyThatWebsiteForEachCompany() {
        Instrument dbs = dbs();
        when(instruments.findAllById(any())).thenReturn(List.of(dbs));
        when(websites.findByActiveTrueOrderByAddedAtAsc())
                .thenReturn(List.of(website("businesstimes.com.sg"), website("theedge.example.com")));

        job(AlertMode.WORKER).researchCompanies(List.of("SGX-D05"), "businesstimes.com.sg", FROM, TO);

        verify(coordinator).research(eq(FROM), eq(TO), argThat(terms -> terms.size() == 1
                && terms.getFirst().officialDomains().isEmpty()
                && terms.getFirst().customDomains().equals(List.of("businesstimes.com.sg"))), eq(List.of()));
    }

    @Test
    void researchWithoutAWebsiteSearchesEveryWebsite() {
        Instrument dbs = dbs();
        when(instruments.findAllById(any())).thenReturn(List.of(dbs));
        when(websites.findByActiveTrueOrderByAddedAtAsc()).thenReturn(List.of(website("businesstimes.com.sg")));

        job(AlertMode.WORKER).researchCompanies(List.of("SGX-D05"), null, FROM, TO);

        verify(coordinator).research(eq(FROM), eq(TO), argThat(terms -> terms.size() == 1
                && terms.getFirst().officialDomains().equals(List.of("sgx.com"))
                && terms.getFirst().customDomains().equals(List.of("businesstimes.com.sg"))), eq(List.of("sgx.com")));
    }

    @Test
    void researchSearchesNothingForAWebsiteThatIsNotAnActiveAddedWebsite() {
        Instrument dbs = dbs();
        when(instruments.findAllById(any())).thenReturn(List.of(dbs));
        when(websites.findByActiveTrueOrderByAddedAtAsc()).thenReturn(List.of());

        IngestionOutcome outcome = job(AlertMode.WORKER).researchCompanies(List.of("SGX-D05"), "random.example.com", FROM, TO);

        assertThat(outcome.status()).isEqualTo(IngestionStatus.NO_QUERIES);
        verify(coordinator, never()).research(any(), any(), any(), any());
    }

    @Test
    void researchSkipsCompaniesThatAreInactive() {
        Instrument inactive = mock(Instrument.class);
        when(inactive.isActive()).thenReturn(false);
        when(instruments.findAllById(any())).thenReturn(List.of(inactive));
        when(websites.findByActiveTrueOrderByAddedAtAsc()).thenReturn(List.of());

        IngestionOutcome outcome = job(AlertMode.WORKER).researchCompanies(List.of("OLD"), null, FROM, TO);

        assertThat(outcome.status()).isEqualTo(IngestionStatus.NO_QUERIES);
        verify(coordinator, never()).research(any(), any(), any(), any());
    }

    @Test
    void researchRefusesToRunInsideTheApiProcess() {
        assertThatThrownBy(() -> job(AlertMode.API).researchCompanies(List.of("SGX-D05"), null, FROM, TO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WORKER mode");
    }

    private static IngestionProperties properties() {
        return new IngestionProperties(false, "tavily", Duration.ofMinutes(5),
                Duration.ofHours(4), 4, 10, 3, Duration.ofDays(90), new BigDecimal("0.20"), 2,
                new BigDecimal("0.50"),
                List.of("sgx.com"), List.of("earnings"));
    }
}
