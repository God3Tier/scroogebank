package bank.alert.research;

import bank.alert.config.SearchOnAddProperties;
import bank.alert.domain.SourceSearchRequest;
import bank.alert.repository.SourceSearchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchOnAddServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final UUID CORRELATION = UUID.fromString("70000000-0000-4000-8000-000000000001");

    private final SourceSearchRequestRepository requests = mock(SourceSearchRequestRepository.class);
    private final SearchOnAddTrigger trigger = mock(SearchOnAddTrigger.class);

    @BeforeEach
    void saves() {
        when(requests.saveAndFlush(any(SourceSearchRequest.class))).thenAnswer(call -> call.getArgument(0));
        when(requests.findFirstByInstrumentIdAndStatusOrderByRequestedAtDesc(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private SearchOnAddService service(String mode, SearchOnAddTrigger starter, int dailyCap) {
        ObjectProvider<SearchOnAddTrigger> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(starter);
        SearchOnAddProperties properties = new SearchOnAddProperties(mode, Duration.ofMinutes(15), dailyCap,
                Duration.ofDays(14), "cluster", "family", null, List.of("subnet-1"), "sg-1", List.of());
        return new SearchOnAddService(properties, provider, requests, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SourceSearchRequest startedAt(String instrumentId, Instant at) {
        return new SourceSearchRequest(UUID.randomUUID(), instrumentId, null, "MANUAL", "agent-a", at, "STARTED", null);
    }

    @Test
    void doesNothingWhenOff() {
        SearchOnAddService.Result result = service("off", null, 0).manual(List.of("SGX-D05"), "agent-a", CORRELATION);

        assertThat(result.outcome()).isEqualTo(SearchOnAddService.Outcome.OFF);
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void doesNothingWhenNoStarterIsConfigured() {
        assertThat(service("ecs", null, 0).manual(List.of("SGX-D05"), "agent-a", CORRELATION).outcome())
                .isEqualTo(SearchOnAddService.Outcome.OFF);
    }

    @Test
    void withNoCompaniesThereIsNothingToSearch() {
        assertThat(service("ecs", trigger, 0).manual(List.of(), "agent-a", CORRELATION).outcome())
                .isEqualTo(SearchOnAddService.Outcome.NOTHING_TO_SEARCH);
        verify(trigger, never()).start(any());
    }

    @Test
    void aManualSearchStartsOneTaskForAllTheCompaniesUsingEveryWebsite() {
        SearchOnAddService.Result result = service("ecs", trigger, 0)
                .manual(List.of("SGX-D05", "SGX-O39", "NASDAQ-AAPL"), "agent-a", CORRELATION);

        assertThat(result.outcome()).isEqualTo(SearchOnAddService.Outcome.STARTED);
        assertThat(result.started()).containsExactly("SGX-D05", "SGX-O39", "NASDAQ-AAPL");
        ArgumentCaptor<SearchOnAddTrigger.ResearchRequest> sent = ArgumentCaptor.forClass(SearchOnAddTrigger.ResearchRequest.class);
        verify(trigger).start(sent.capture());
        assertThat(sent.getValue().instrumentIds()).containsExactly("SGX-D05", "SGX-O39", "NASDAQ-AAPL");
        assertThat(sent.getValue().domain()).isNull();
        assertThat(sent.getValue().lookback()).isEqualTo(Duration.ofDays(14));
        assertThat(sent.getValue().correlationId()).isEqualTo(CORRELATION.toString());
        ArgumentCaptor<SourceSearchRequest> saved = ArgumentCaptor.forClass(SourceSearchRequest.class);
        verify(requests, times(3)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(SourceSearchRequest::getStatus).containsOnly("STARTED");
        assertThat(saved.getAllValues()).extracting(SourceSearchRequest::getKind).containsOnly("MANUAL");
    }

    @Test
    void anAddedWebsiteIsSearchedAloneForTheAgentsCompanies() {
        service("ecs", trigger, 0).afterAdd("businesstimes.com.sg", List.of("SGX-D05", "SGX-O39"), "agent-a", CORRELATION);

        ArgumentCaptor<SearchOnAddTrigger.ResearchRequest> sent = ArgumentCaptor.forClass(SearchOnAddTrigger.ResearchRequest.class);
        verify(trigger).start(sent.capture());
        assertThat(sent.getValue().domain()).isEqualTo("businesstimes.com.sg");
        ArgumentCaptor<SourceSearchRequest> saved = ArgumentCaptor.forClass(SourceSearchRequest.class);
        verify(requests, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(SourceSearchRequest::getKind).containsOnly("ADD");
    }

    @Test
    void aCompanySearchedRecentlyIsSkippedAndSaysWhenItCanBeSearchedAgain() {
        when(requests.findFirstByInstrumentIdAndStatusOrderByRequestedAtDesc("SGX-D05", "STARTED"))
                .thenReturn(Optional.of(startedAt("SGX-D05", NOW.minusSeconds(300))));

        SearchOnAddService.Result result = service("ecs", trigger, 0)
                .manual(List.of("SGX-D05", "SGX-O39"), "agent-a", CORRELATION);

        assertThat(result.outcome()).isEqualTo(SearchOnAddService.Outcome.STARTED);
        assertThat(result.started()).containsExactly("SGX-O39");
        assertThat(result.cooldown()).singleElement().satisfies(skipped -> {
            assertThat(skipped.instrumentId()).isEqualTo("SGX-D05");
            assertThat(skipped.availableAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        });
        ArgumentCaptor<SearchOnAddTrigger.ResearchRequest> sent = ArgumentCaptor.forClass(SearchOnAddTrigger.ResearchRequest.class);
        verify(trigger).start(sent.capture());
        assertThat(sent.getValue().instrumentIds()).containsExactly("SGX-O39");
    }

    @Test
    void whenEveryCompanyIsInCooldownNothingStarts() {
        when(requests.findFirstByInstrumentIdAndStatusOrderByRequestedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.of(startedAt("SGX-D05", NOW.minusSeconds(60))));

        SearchOnAddService.Result result = service("ecs", trigger, 0).manual(List.of("SGX-D05"), "agent-a", CORRELATION);

        assertThat(result.outcome()).isEqualTo(SearchOnAddService.Outcome.COOLDOWN);
        verify(trigger, never()).start(any());
    }

    @Test
    void aCooldownThatHasPassedDoesNotBlock() {
        when(requests.findFirstByInstrumentIdAndStatusOrderByRequestedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.of(startedAt("SGX-D05", NOW.minus(Duration.ofMinutes(16)))));

        assertThat(service("ecs", trigger, 0).manual(List.of("SGX-D05"), "agent-a", CORRELATION).outcome())
                .isEqualTo(SearchOnAddService.Outcome.STARTED);
    }

    @Test
    void thereIsNoDailyCapByDefault() {
        when(requests.countByStatusAndRequestedAtAfter(anyString(), any())).thenReturn(10_000L);

        assertThat(service("ecs", trigger, 0).manual(List.of("SGX-D05"), "agent-a", CORRELATION).outcome())
                .isEqualTo(SearchOnAddService.Outcome.STARTED);
    }

    @Test
    void anOptionalDailyCapStopsASearchThatWouldExceedIt() {
        when(requests.countByStatusAndRequestedAtAfter(anyString(), any())).thenReturn(19L);

        SearchOnAddService.Result result = service("ecs", trigger, 20).manual(List.of("SGX-D05", "SGX-O39"), "agent-a", CORRELATION);

        assertThat(result.outcome()).isEqualTo(SearchOnAddService.Outcome.DAILY_LIMIT);
        verify(trigger, never()).start(any());
        verify(requests).countByStatusAndRequestedAtAfter("STARTED", NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void aSearchThatCannotStartIsReportedAndDoesNotHoldTheCooldown() {
        doThrow(new IllegalStateException("no capacity")).when(trigger).start(any());

        SearchOnAddService.Result result = service("ecs", trigger, 0).manual(List.of("SGX-D05"), "agent-a", CORRELATION);

        assertThat(result.outcome()).isEqualTo(SearchOnAddService.Outcome.FAILED);
        ArgumentCaptor<SourceSearchRequest> saved = ArgumentCaptor.forClass(SourceSearchRequest.class);
        verify(requests, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(SourceSearchRequest::getStatus).containsExactly("STARTED", "FAILED");
        verify(requests).deleteAll(any());
    }

    @Test
    void neverThrowsWhenTheDatabaseIsUnavailable() {
        when(requests.findFirstByInstrumentIdAndStatusOrderByRequestedAtDesc(anyString(), anyString()))
                .thenThrow(new IllegalStateException("database down"));

        assertThat(service("ecs", trigger, 0).manual(List.of("SGX-D05"), "agent-a", CORRELATION).outcome())
                .isEqualTo(SearchOnAddService.Outcome.FAILED);
        verify(trigger, never()).start(any());
    }

    @Test
    void reportsTheLookbackInWholeDays() {
        assertThat(service("ecs", trigger, 0).lookbackDays()).isEqualTo(14);
    }
}
