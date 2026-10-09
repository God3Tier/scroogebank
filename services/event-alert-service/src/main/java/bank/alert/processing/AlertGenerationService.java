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
        Set<String> affectedIds = classification.affectedInstruments().stream()
                .map(AffectedInstrument::instrumentId)
                .collect(Collectors.toUnmodifiableSet());
        if (affectedIds.isEmpty()) {
            throw new IllegalArgumentException("classification has no affected instruments");
        }

        List<HoldingSnapshot> positions = holdingsProvider.read(new HoldingsReadRequest(
                affectedIds, request.asOf(), request.assignedAgentSubject())).positions();
        Map<PortfolioKey, List<HoldingSnapshot>> portfolios = positions.stream()
                .collect(Collectors.groupingBy(PortfolioKey::from, LinkedHashMap::new, Collectors.toList()));
        UUID existingPublicEventId = findPublicEventId(source.getId(), classification.modelVersion());
        long newPortfolioAlerts = existingPublicEventId == null
                ? portfolios.size()
                : portfolios.values().stream()
                        .filter(portfolio -> !hasExistingAlert(
                                portfolio, affectedIds, existingPublicEventId))
                        .count();
        ensureAuditCapacity((existingPublicEventId != null && auditOutbox.existsById(auditEventId(
                        "alert.classification-recorded", existingPublicEventId)) ? 0 : 1)
                + newPortfolioAlerts * (request.briefing() == null ? 1 : 2));
        UUID publicEventId = existingPublicEventId == null
                ? persistPublicEvent(source, classification) : existingPublicEventId;
        persistClassificationAudit(publicEventId, source.getId(), classification);

        int created = 0;
        int duplicates = 0;
        int belowThreshold = 0;
        int unevaluable = 0;
        int suppressed = 0;
        List<UUID> alertIds = new ArrayList<>();
        Instant dayStart = request.asOf().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
        long dailyCount = alerts.countByAssignedAgentSubjectAndCreatedAtGreaterThanEqual(
                request.assignedAgentSubject(), dayStart);

        for (List<HoldingSnapshot> portfolio : portfolios.values()) {
            ExposureResult exposure = exposureCalculator.calculate(portfolio, affectedIds);
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

            HoldingSnapshot affected = portfolio.stream()
                    .filter(position -> affectedIds.contains(position.getInstrumentId()))
                    .findFirst().orElseThrow();
            if (alerts.findByClientRefAndPublicEventIdAndInstrumentId(
                    affected.getClientRef(), publicEventId, affected.getInstrumentId()).isPresent()) {
                duplicates++;
                continue;
            }
            if (dailyCount + created >= DAILY_ALERT_LIMIT) {
                suppressed++;
                continue;
            }

            holdingSnapshots.saveAll(portfolio);
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
        return new AlertGenerationOutcome(publicEventId, created, duplicates, belowThreshold,
                unevaluable, suppressed, alertIds);
    }

    private UUID persistPublicEvent(SourceResult source, EventClassification classification) {
        String classifierVersion = classification.modelVersion();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public_event
                    (id, source_result_id, classification, direction, confidence,
                     classifier_version, model_version, prompt_version, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_result_id, classifier_version) DO NOTHING
                """, eventId, source.getId(), classification.eventType().name(),
                classification.direction().name(), classification.confidence(), classifierVersion,
                classification.modelVersion(), classification.promptVersion(),
                Timestamp.from(source.getPublishedAt() == null
                        ? classification.classifiedAt() : source.getPublishedAt()),
                Timestamp.from(classification.classifiedAt()));
        UUID persistedId = jdbc.queryForObject("""
                SELECT id FROM public_event
                 WHERE source_result_id = ? AND classifier_version = ?
                """, UUID.class, source.getId(), classifierVersion);
        for (AffectedInstrument affected : classification.affectedInstruments()) {
            jdbc.update("""
                    INSERT INTO public_event_instrument
                        (public_event_id, instrument_id, match_reason, confidence)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (public_event_id, instrument_id) DO NOTHING
                    """, persistedId, affected.instrumentId(),
                    "Model classification cites " + affected.evidenceRef(), classification.confidence());
        }
        return persistedId;
    }

    private UUID findPublicEventId(UUID sourceResultId, String classifierVersion) {
        List<UUID> existing = jdbc.query("""
                SELECT id FROM public_event
                 WHERE source_result_id = ? AND classifier_version = ?
                """, (resultSet, row) -> resultSet.getObject(1, UUID.class),
                sourceResultId, classifierVersion);
        return existing.isEmpty() ? null : existing.getFirst();
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

    private void persistClassificationAudit(UUID eventId, UUID sourceId,
            EventClassification classification) {
        persistAudit("alert.classification-recorded", eventId, Map.of(
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

    private boolean hasExistingAlert(List<HoldingSnapshot> portfolio, Set<String> affectedIds,
            UUID publicEventId) {
        return portfolio.stream()
                .filter(position -> affectedIds.contains(position.getInstrumentId()))
                .anyMatch(position -> alerts.findByClientRefAndPublicEventIdAndInstrumentId(
                        position.getClientRef(), publicEventId, position.getInstrumentId()).isPresent());
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

    private record PortfolioKey(String clientRef, String accountRef, String agentSubject) {
        static PortfolioKey from(HoldingSnapshot position) {
            return new PortfolioKey(position.getClientRef(), position.getAccountRef(),
                    position.getAssignedAgentSubject());
        }
    }
}
