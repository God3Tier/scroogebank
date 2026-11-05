package bank.alert.processing;

import bank.alert.ai.AffectedInstrument;
import bank.alert.ai.AgentBriefing;
import bank.alert.ai.EventClassification;
import bank.alert.domain.Alert;
import bank.alert.domain.AuditOutbox;
import bank.alert.domain.HoldingSnapshot;
import bank.alert.domain.SourceResult;
import bank.alert.holdings.HoldingsProvider;
import bank.alert.holdings.HoldingsReadRequest;
import bank.alert.repository.AlertRepository;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.repository.HoldingSnapshotRepository;
import bank.alert.repository.SourceResultRepository;
import bank.alert.scoring.ExposureCalculator;
import bank.alert.scoring.ExposureResult;
import bank.alert.scoring.MaterialityInputs;
import bank.alert.scoring.MaterialityScore;
import bank.alert.scoring.MaterialityScorer;
import bank.alert.scoring.ScoreFactor;
import bank.alert.service.AuditUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AlertGenerationService {

    private static final int DAILY_ALERT_LIMIT = 10;
    /** A ticker's event takes new articles until its report is written, but never for longer than this (ADR-0084). */
    static final java.time.Duration OPEN_WINDOW = java.time.Duration.ofHours(72);
    private static final Collection<String> PENDING_AUDIT_STATUSES = List.of("PENDING", "FAILED");

    private final SourceResultRepository sourceResults;
    private final HoldingsProvider holdingsProvider;
    private final ExposureCalculator exposureCalculator;
    private final MaterialityScorer scorer;
    private final HoldingSnapshotRepository holdingSnapshots;
    private final AlertRepository alerts;
    private final AuditOutboxRepository auditOutbox;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxPendingAuditEvents;

    public AlertGenerationService(SourceResultRepository sourceResults,
            HoldingsProvider holdingsProvider, ExposureCalculator exposureCalculator,
            MaterialityScorer scorer, HoldingSnapshotRepository holdingSnapshots,
            AlertRepository alerts, AuditOutboxRepository auditOutbox, JdbcTemplate jdbc,
            ObjectMapper mapper, Clock clock,
            @Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents) {
        this.sourceResults = sourceResults;
        this.holdingsProvider = holdingsProvider;
        this.exposureCalculator = exposureCalculator;
        this.scorer = scorer;
        this.holdingSnapshots = holdingSnapshots;
        this.alerts = alerts;
        this.auditOutbox = auditOutbox;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    @Transactional
    public AlertGenerationOutcome generate(AlertGenerationRequest request) {
        SourceResult source = sourceResults.findById(request.sourceResultId())
                .orElseThrow(() -> new IllegalArgumentException("source result does not exist"));
        EventClassification classification = request.classification();
        List<String> instrumentIds = classification.affectedInstruments().stream()
                .map(AffectedInstrument::instrumentId)
                .distinct().sorted().toList();
        if (instrumentIds.isEmpty()) {
            throw new IllegalArgumentException("classification has no affected instruments");
        }
        Set<String> affectedIds = Set.copyOf(instrumentIds);

        List<HoldingSnapshot> positions = holdingsProvider.read(new HoldingsReadRequest(
                affectedIds, request.asOf(), request.assignedAgentSubject())).positions();
        Map<PortfolioKey, List<HoldingSnapshot>> portfolios = positions.stream()
                .collect(Collectors.groupingBy(PortfolioKey::from, LinkedHashMap::new, Collectors.toList()));
        // One event per ticker (ADR-0084): this source joins the ticker's open event, the one that has no report yet,
        // or starts a new one. Whatever else the ticker has in the news, whatever its type or direction, lands in the
        // same event, and so in the same report.
        Map<String, UUID> existingEvents = new LinkedHashMap<>();
        for (String instrumentId : instrumentIds) {
            UUID existing = findEventFor(source.getId(), instrumentId);
            if (existing != null) existingEvents.put(instrumentId, existing);
        }
        // One candidate per held affected instrument, scored against that account's whole portfolio.
        // A client holding the same instrument in several accounts yields only its best account.
        Map<AlertKey, Candidate> evaluated = new LinkedHashMap<>();
        int belowThreshold = 0;
        int unevaluable = 0;
        for (List<HoldingSnapshot> portfolio : portfolios.values()) {
            for (String instrumentId : instrumentIds) {
                HoldingSnapshot affected = portfolio.stream()
                        .filter(position -> position.getInstrumentId().equals(instrumentId))
                        .findFirst().orElse(null);
                if (affected == null) continue;
                ExposureResult exposure = exposureCalculator.calculate(portfolio, Set.of(instrumentId));
                if (!exposure.evaluable()) {
                    unevaluable++;
                    continue;
                }
                MaterialityScore score = scorer.score(new MaterialityInputs(
                        request.eventSeverity(),
                        exposure.portfolioPercentage().divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP),
                        request.retrievalRelevance(),
                        classification.confidence(),
                        exposure.freshness()));
                if (!score.alertEligible()) {
                    belowThreshold++;
                    continue;
                }
                Candidate candidate = new Candidate(portfolio, affected, score);
                evaluated.merge(new AlertKey(affected.getClientRef(), instrumentId), candidate,
                        Candidate::better);
            }
        }
        long newPortfolioAlerts = evaluated.keySet().stream()
                .filter(key -> existingEvents.get(key.instrumentId()) == null
                        || alerts.findByClientRefAndPublicEventIdAndInstrumentId(
                                key.clientRef(), existingEvents.get(key.instrumentId()), key.instrumentId()).isEmpty())
                .count();
        long newClassificationAudits = instrumentIds.stream()
                .filter(instrumentId -> existingEvents.get(instrumentId) == null
                        || !auditOutbox.existsById(auditEventId("alert.classification-recorded",
                                classificationAuditKey(existingEvents.get(instrumentId), source.getId()))))
                .count();
        ensureAuditCapacity(newClassificationAudits + newPortfolioAlerts * (request.briefing() == null ? 1 : 2));

        Map<String, UUID> eventIds = new LinkedHashMap<>();
        for (String instrumentId : instrumentIds) {
            UUID eventId = existingEvents.get(instrumentId);
            if (eventId == null) eventId = persistPublicEvent(source, classification, instrumentId);
            linkSource(eventId, source.getId());
            persistClassificationAudit(eventId, source.getId(), classification);
            eventIds.put(instrumentId, eventId);
        }

        int created = 0;
        int duplicates = 0;
        int suppressed = 0;
        List<UUID> alertIds = new ArrayList<>();
        Instant dayStart = request.asOf().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
        long dailyCount = alerts.countByAssignedAgentSubjectAndCreatedAtGreaterThanEqual(
                request.assignedAgentSubject(), dayStart);

        for (Candidate candidate : evaluated.values()) {
            HoldingSnapshot affected = candidate.affected();
            MaterialityScore score = candidate.score();
            UUID publicEventId = eventIds.get(affected.getInstrumentId());
            if (alerts.findByClientRefAndPublicEventIdAndInstrumentId(
                    affected.getClientRef(), publicEventId, affected.getInstrumentId()).isPresent()) {
                duplicates++;
                continue;
            }
            if (dailyCount + created >= DAILY_ALERT_LIMIT) {
                suppressed++;
                continue;
            }

            persistSnapshotGroup(candidate.portfolio());
            holdingSnapshots.saveAll(candidate.portfolio());
            Alert alert = alerts.saveAndFlush(new Alert(
                    UUID.randomUUID(), affected.getClientRef(), affected.getAssignedAgentSubject(),
                    publicEventId, affected.getInstrumentId(), affected.getId(), score.score(),
                    score.ruleVersion(), clock.instant()));
            persistFactors(alert.getId(), score.factors());
            if (request.briefing() != null) persistBriefing(alert.getId(), request.briefing());
            persistAlertAudit(alert, source.getId());
            alertIds.add(alert.getId());
            created++;
        }
        return new AlertGenerationOutcome(eventIds.values().iterator().next(), created, duplicates, belowThreshold,
                unevaluable, suppressed, alertIds);
    }

    /** Records the group of positions scored together, so later portfolio edits never change this alert's evidence. */
    private void persistSnapshotGroup(List<HoldingSnapshot> portfolio) {
        HoldingSnapshot first = portfolio.getFirst();
        if (first.getPortfolioSnapshotId() == null) return;
        BigDecimal total = portfolio.stream().map(HoldingSnapshot::getValueSgd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.update("""
                INSERT INTO portfolio_snapshot (id, account_ref, client_ref, portfolio_revision,
                    assigned_agent_subject, total_sgd, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                """, first.getPortfolioSnapshotId(), first.getAccountRef(), first.getClientRef(),
                first.getPortfolioRevision(), first.getAssignedAgentSubject(), total,
                Timestamp.from(clock.instant()));
    }

    private UUID persistPublicEvent(SourceResult source, EventClassification classification, String instrumentId) {
        String classifierVersion = classification.modelVersion();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public_event
                    (id, source_result_id, instrument_id, classification, direction, confidence,
                     classifier_version, model_version, prompt_version, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, eventId, source.getId(), instrumentId, classification.eventType().name(),
                classification.direction().name(), classification.confidence(), classifierVersion,
                classification.modelVersion(), classification.promptVersion(),
                Timestamp.from(source.getPublishedAt() == null
                        ? classification.classifiedAt() : source.getPublishedAt()),
                Timestamp.from(classification.classifiedAt()));
        UUID persistedId = jdbc.queryForObject("""
                SELECT id FROM public_event
                 WHERE source_result_id = ? AND classifier_version = ? AND instrument_id = ?
                """, UUID.class, source.getId(), classifierVersion, instrumentId);
        AffectedInstrument affected = classification.affectedInstruments().stream()
                .filter(candidate -> candidate.instrumentId().equals(instrumentId)).findFirst().orElseThrow();
        jdbc.update("""
                INSERT INTO public_event_instrument
                    (public_event_id, instrument_id, match_reason, confidence)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (public_event_id, instrument_id) DO NOTHING
                """, persistedId, instrumentId,
                "Model classification cites " + affected.evidenceRef(), classification.confidence());
        return persistedId;
    }

    /** Every source an event rests on, the first and any that later join it. */
    private void linkSource(UUID publicEventId, UUID sourceResultId) {
        jdbc.update("""
                INSERT INTO public_event_source (public_event_id, source_result_id, linked_at)
                VALUES (?, ?, ?) ON CONFLICT (public_event_id, source_result_id) DO NOTHING
                """, publicEventId, sourceResultId, Timestamp.from(clock.instant()));
    }

    /**
     * The event this source belongs to for one ticker, or null. A source already linked to an event of the ticker
     * keeps it (so a second agent's pass finds the same event). Otherwise it joins the ticker's open event: created
     * within the last 72 hours and with no report yet. Once a pass has written the report, the event is closed and the
     * next run's new articles start a new one, so the same news does not reach two reports in a row.
     */
    private UUID findEventFor(UUID sourceResultId, String instrumentId) {
        List<UUID> same = jdbc.query("""
                SELECT pe.id FROM public_event pe
                  JOIN public_event_source s ON s.public_event_id = pe.id
                 WHERE s.source_result_id = ?
                   AND (pe.instrument_id = ?
                        OR (pe.instrument_id IS NULL AND EXISTS (
                              SELECT 1 FROM public_event_instrument i
                               WHERE i.public_event_id = pe.id AND i.instrument_id = ?)))
                 ORDER BY pe.created_at ASC
                 LIMIT 1
                """, (resultSet, row) -> resultSet.getObject(1, UUID.class), sourceResultId, instrumentId, instrumentId);
        if (!same.isEmpty()) return same.getFirst();
        List<UUID> open = jdbc.query("""
                SELECT pe.id FROM public_event pe
                 WHERE pe.instrument_id = ?
                   AND pe.created_at >= ?
                   AND NOT EXISTS (SELECT 1 FROM event_report r WHERE r.public_event_id = pe.id)
                 ORDER BY pe.created_at DESC
                 LIMIT 1
                """, (resultSet, row) -> resultSet.getObject(1, UUID.class), instrumentId,
                Timestamp.from(clock.instant().minus(OPEN_WINDOW)));
        return open.isEmpty() ? null : open.getFirst();
    }

    private void persistFactors(UUID alertId, List<ScoreFactor> factors) {
        for (int index = 0; index < factors.size(); index++) {
            ScoreFactor factor = factors.get(index);
            jdbc.update("""
                    INSERT INTO alert_factor
                        (alert_id, sequence_number, code, contribution, description)
                    VALUES (?, ?, ?, ?, ?)
                    """, alertId, index, factor.code(), factor.contribution(), factor.explanation());
        }
    }

    private void persistBriefing(UUID alertId, AgentBriefing briefing) {
        jdbc.update("""
                INSERT INTO briefing
                    (id, alert_id, summary, uncertainty, model_version, prompt_version,
                     ai_generated, citation_refs, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, CAST(? AS jsonb), ?, ?)
                """, UUID.randomUUID(), alertId, briefing.summary(), briefing.uncertainty(),
                briefing.modelVersion(), briefing.promptVersion(), json(briefing.sourceRefs()),
                Timestamp.from(briefing.generatedAt()),
                Timestamp.from(briefing.generatedAt().plus(90, ChronoUnit.DAYS)));
        persistAudit("alert.briefing-generated", alertId, Map.of(
                "alertId", alertId,
                "modelVersion", briefing.modelVersion(),
                "promptVersion", briefing.promptVersion(),
                "sourceRefs", briefing.sourceRefs(),
                "outcome", "GENERATED"));
    }

    /** One classification record per event and source, since an event now rests on several sources. */
    private static UUID classificationAuditKey(UUID eventId, UUID sourceId) {
        return UUID.nameUUIDFromBytes((eventId + "|" + sourceId).getBytes(StandardCharsets.UTF_8));
    }

    private void persistClassificationAudit(UUID eventId, UUID sourceId,
            EventClassification classification) {
        persistAudit("alert.classification-recorded", classificationAuditKey(eventId, sourceId), Map.of(
                "publicEventId", eventId,
                "sourceResultId", sourceId,
                "classification", classification.eventType().name(),
                "modelVersion", classification.modelVersion(),
                "promptVersion", classification.promptVersion(),
                "outcome", "ACCEPTED"));
    }

    private void persistAlertAudit(Alert alert, UUID sourceId) {
        persistAudit("alert.generated", alert.getId(), Map.of(
                "alertId", alert.getId(),
                "publicEventId", alert.getPublicEventId(),
                "instrumentId", alert.getInstrumentId(),
                "sourceResultId", sourceId,
                "ruleVersion", alert.getRuleVersion(),
                "status", alert.getStatus().name(),
                "outcome", "CREATED"));
    }

    private void persistAudit(String eventType, UUID resourceId, Map<String, ?> payload) {
        UUID eventId = auditEventId(eventType, resourceId);
        if (!auditOutbox.existsById(eventId)) {
            auditOutbox.save(new AuditOutbox(eventId, eventType, 1, json(payload), clock.instant()));
        }
    }

    private static UUID auditEventId(String eventType, UUID resourceId) {
        return UUID.nameUUIDFromBytes(
                (eventType + "|" + resourceId).getBytes(StandardCharsets.UTF_8));
    }

    private void ensureAuditCapacity(long required) {
        long pending = auditOutbox.countByStatusIn(PENDING_AUDIT_STATUSES);
        if (required < 0 || pending + required > maxPendingAuditEvents) {
            throw new AuditUnavailableException("audit outbox is at capacity");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AuditUnavailableException("cannot serialize alert audit payload", exception);
        }
    }

    private record AlertKey(String clientRef, String instrumentId) {
    }

    private record Candidate(List<HoldingSnapshot> portfolio, HoldingSnapshot affected, MaterialityScore score) {
        /** Higher score wins; a tie goes to the lower account ID so the choice is repeatable. */
        Candidate better(Candidate other) {
            int byScore = score.score().compareTo(other.score().score());
            if (byScore != 0) return byScore > 0 ? this : other;
            return affected.getAccountRef().compareTo(other.affected().getAccountRef()) <= 0 ? this : other;
        }
    }

    private record PortfolioKey(String clientRef, String accountRef, String agentSubject) {
        static PortfolioKey from(HoldingSnapshot position) {
            return new PortfolioKey(position.getClientRef(), position.getAccountRef(),
                    position.getAssignedAgentSubject());
        }
    }
}
