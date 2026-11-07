package bank.alert.service;

import bank.alert.config.IngestionProperties;
import bank.alert.domain.AuditOutbox;
import bank.alert.domain.SearchWebsite;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.repository.SearchWebsiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one shared list of websites that is searched for every company (ADR-0082). Any agent can add
 * one and an agent or an admin can remove one; each change is audited in the same transaction.
 */
@Service
public class SearchWebsiteService {

    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "FAILED");

    public record Website(UUID id, String domain, Instant addedAt, boolean addedByYou) {}

    public record WebsiteList(List<String> shared, List<Website> added, boolean canAdd, int max) {}

    private final SearchWebsiteRepository websites;
    private final AuditOutboxRepository outbox;
    private final SourceDomainPolicy policy;
    private final IngestionProperties ingestion;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxPendingAuditEvents;

    public SearchWebsiteService(SearchWebsiteRepository websites, AuditOutboxRepository outbox,
            SourceDomainPolicy policy, IngestionProperties ingestion, ObjectMapper objectMapper, Clock clock,
            @Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents) {
        this.websites = websites;
        this.outbox = outbox;
        this.policy = policy;
        this.ingestion = ingestion;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    @Transactional(readOnly = true)
    public WebsiteList list(String actorSubject, boolean admin) {
        List<Website> added = websites.findByActiveTrueOrderByAddedAtAsc().stream()
                .map(site -> new Website(site.getId(), site.getDomain(), site.getAddedAt(),
                        site.getAddedBySubject().equals(actorSubject)))
                .toList();
        return new WebsiteList(ingestion.approvedDomains(), added, !admin, SourceDomainPolicy.MAX_WEBSITES);
    }

    @Transactional
    public Website add(String actorSubject, UUID correlationId, String rawDomain) {
        String domain = policy.normalise(rawDomain);
        List<String> existing = new ArrayList<>(ingestion.approvedDomains());
        websites.findByActiveTrueOrderByAddedAtAsc().forEach(site -> existing.add(site.getDomain()));
        String covering = SourceDomainPolicy.coveredBy(domain, existing);
        if (covering != null) {
            throw SourceDomainException.conflict("ALREADY_COVERED",
                    domain.equals(covering) ? domain + " is already searched"
                            : domain + " is already covered by " + covering);
        }
        if (websites.countByActiveTrue() >= SourceDomainPolicy.MAX_WEBSITES) {
            throw SourceDomainException.conflict("LIMIT_REACHED", "at most "
                    + SourceDomainPolicy.MAX_WEBSITES + " websites can be added; remove one first");
        }

        ensureAuditCapacity();
        Instant now = clock.instant();
        SearchWebsite entry = new SearchWebsite(UUID.randomUUID(), domain, actorSubject, now);
        try {
            websites.saveAndFlush(entry);
        } catch (DataIntegrityViolationException duplicate) {
            throw SourceDomainException.conflict("ALREADY_COVERED", domain + " is already searched");
        }
        persistOutbox("alert.search-website-added", payload(entry, actorSubject, "AGENT", correlationId, now), now);
        return new Website(entry.getId(), domain, now, true);
    }

    @Transactional
    public void remove(UUID websiteId, String actorSubject, boolean admin, UUID correlationId) {
        SearchWebsite entry = websites.findByIdAndActiveTrue(websiteId).orElseThrow(SourceDomainException::notFound);
        ensureAuditCapacity();
        Instant now = clock.instant();
        entry.deactivate(actorSubject, now);
        websites.saveAndFlush(entry);
        persistOutbox("alert.search-website-removed",
                payload(entry, actorSubject, admin ? "ADMIN" : "AGENT", correlationId, now), now);
    }

    private ObjectNode payload(SearchWebsite entry, String actorSubject, String actorRole, UUID correlationId, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("searchWebsiteId", entry.getId().toString());
        payload.put("domain", entry.getDomain());
        payload.put("actorSubject", actorSubject);
        payload.put("actorRole", actorRole);
        payload.put("correlationId", correlationId.toString());
        payload.put("occurredAt", at.toString());
        return payload;
    }

    private void ensureAuditCapacity() {
        try {
            if (outbox.countByStatusIn(BACKLOG_STATUSES) >= maxPendingAuditEvents) {
                throw new AuditUnavailableException("audit outbox is at capacity");
            }
        } catch (DataAccessException exception) {
            throw new AuditUnavailableException("audit outbox availability cannot be verified", exception);
        }
    }

    private void persistOutbox(String eventType, ObjectNode payload, Instant now) {
        try {
            outbox.saveAndFlush(new AuditOutbox(UUID.randomUUID(), eventType, 1, payload.toString(), now));
        } catch (DataAccessException exception) {
            throw new AuditUnavailableException("durable audit persistence is unavailable", exception);
        }
    }
}
