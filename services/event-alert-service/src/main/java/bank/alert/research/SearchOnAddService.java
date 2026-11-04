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
import java.util.UUID;

/**
 * Asks for one targeted search when an agent adds a domain (ADR-0080), unless the company was
 * searched this way recently (cooldown) or too many searches have started today (daily cap).
 * It never throws: the domain is already saved, and the search is a bonus.
 */
@Service
public class SearchOnAddService {

    public enum Outcome { STARTED, COOLDOWN, DAILY_LIMIT, OFF, FAILED }

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

    public Outcome request(String instrumentId, String domain, String actorSubject, UUID correlationId) {
        SearchOnAddTrigger starter = trigger.getIfAvailable();
        if (!properties.enabled() || starter == null) return Outcome.OFF;
        try {
            Instant now = clock.instant();
            if (requests.existsByInstrumentIdAndStatusAndRequestedAtAfter(
                    instrumentId, STARTED, now.minus(properties.cooldown()))) {
                record(instrumentId, domain, actorSubject, now, Outcome.COOLDOWN, null);
                return Outcome.COOLDOWN;
            }
            if (requests.countByStatusAndRequestedAtAfter(STARTED, now.minus(Duration.ofHours(24))) >= properties.dailyCap()) {
                record(instrumentId, domain, actorSubject, now, Outcome.DAILY_LIMIT, null);
                return Outcome.DAILY_LIMIT;
            }
            // Recorded first, so a second add a moment later already sees the cooldown.
            SourceSearchRequest started = record(instrumentId, domain, actorSubject, now, Outcome.STARTED, null);
            try {
                starter.start(new SearchOnAddTrigger.ResearchRequest(
                        instrumentId, domain, properties.lookback(), correlationId.toString()));
                return Outcome.STARTED;
            } catch (RuntimeException cannotStart) {
                // A search that did not start must not hold the cooldown.
                requests.delete(started);
                log.warn("search on add could not be started error_code={}", cannotStart.getClass().getSimpleName());
                record(instrumentId, domain, actorSubject, now, Outcome.FAILED, cannotStart.getClass().getSimpleName());
                return Outcome.FAILED;
            }
        } catch (RuntimeException unavailable) {
            log.warn("search on add unavailable error_code={}", unavailable.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    private SourceSearchRequest record(String instrumentId, String domain, String actorSubject, Instant at,
            Outcome outcome, String detail) {
        return requests.saveAndFlush(new SourceSearchRequest(UUID.randomUUID(), instrumentId, domain,
                actorSubject, at, outcome.name(), detail));
    }
}
