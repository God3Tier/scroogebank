package bank.alert.service;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.AuditOutbox;
import bank.alert.domain.SearchWebsite;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.repository.SearchWebsiteRepository;
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

class SearchWebsiteServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T04:00:00Z");
    private static final UUID CORRELATION = UUID.fromString("60000000-0000-4000-8000-000000000001");

    private final SearchWebsiteRepository websites = mock(SearchWebsiteRepository.class);
    private final AuditOutboxRepository outbox = mock(AuditOutboxRepository.class);
    private SearchWebsiteService service;

    @BeforeEach
    void setUp() {
        IngestionProperties ingestion = new IngestionProperties(true, "tavily", Duration.ofMinutes(5), Duration.ofHours(4),
                4, 10, 3, Duration.ofDays(90), new BigDecimal("0.20"), 2,
                List.of("reuters.com", "cnbc.com", "ft.com"), List.of("earnings"));
        service = new SearchWebsiteService(websites, outbox, new SourceDomainPolicy(), ingestion, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), 10_000);
        when(websites.findByActiveTrueOrderByAddedAtAsc()).thenReturn(List.of());
        when(websites.saveAndFlush(any(SearchWebsite.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static SearchWebsite entry(String domain, String addedBy) {
        return new SearchWebsite(UUID.randomUUID(), domain, addedBy, NOW);
    }

    @Test
    void listsTheSharedSitesAndWhoAddedEachWebsiteWithoutNamingOtherAgents() {
        when(websites.findByActiveTrueOrderByAddedAtAsc()).thenReturn(List.of(entry("a.example.com", "agent-a"), entry("b.example.com", "agent-b")));

        SearchWebsiteService.WebsiteList list = service.list("agent-a", false);

        assertThat(list.shared()).containsExactly("reuters.com", "cnbc.com", "ft.com");
        assertThat(list.added()).extracting(SearchWebsiteService.Website::domain).containsExactly("a.example.com", "b.example.com");
        assertThat(list.added()).extracting(SearchWebsiteService.Website::addedByYou).containsExactly(true, false);
        assertThat(list.canAdd()).isTrue();
        assertThat(list.max()).isEqualTo(SourceDomainPolicy.MAX_WEBSITES);
    }

    @Test
    void anAdminCanListButNotAdd() {
        assertThat(service.list("admin-1", true).canAdd()).isFalse();
    }

    @Test
    void addsANormalisedWebsiteAndAuditsItInTheSameTransaction() {
        SearchWebsiteService.Website added = service.add("agent-a", CORRELATION, "  WWW.BusinessTimes.com.sg ");

        assertThat(added.domain()).isEqualTo("businesstimes.com.sg");
        assertThat(added.addedByYou()).isTrue();
        ArgumentCaptor<AuditOutbox> event = ArgumentCaptor.forClass(AuditOutbox.class);
        verify(outbox).saveAndFlush(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("alert.search-website-added");
        assertThat(event.getValue().getPayload()).contains("businesstimes.com.sg").contains("agent-a").contains(CORRELATION.toString());
    }

    @Test
    void refusesASocialPlatformAndABadFormat() {
        assertThatThrownBy(() -> service.add("agent-a", CORRELATION, "reddit.com"))
                .isInstanceOfSatisfying(SourceDomainException.class, e -> assertThat(e.code()).isEqualTo("BLOCKED_DOMAIN"));
        assertThatThrownBy(() -> service.add("agent-a", CORRELATION, "https://example.com/page"))
                .isInstanceOf(SourceDomainException.class);
        verify(websites, never()).saveAndFlush(any());
    }

    @Test
    void refusesWhatTheSharedListOrAnAddedWebsiteAlreadyCovers() {
        when(websites.findByActiveTrueOrderByAddedAtAsc()).thenReturn(List.of(entry("example.com", "agent-b")));

        assertThatThrownBy(() -> service.add("agent-a", CORRELATION, "reuters.com"))
                .isInstanceOfSatisfying(SourceDomainException.class, e -> assertThat(e.code()).isEqualTo("ALREADY_COVERED"));
        assertThatThrownBy(() -> service.add("agent-a", CORRELATION, "news.example.com"))
                .isInstanceOfSatisfying(SourceDomainException.class, e -> assertThat(e.code()).isEqualTo("ALREADY_COVERED"));
    }

    @Test
    void refusesAnotherWebsiteOnceTheListIsFull() {
        when(websites.countByActiveTrue()).thenReturn((long) SourceDomainPolicy.MAX_WEBSITES);

        assertThatThrownBy(() -> service.add("agent-a", CORRELATION, "new.example.com"))
                .isInstanceOfSatisfying(SourceDomainException.class, e -> assertThat(e.code()).isEqualTo("LIMIT_REACHED"));
    }

    @Test
    void aRaceOnTheUniqueIndexIsReportedAsAlreadyCovered() {
        when(websites.saveAndFlush(any(SearchWebsite.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.add("agent-a", CORRELATION, "new.example.com"))
                .isInstanceOfSatisfying(SourceDomainException.class, e -> assertThat(e.code()).isEqualTo("ALREADY_COVERED"));
    }

    @Test
    void refusesWhenTheAuditBacklogIsFull() {
        when(outbox.countByStatusIn(any())).thenReturn(10_000L);

        assertThatThrownBy(() -> service.add("agent-a", CORRELATION, "new.example.com"))
                .isInstanceOf(AuditUnavailableException.class);
        verify(websites, never()).saveAndFlush(any());
    }

    @Test
    void anyAgentOrAnAdminCanRemoveAWebsiteAndItIsAudited() {
        SearchWebsite entry = entry("a.example.com", "agent-a");
        when(websites.findByIdAndActiveTrue(entry.getId())).thenReturn(Optional.of(entry));

        service.remove(entry.getId(), "agent-b", false, CORRELATION);

        assertThat(entry.isActive()).isFalse();
        ArgumentCaptor<AuditOutbox> event = ArgumentCaptor.forClass(AuditOutbox.class);
        verify(outbox).saveAndFlush(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("alert.search-website-removed");
        assertThat(event.getValue().getPayload()).contains("\"actorRole\":\"AGENT\"");

        SearchWebsite another = entry("b.example.com", "agent-a");
        when(websites.findByIdAndActiveTrue(another.getId())).thenReturn(Optional.of(another));
        service.remove(another.getId(), "admin-1", true, CORRELATION);
        assertThat(another.isActive()).isFalse();
    }

    @Test
    void removingAnUnknownOrAlreadyRemovedWebsiteIsNotFound() {
        when(websites.findByIdAndActiveTrue(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(UUID.randomUUID(), "agent-a", false, CORRELATION))
                .isInstanceOfSatisfying(SourceDomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
    }
}
