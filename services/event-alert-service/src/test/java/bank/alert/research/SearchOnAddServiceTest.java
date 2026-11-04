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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    }

    @SuppressWarnings("unchecked")
    private SearchOnAddService service(String mode, SearchOnAddTrigger starter, int dailyCap) {
        ObjectProvider<SearchOnAddTrigger> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(starter);
        SearchOnAddProperties properties = new SearchOnAddProperties(mode, Duration.ofMinutes(15), dailyCap,
                Duration.ofDays(14), "cluster", "family", null, List.of("subnet-1"), "sg-1", List.of());
        return new SearchOnAddService(properties, provider, requests, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SearchOnAddService.Outcome add(SearchOnAddService service) {
        return service.request("SGX-D05", "businesstimes.com.sg", "agent-a", CORRELATION);
    }

    @Test
    void doesNothingWhenOff() {
        assertThat(add(service("off", null, 20))).isEqualTo(SearchOnAddService.Outcome.OFF);
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void doesNothingWhenNoStarterIsConfigured() {
        assertThat(add(service("ecs", null, 20))).isEqualTo(SearchOnAddService.Outcome.OFF);
    }

    @Test
    void startsOneSearchWithTheConfiguredLookbackAndRecordsIt() {
        assertThat(add(service("ecs", trigger, 20))).isEqualTo(SearchOnAddService.Outcome.STARTED);

        ArgumentCaptor<SearchOnAddTrigger.ResearchRequest> sent = ArgumentCaptor.forClass(SearchOnAddTrigger.ResearchRequest.class);
        verify(trigger).start(sent.capture());
        assertThat(sent.getValue().instrumentId()).isEqualTo("SGX-D05");
        assertThat(sent.getValue().domain()).isEqualTo("businesstimes.com.sg");
        assertThat(sent.getValue().lookback()).isEqualTo(Duration.ofDays(14));
        assertThat(sent.getValue().correlationId()).isEqualTo(CORRELATION.toString());
        ArgumentCaptor<SourceSearchRequest> saved = ArgumentCaptor.forClass(SourceSearchRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("STARTED");
        assertThat(saved.getValue().getRequestedBySubject()).isEqualTo("agent-a");
    }

    @Test
    void aCompanySearchedRecentlyIsInCooldown() {
        when(requests.existsByInstrumentIdAndStatusAndRequestedAtAfter(anyString(), anyString(), any())).thenReturn(true);

        assertThat(add(service("ecs", trigger, 20))).isEqualTo(SearchOnAddService.Outcome.COOLDOWN);

        verify(trigger, never()).start(any());
        ArgumentCaptor<SourceSearchRequest> saved = ArgumentCaptor.forClass(SourceSearchRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("COOLDOWN");
    }

    @Test
    void theCooldownLooksBackByTheConfiguredPeriod() {
        add(service("ecs", trigger, 20));

        verify(requests).existsByInstrumentIdAndStatusAndRequestedAtAfter("SGX-D05", "STARTED", NOW.minus(Duration.ofMinutes(15)));
    }

    @Test
    void stopsAtTheDailyCap() {
        when(requests.countByStatusAndRequestedAtAfter(anyString(), any())).thenReturn(2L);

        assertThat(add(service("ecs", trigger, 2))).isEqualTo(SearchOnAddService.Outcome.DAILY_LIMIT);

        verify(trigger, never()).start(any());
        verify(requests).countByStatusAndRequestedAtAfter("STARTED", NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void aSearchThatCannotStartIsReportedAndDoesNotHoldTheCooldown() {
        doThrow(new IllegalStateException("no capacity")).when(trigger).start(any());

        assertThat(add(service("ecs", trigger, 20))).isEqualTo(SearchOnAddService.Outcome.FAILED);

        ArgumentCaptor<SourceSearchRequest> saved = ArgumentCaptor.forClass(SourceSearchRequest.class);
        verify(requests, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(SourceSearchRequest::getStatus).containsExactly("STARTED", "FAILED");
        verify(requests).delete(saved.getAllValues().get(0));
    }

    @Test
    void neverThrowsWhenTheDatabaseIsUnavailable() {
        when(requests.existsByInstrumentIdAndStatusAndRequestedAtAfter(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("database down"));

        assertThat(add(service("ecs", trigger, 20))).isEqualTo(SearchOnAddService.Outcome.FAILED);
        verify(trigger, never()).start(any());
    }

    @Test
    void reportsTheLookbackInWholeDays() {
        assertThat(service("ecs", trigger, 20).lookbackDays()).isEqualTo(14);
    }
}
