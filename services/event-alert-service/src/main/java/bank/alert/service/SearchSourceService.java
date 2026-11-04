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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lists, adds and deactivates the search domains agents add per company (ADR-0079). Agents
 * act only on companies their clients currently hold; admins can list and deactivate.
 */
@Service
public class SearchSourceService {

    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "FAILED");

    public record CustomDomain(UUID id, String domain, Instant addedAt, boolean addedByYou) {}

    public record CompanySources(String instrumentId, String ticker, String issuerName,
            List<String> official, List<String> shared, List<CustomDomain> custom, boolean canAdd,
            int maxCustom) {}

    private final InstrumentRepository instruments;
    private final InstrumentSourceDomainRepository domains;
    private final AuditOutboxRepository outbox;
    private final AgentHeldInstruments held;
    private final CurrentOwnership currentOwnership;
    private final SourceDomainPolicy policy;
    private final IngestionProperties ingestion;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxPendingAuditEvents;

    public SearchSourceService(InstrumentRepository instruments, InstrumentSourceDomainRepository domains,
            AuditOutboxRepository outbox, AgentHeldInstruments held, CurrentOwnership currentOwnership,
            SourceDomainPolicy policy, IngestionProperties ingestion, ObjectMapper objectMapper, Clock clock,
            @Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents) {
        this.instruments = instruments;
        this.domains = domains;
        this.outbox = outbox;
        this.held = held;
        this.currentOwnership = currentOwnership;
        this.policy = policy;
        this.ingestion = ingestion;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    @Transactional(readOnly = true)
    public List<CompanySources> list(String actorSubject, boolean admin) {
        List<Instrument> companies = instruments.findByActiveTrueOrderByIdAsc();
        if (!admin) {
            currentOwnership.refresh();
            Set<String> heldIds = Set.copyOf(held.idsFor(actorSubject));
            companies = companies.stream().filter(instrument -> heldIds.contains(instrument.getId())).toList();
        }
        Map<String, List<InstrumentSourceDomain>> custom = companies.isEmpty() ? Map.of()
                : domains.findByInstrumentIdInAndActiveTrueOrderByAddedAtAsc(
                        companies.stream().map(Instrument::getId).toList()).stream()
                        .collect(Collectors.groupingBy(InstrumentSourceDomain::getInstrumentId));
        List<CompanySources> result = new ArrayList<>();
        for (Instrument company : companies) {
            List<CustomDomain> entries = custom.getOrDefault(company.getId(), List.of()).stream()
                    .map(entry -> new CustomDomain(entry.getId(), entry.getDomain(), entry.getAddedAt(),
                            entry.getAddedBySubject().equals(actorSubject)))
                    .toList();
            result.add(new CompanySources(company.getId(), company.getTicker(), company.getIssuerName(),
                    company.getOfficialDomains(), ingestion.approvedDomains(), entries, !admin,
                    SourceDomainPolicy.MAX_CUSTOM_DOMAINS_PER_COMPANY));
        }
        return result;
    }

    @Transactional
    public CustomDomain add(String instrumentId, String actorSubject, UUID correlationId, String rawDomain) {
        Instrument company = instruments.findById(instrumentId).filter(Instrument::isActive)
                .orElseThrow(SourceDomainException::notFound);
        currentOwnership.refresh();
        if (!held.holds(actorSubject, instrumentId)) throw SourceDomainException.forbidden();

        String domain = policy.normalise(rawDomain);
        List<String> existing = new ArrayList<>(company.getOfficialDomains());
        existing.addAll(ingestion.approvedDomains());
        domains.findByInstrumentIdAndActiveTrueOrderByAddedAtAsc(instrumentId)
                .forEach(entry -> existing.add(entry.getDomain()));
        String covering = SourceDomainPolicy.coveredBy(domain, existing);
        if (covering != null) {
            throw SourceDomainException.conflict("ALREADY_COVERED",
                    domain.equals(covering) ? domain + " is already searched for this company"
                            : domain + " is already covered by " + covering);
        }
        if (domains.countByInstrumentIdAndActiveTrue(instrumentId) >= SourceDomainPolicy.MAX_CUSTOM_DOMAINS_PER_COMPANY) {
            throw SourceDomainException.conflict("LIMIT_REACHED", "a company can have at most "
                    + SourceDomainPolicy.MAX_CUSTOM_DOMAINS_PER_COMPANY + " added domains; remove one first");
        }

        ensureAuditCapacity();
        Instant now = clock.instant();
        InstrumentSourceDomain entry = new InstrumentSourceDomain(
                UUID.randomUUID(), instrumentId, domain, actorSubject, now);
        try {
            domains.saveAndFlush(entry);
        } catch (DataIntegrityViolationException duplicate) {
            throw SourceDomainException.conflict("ALREADY_COVERED", domain + " is already searched for this company");
        }
        persistOutbox("alert.search-domain-added", payload(entry, actorSubject, "AGENT", correlationId, now), now);
        return new CustomDomain(entry.getId(), domain, now, true);
    }

    @Transactional
    public void deactivate(String instrumentId, UUID domainId, String actorSubject, boolean admin, UUID correlationId) {
        if (!admin) {
            currentOwnership.refresh();
            if (!held.holds(actorSubject, instrumentId)) throw SourceDomainException.notFound();
        }
        InstrumentSourceDomain entry = domains.findByIdAndInstrumentIdAndActiveTrue(domainId, instrumentId)
                .orElseThrow(SourceDomainException::notFound);
        ensureAuditCapacity();
        Instant now = clock.instant();
        entry.deactivate(actorSubject, now);
        domains.saveAndFlush(entry);
        persistOutbox("alert.search-domain-deactivated",
                payload(entry, actorSubject, admin ? "ADMIN" : "AGENT", correlationId, now), now);
    }

    private ObjectNode payload(InstrumentSourceDomain entry, String actorSubject, String actorRole,
            UUID correlationId, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sourceDomainId", entry.getId().toString());
        payload.put("instrumentId", entry.getInstrumentId());
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
