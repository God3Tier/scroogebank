package bank.alert.service;

import bank.alert.config.IngestionProperties;
import bank.alert.crm.CurrentOwnership;
import bank.alert.domain.AuditOutbox;
import bank.alert.domain.Instrument;
import bank.alert.domain.InstrumentSourceDomain;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.repository.InstrumentRepository;
import bank.alert.repository.InstrumentSourceDomainRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchSourceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T04:00:00Z");
    private static final UUID CORRELATION = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private InstrumentRepository instruments;
    private InstrumentSourceDomainRepository domains;
    private AuditOutboxRepository outbox;
    private AgentHeldInstruments held;
    private CurrentOwnership ownership;
    private SearchSourceService service;
    private Instrument dbs;

    @BeforeEach
    void setUp() {
        instruments = mock(InstrumentRepository.class);
        domains = mock(InstrumentSourceDomainRepository.class);
        outbox = mock(AuditOutboxRepository.class);
        held = mock(AgentHeldInstruments.class);
        ownership = mock(CurrentOwnership.class);
        dbs = mock(Instrument.class);
        when(dbs.getId()).thenReturn("SGX-D05");
        when(dbs.getTicker()).thenReturn("D05");
        when(dbs.getIssuerName()).thenReturn("DBS Group Holdings Ltd");
        when(dbs.isActive()).thenReturn(true);
        when(dbs.getOfficialDomains()).thenReturn(List.of("sgx.com", "dbs.com"));
        when(instruments.findById("SGX-D05")).thenReturn(Optional.of(dbs));
        when(instruments.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(dbs));
        when(held.holds("agent-a", "SGX-D05")).thenReturn(true);
        when(held.idsFor("agent-a")).thenReturn(List.of("SGX-D05"));
        when(domains.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(outbox.countByStatusIn(any())).thenReturn(0L);
        IngestionProperties properties = new IngestionProperties(false, "tavily", Duration.ofMinutes(5),
                Duration.ofHours(4), 4, 10, 3, Duration.ofDays(90), new BigDecimal("0.20"), 2,
                new BigDecimal("0.50"), List.of("reuters.com", "cnbc.com", "ft.com"), List.of("earnings"));
        service = new SearchSourceService(instruments, domains, outbox, held, ownership, new SourceDomainPolicy(),
                properties, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), 10_000);
    }

    @Test
    void anAgentAddsADomainToACompanyTheirClientHoldsAndItIsAudited() {
        SearchSourceService.CustomDomain added = service.add("SGX-D05", "agent-a", CORRELATION, " WWW.BusinessTimes.com.sg ");

        assertThat(added.domain()).isEqualTo("businesstimes.com.sg");
        assertThat(added.addedByYou()).isTrue();
        verify(ownership).refresh();
        ArgumentCaptor<AuditOutbox> event = ArgumentCaptor.forClass(AuditOutbox.class);
        verify(outbox).saveAndFlush(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("alert.search-domain-added");
        assertThat(event.getValue().getPayload()).contains("\"domain\":\"businesstimes.com.sg\"",
                "\"instrumentId\":\"SGX-D05\"", "\"actorSubject\":\"agent-a\"", "\"actorRole\":\"AGENT\"");
    }

    @Test
    void anAgentCannotAddToACompanyNoClientOfTheirsHolds() {
        assertThatThrownBy(() -> service.add("SGX-D05", "agent-b", CORRELATION, "example.com"))
                .isInstanceOfSatisfying(SourceDomainException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("NOT_HELD");
                });
        verify(domains, never()).saveAndFlush(any());
        verify(outbox, never()).saveAndFlush(any());
    }

    @Test
    void anUnknownCompanyIsNotFound() {
        assertThatThrownBy(() -> service.add("NOPE", "agent-a", CORRELATION, "example.com"))
                .isInstanceOfSatisfying(SourceDomainException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(404));
    }

    @Test
    void rejectsADomainTheCompanyAlreadySearchesOrThatIsAlreadyCovered() {
        for (String duplicate : List.of("dbs.com", "news.dbs.com", "reuters.com", "uk.reuters.com")) {
            assertThatThrownBy(() -> service.add("SGX-D05", "agent-a", CORRELATION, duplicate))
                    .isInstanceOfSatisfying(SourceDomainException.class, exception -> {
                        assertThat(exception.status().value()).isEqualTo(409);
                        assertThat(exception.code()).isEqualTo("ALREADY_COVERED");
                    });
        }
        InstrumentSourceDomain existing = new InstrumentSourceDomain(UUID.randomUUID(), "SGX-D05", "example.com", "agent-b", NOW);
        when(domains.findByInstrumentIdAndActiveTrueOrderByAddedAtAsc("SGX-D05")).thenReturn(List.of(existing));
        assertThatThrownBy(() -> service.add("SGX-D05", "agent-a", CORRELATION, "www.example.com"))
                .isInstanceOfSatisfying(SourceDomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ALREADY_COVERED"));
        verify(outbox, never()).saveAndFlush(any());
    }

    @Test
    void stopsAtTheLimitOfActiveDomains() {
        when(domains.countByInstrumentIdAndActiveTrue("SGX-D05"))
                .thenReturn((long) SourceDomainPolicy.MAX_CUSTOM_DOMAINS_PER_COMPANY);

        assertThatThrownBy(() -> service.add("SGX-D05", "agent-a", CORRELATION, "example.com"))
                .isInstanceOfSatisfying(SourceDomainException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("LIMIT_REACHED");
                });
    }

    @Test
    void aRaceOnTheUniqueIndexIsReportedAsAlreadyCovered() {
        when(domains.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.add("SGX-D05", "agent-a", CORRELATION, "example.com"))
                .isInstanceOfSatisfying(SourceDomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ALREADY_COVERED"));
        verify(outbox, never()).saveAndFlush(any());
    }

    @Test
    void refusesToChangeAnythingWhenTheAuditBacklogIsFull() {
        when(outbox.countByStatusIn(any())).thenReturn(10_000L);

        assertThatThrownBy(() -> service.add("SGX-D05", "agent-a", CORRELATION, "example.com"))
                .isInstanceOf(AuditUnavailableException.class);
        verify(domains, never()).saveAndFlush(any());
    }

    @Test
    void anyHoldingAgentOrAnAdminDeactivatesAndItIsAudited() {
        InstrumentSourceDomain entry = new InstrumentSourceDomain(UUID.randomUUID(), "SGX-D05", "example.com", "agent-b", NOW);
        when(domains.findByIdAndInstrumentIdAndActiveTrue(entry.getId(), "SGX-D05")).thenReturn(Optional.of(entry));

        service.deactivate("SGX-D05", entry.getId(), "agent-a", false, CORRELATION);

        assertThat(entry.isActive()).isFalse();
        assertThat(entry.getDeactivatedBySubject()).isEqualTo("agent-a");
        ArgumentCaptor<AuditOutbox> event = ArgumentCaptor.forClass(AuditOutbox.class);
        verify(outbox).saveAndFlush(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("alert.search-domain-deactivated");
        assertThat(event.getValue().getPayload()).contains("\"actorRole\":\"AGENT\"");

        InstrumentSourceDomain second = new InstrumentSourceDomain(UUID.randomUUID(), "SGX-D05", "other.com", "agent-a", NOW);
        when(domains.findByIdAndInstrumentIdAndActiveTrue(second.getId(), "SGX-D05")).thenReturn(Optional.of(second));
        service.deactivate("SGX-D05", second.getId(), "admin-1", true, CORRELATION);
        assertThat(second.isActive()).isFalse();
    }

    @Test
    void aNonHoldingAgentCannotDeactivate() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.deactivate("SGX-D05", id, "agent-b", false, CORRELATION))
                .isInstanceOfSatisfying(SourceDomainException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(404));
        verify(domains, never()).saveAndFlush(any());
    }

    @Test
    void anAgentSeesOnlyHeldCompaniesAndWhichDomainsAreTheirs() {
        InstrumentSourceDomain mine = new InstrumentSourceDomain(UUID.randomUUID(), "SGX-D05", "mine.com", "agent-a", NOW);
        InstrumentSourceDomain theirs = new InstrumentSourceDomain(UUID.randomUUID(), "SGX-D05", "theirs.com", "agent-b", NOW);
        when(domains.findByInstrumentIdInAndActiveTrueOrderByAddedAtAsc(any())).thenReturn(List.of(mine, theirs));

        List<SearchSourceService.CompanySources> sources = service.list("agent-a", false);

        assertThat(sources).singleElement().satisfies(company -> {
            assertThat(company.instrumentId()).isEqualTo("SGX-D05");
            assertThat(company.official()).containsExactly("sgx.com", "dbs.com");
            assertThat(company.shared()).containsExactly("reuters.com", "cnbc.com", "ft.com");
            assertThat(company.canAdd()).isTrue();
            assertThat(company.custom()).extracting(SearchSourceService.CustomDomain::domain, SearchSourceService.CustomDomain::addedByYou)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("mine.com", true),
                            org.assertj.core.groups.Tuple.tuple("theirs.com", false));
        });
        assertThat(service.list("agent-b", false)).isEmpty();
    }

    @Test
    void anAdminSeesEveryCompanyButCannotAdd() {
        when(domains.findByInstrumentIdInAndActiveTrueOrderByAddedAtAsc(any())).thenReturn(List.of());

        assertThat(service.list("admin-1", true)).singleElement()
                .satisfies(company -> assertThat(company.canAdd()).isFalse());
        verify(ownership, never()).refresh();
    }
}
