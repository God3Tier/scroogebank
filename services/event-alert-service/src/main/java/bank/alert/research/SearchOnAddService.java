package bank.alert.research;

import bank.alert.config.SearchOnAddProperties;
import bank.alert.domain.SourceSearchRequest;
import bank.alert.repository.SourceSearchRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Asks for one targeted search of some companies, either because an agent added a website or because
 * someone pressed Search now (ADR-0081). A company searched this way within the cooldown is skipped.
 * It never throws: a website is already saved when this runs, and the search is a bonus.
 */
@Service
public class SearchOnAddService {

    public enum Outcome { STARTED, COOLDOWN, DAILY_LIMIT, OFF, FAILED, NOTHING_TO_SEARCH }

    public enum Kind { ADD, MANUAL }

    /** A company that was not searched, and when it can be searched again. */
    public record Skipped(String instrumentId, Instant availableAt) {}

    public record Result(Outcome outcome, List<String> started, List<Skipped> cooldown, long lookbackDays) {}

    private static final Logger log = LoggerFactory.getLogger(SearchOnAddService.class);
    private static final String STARTED = "STARTED";

    private final SearchOnAddProperties properties;
    private final ObjectProvider<SearchOnAddTrigger> trigger;
    private final SourceSearchRequestRepository requests;
    private final Clock clock;

    public SearchOnAddService(SearchOnAddProperties properties, ObjectProvider<SearchOnAddTrigger> trigger,
            SourceSearchRequestRepository requests, Clock clock) {
        this.properties = properties;
        this.trigger = trigger;
        this.requests = requests;
        this.clock = clock;
    }

    /** How far back the started search looks, in whole days (for the message shown to the agent). */
    public long lookbackDays() {
        return Math.max(1, properties.lookback().toDays());
    }

    /** Searches these companies using only the website that was just added. */
    public Result afterAdd(String domain, List<String> instrumentIds, String actorSubject, UUID correlationId) {
        return request(Kind.ADD, domain, instrumentIds, actorSubject, correlationId);
    }

    /** Searches these companies using every website. */
    public Result manual(List<String> instrumentIds, String actorSubject, UUID correlationId) {
        return request(Kind.MANUAL, null, instrumentIds, actorSubject, correlationId);
    }

    private Result request(Kind kind, String domain, List<String> instrumentIds, String actorSubject, UUID correlationId) {
        SearchOnAddTrigger starter = trigger.getIfAvailable();
        if (!properties.enabled() || starter == null) return result(Outcome.OFF, List.of(), List.of());
        if (instrumentIds.isEmpty()) return result(Outcome.NOTHING_TO_SEARCH, List.of(), List.of());
        try {
            Instant now = clock.instant();
            List<String> toSearch = new ArrayList<>();
            List<Skipped> waiting = new ArrayList<>();
            for (String instrumentId : instrumentIds) {
                Instant availableAt = requests.findFirstByInstrumentIdAndStatusOrderByRequestedAtDesc(instrumentId, STARTED)
                        .map(last -> last.getRequestedAt().plus(properties.cooldown())).orElse(null);
                if (availableAt != null && availableAt.isAfter(now)) waiting.add(new Skipped(instrumentId, availableAt));
                else toSearch.add(instrumentId);
            }
            if (toSearch.isEmpty()) {
                waiting.forEach(skipped -> record(skipped.instrumentId(), domain, kind, actorSubject, now, "COOLDOWN", null));
                return result(Outcome.COOLDOWN, List.of(), waiting);
            }
            if (properties.dailyCap() > 0 && requests.countByStatusAndRequestedAtAfter(STARTED, now.minus(Duration.ofHours(24)))
                    + toSearch.size() > properties.dailyCap()) {
                toSearch.forEach(id -> record(id, domain, kind, actorSubject, now, "DAILY_LIMIT", null));
                return result(Outcome.DAILY_LIMIT, List.of(), waiting);
            }
            // Recorded first, so a second press a moment later already sees the cooldown.
            List<SourceSearchRequest> recorded = toSearch.stream()
                    .map(id -> record(id, domain, kind, actorSubject, now, STARTED, null)).toList();
            try {
                starter.start(new SearchOnAddTrigger.ResearchRequest(
                        List.copyOf(toSearch), domain, properties.lookback(), correlationId.toString()));
                return result(Outcome.STARTED, toSearch, waiting);
            } catch (RuntimeException cannotStart) {
                // A search that did not start must not hold the cooldown.
                requests.deleteAll(recorded);
                log.warn("search could not be started error_code={}", cannotStart.getClass().getSimpleName());
                toSearch.forEach(id -> record(id, domain, kind, actorSubject, now, "FAILED", cannotStart.getClass().getSimpleName()));
                return result(Outcome.FAILED, List.of(), waiting);
            }
        } catch (RuntimeException unavailable) {
            log.warn("search unavailable error_code={}", unavailable.getClass().getSimpleName());
            return result(Outcome.FAILED, List.of(), List.of());
        }
    }

    private Result result(Outcome outcome, List<String> started, List<Skipped> cooldown) {
        return new Result(outcome, List.copyOf(started), List.copyOf(cooldown), lookbackDays());
    }

    private SourceSearchRequest record(String instrumentId, String domain, Kind kind, String actorSubject,
            Instant at, String status, String detail) {
        return requests.saveAndFlush(new SourceSearchRequest(UUID.randomUUID(), instrumentId, domain, kind.name(),
                actorSubject, at, status, detail));
    }
}
