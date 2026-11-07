package bank.alert.report;

import bank.alert.ai.PublicEvidence;
import bank.alert.domain.AuditOutbox;
import bank.alert.domain.Instrument;
import bank.alert.repository.AuditOutboxRepository;
import bank.alert.repository.InstrumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Writes and versions the one client report each event has (ADR-0084). A report is made only once an alert for the
 * event scores 60 or more, so a quiet event costs nothing, and it is made once for the event, not once per client.
 * The first report is not held back waiting for more sources. When a later source from a new domain changes which
 * (at most five) sources are chosen, the next version is written for the agent to review.
 */
@Service
public class EventReportService {

    private static final Logger log = LoggerFactory.getLogger(EventReportService.class);

    static final int MINIMUM_ALERT_SCORE = 60;
    static final int MAX_FAILED_ATTEMPTS = 3;
    /** An earlier report on the same company this recent is given to the model so it is not repeated. */
    static final java.time.Duration REPEAT_GUARD = java.time.Duration.ofHours(72);
    /** Not a failure of the report: it is tried again when the model or the budget is available. */
    private static final Set<String> NOT_A_FAILURE = Set.of("MODEL_DISABLED", "CONFIG_DISABLED", "OPERATOR_DISABLED",
            "MODEL_TOKEN_QUOTA_REACHED", "MONTHLY_BUDGET_REACHED");

    private final JdbcTemplate jdbc;
    private final InstrumentRepository instruments;
    private final ClientReportGenerator generator;
    private final AuditOutboxRepository auditOutbox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int maxPendingAuditEvents;
    /** Null when no summary sentence is written (some tests). */
    private final SummarySentenceGenerator summaries;

    public EventReportService(JdbcTemplate jdbc, InstrumentRepository instruments, ClientReportGenerator generator,
            AuditOutboxRepository auditOutbox, ObjectMapper mapper, Clock clock, int maxPendingAuditEvents) {
        this(jdbc, instruments, generator, auditOutbox, mapper, clock, maxPendingAuditEvents, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EventReportService(JdbcTemplate jdbc, InstrumentRepository instruments, ClientReportGenerator generator,
            AuditOutboxRepository auditOutbox, ObjectMapper mapper, Clock clock,
            @Value("${alert.audit.max-pending:10000}") int maxPendingAuditEvents, SummarySentenceGenerator summaries) {
        this.summaries = summaries;
        this.jdbc = jdbc;
        this.instruments = instruments;
        this.generator = generator;
        this.auditOutbox = auditOutbox;
        this.mapper = mapper;
        this.clock = clock;
        this.maxPendingAuditEvents = maxPendingAuditEvents;
    }

    /** Writes the reports that are due, at most {@code limit} model calls. Returns how many were written. */
    public int refresh(int limit) {
        int written = 0;
        for (UUID eventId : eventsDueForAReport(limit)) {
            try {
                if (generateFor(eventId)) written++;
            } catch (RuntimeException failure) {
                log.warn("event report failed error_code={}", failure.getClass().getSimpleName());
            }
        }
        return written;
    }

    List<UUID> eventsDueForAReport(int limit) {
        return jdbc.query("""
                SELECT pe.id FROM public_event pe
                 WHERE EXISTS (SELECT 1 FROM alert a WHERE a.public_event_id = pe.id AND a.score >= ?)
                   AND COALESCE((SELECT attempts FROM event_report_attempt t WHERE t.public_event_id = pe.id), 0) < ?
                   AND (NOT EXISTS (SELECT 1 FROM event_report r WHERE r.public_event_id = pe.id)
                        OR EXISTS (SELECT 1 FROM public_event_source s
                                    WHERE s.public_event_id = pe.id
                                      AND s.linked_at >= (SELECT max(r.created_at) FROM event_report r
                                                          WHERE r.public_event_id = pe.id)))
                 ORDER BY pe.created_at ASC
                 LIMIT ?
                """, (rs, row) -> rs.getObject(1, UUID.class), MINIMUM_ALERT_SCORE, MAX_FAILED_ATTEMPTS, limit);
    }

    /** Writes the next version of this event's report if the chosen sources call for one. Returns true if it did. */
    public boolean generateFor(UUID eventId) {
        List<SourceRow> linked = linkedSources(eventId);
        if (linked.isEmpty()) return false;
        List<EventSourceSelector.Candidate> chosen = EventSourceSelector.select(linked.stream()
                .map(row -> new EventSourceSelector.Candidate(row.id().toString(), row.domain(), row.publishedAt(), row.url()))
                .toList());
        List<String> chosenIds = chosen.stream().map(EventSourceSelector.Candidate::sourceId).toList();
        List<String> latest = latestSourceIds(eventId);
        if (chosenIds.equals(latest)) return false;
        if (auditOutbox.countByStatusIn(List.of("PENDING", "FAILED")) >= maxPendingAuditEvents) return false;

        Map<String, SourceRow> byId = linked.stream().collect(Collectors.toMap(row -> row.id().toString(), row -> row));
        List<ReportInput.InstrumentRef> instrumentRefs = instrumentsOf(eventId);
        List<String> instrumentIds = instrumentRefs.stream().map(ReportInput.InstrumentRef::instrumentId).toList();
        List<PublicEvidence> evidence = chosenIds.stream().map(byId::get).map(row -> toEvidence(row, instrumentIds)).toList();
        ReportInput input = new ReportInput(instrumentRefs, evidence, alreadyReported(eventId, instrumentRefs));
        ReportOutcome outcome = generator.generate(input);
        if (outcome.status() != ReportOutcome.Status.GENERATED) {
            recordAttempt(eventId, outcome);
            return false;
        }
        int version = persist(eventId, outcome, chosenIds);
        jdbc.update("DELETE FROM event_report_attempt WHERE public_event_id = ?", eventId);
        if (version > 0) writeSummarySentence(eventId, version, instrumentRefs);
        return version > 0;
    }

    private int persist(UUID eventId, ReportOutcome outcome, List<String> sourceIds) {
        Instant now = clock.instant();
        int version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM event_report WHERE public_event_id = ?", Integer.class, eventId);
        UUID reportId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO event_report (id, public_event_id, version, schema_version, report, source_ids,
                                          model_version, prompt_version, ai_generated, created_at, expires_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, TRUE, ?, ?)
                """, reportId, eventId, version, ClientReport.SCHEMA_VERSION, json(outcome.report()), json(sourceIds),
                outcome.modelVersion(), outcome.promptVersion(), Timestamp.from(now),
                Timestamp.from(now.plus(90, ChronoUnit.DAYS)));
        UUID auditId = UUID.nameUUIDFromBytes(("alert.report-generated|" + reportId).getBytes(StandardCharsets.UTF_8));
        auditOutbox.saveAndFlush(new AuditOutbox(auditId, "alert.report-generated", 1, json(Map.of(
                "publicEventId", eventId, "reportId", reportId, "version", version,
                "modelVersion", outcome.modelVersion(), "promptVersion", outcome.promptVersion(),
                "sourceIds", sourceIds, "outcome", "GENERATED")), now));
        return version;
    }

    /**
     * The one-sentence summary for the news email (news-alert-drafts plan, section 6), from this report's public text
     * only. A failure never affects the report: the email then uses the report's first sentence.
     */
    private void writeSummarySentence(UUID eventId, int version, List<ReportInput.InstrumentRef> instrumentRefs) {
        if (summaries == null || instrumentRefs.isEmpty()) return;
        try {
            List<Object[]> rows = jdbc.query("SELECT id, report::text FROM event_report WHERE public_event_id = ? AND version = ?",
                    (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}, eventId, version);
            if (rows.isEmpty()) return;
            UUID reportId = (UUID) rows.getFirst()[0];
            ReportInput.InstrumentRef instrument = instrumentRefs.getFirst();
            SummarySentenceGenerator.Outcome outcome = summaries.write(mapper.readTree((String) rows.getFirst()[1]),
                    instrument.issuerName(), instrument.ticker());
            if ("SUMMARY_DISABLED".equals(outcome.reasonCode())) return;
            if (outcome.sentence() != null) {
                jdbc.update("UPDATE event_report SET summary_sentence = ?, summary_model = ? WHERE id = ?",
                        outcome.sentence(), outcome.model(), reportId);
            }
            Instant now = clock.instant();
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("reportId", reportId);
            payload.put("publicEventId", eventId);
            payload.put("version", version);
            payload.put("modelId", outcome.model());
            payload.put("outcome", outcome.sentence() != null ? "PASSED" : "FAILED");
            payload.put("reasonCode", outcome.reasonCode());
            payload.put("occurredAt", now.toString());
            auditOutbox.saveAndFlush(new AuditOutbox(UUID.randomUUID(), "alert.summary-generated", 1, json(payload), now));
        } catch (JsonProcessingException | RuntimeException failure) {
            log.warn("summary sentence not written error_code={}", failure.getClass().getSimpleName());
        }
    }

    private void recordAttempt(UUID eventId, ReportOutcome outcome) {
        boolean failure = outcome.reasonCode() != null && !NOT_A_FAILURE.contains(outcome.reasonCode());
        log.info("event report not written event_id={} status={} reason_code={}", eventId, outcome.status(), outcome.reasonCode());
        if (!failure) return;
        jdbc.update("""
                INSERT INTO event_report_attempt (public_event_id, attempts, last_reason, last_attempt_at)
                VALUES (?, 1, ?, ?)
                ON CONFLICT (public_event_id) DO UPDATE
                   SET attempts = event_report_attempt.attempts + 1, last_reason = EXCLUDED.last_reason,
                       last_attempt_at = EXCLUDED.last_attempt_at
                """, eventId, outcome.reasonCode(), Timestamp.from(clock.instant()));
    }

    // ---- reading what the report is built from ---------------------------------------------------

    private record SourceRow(UUID id, String title, String excerpt, String url, String domain, Instant publishedAt) {}

    private List<SourceRow> linkedSources(UUID eventId) {
        return jdbc.query("""
                SELECT sr.id, sr.title, sr.excerpt, sr.canonical_url, sr.source_domain, sr.published_at
                  FROM public_event_source s JOIN source_result sr ON sr.id = s.source_result_id
                 WHERE s.public_event_id = ?
                """, (rs, row) -> new SourceRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()), eventId);
    }

    private PublicEvidence toEvidence(SourceRow row, List<String> instrumentIds) {
        String excerpt = row.excerpt() == null || row.excerpt().isBlank() ? row.title() : row.excerpt();
        return new PublicEvidence(row.id().toString(), row.title(), excerpt, row.url(), row.domain(),
                row.publishedAt(), instrumentIds);
    }

    private List<String> latestSourceIds(UUID eventId) {
        List<String> json = jdbc.query("""
                SELECT source_ids::text FROM event_report WHERE public_event_id = ? ORDER BY version DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), eventId);
        if (json.isEmpty()) return List.of();
        try {
            return mapper.readValue(json.getFirst(), new TypeReference<List<String>>() {});
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /**
     * What the ticker's most recent report from the last 72 hours said, as public text. Only for an event that belongs
     * to one ticker; an older event that spans several has nothing to compare against.
     */
    private List<ReportInput.PriorReport> alreadyReported(UUID eventId, List<ReportInput.InstrumentRef> instrumentRefs) {
        if (instrumentRefs.size() != 1) return List.of();
        return jdbc.query("""
                SELECT r.report::text FROM event_report r JOIN public_event pe ON pe.id = r.public_event_id
                 WHERE pe.instrument_id = ? AND pe.id <> ? AND r.created_at >= ?
                 ORDER BY r.created_at DESC LIMIT 1
                """, (rs, row) -> priorReport(rs.getString(1)), instrumentRefs.getFirst().instrumentId(), eventId,
                Timestamp.from(clock.instant().minus(REPEAT_GUARD))).stream().filter(java.util.Objects::nonNull).toList();
    }

    private ReportInput.PriorReport priorReport(String reportJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode report = mapper.readTree(reportJson);
            List<String> summary = new java.util.ArrayList<>();
            report.path("executiveSummary").forEach(sentence -> summary.add(sentence.path("text").asText()));
            String headline = report.path("headline").asText("");
            return headline.isBlank() && summary.isEmpty() ? null : new ReportInput.PriorReport(headline, summary);
        } catch (JsonProcessingException unreadable) {
            return null;
        }
    }

    private List<ReportInput.InstrumentRef> instrumentsOf(UUID eventId) {
        List<String> ids = jdbc.query("""
                SELECT instrument_id FROM public_event_instrument WHERE public_event_id = ? ORDER BY instrument_id
                """, (rs, row) -> rs.getString(1), eventId);
        return instruments.findAllById(ids).stream()
                .sorted(java.util.Comparator.comparing(Instrument::getId))
                .map(instrument -> new ReportInput.InstrumentRef(instrument.getId(), instrument.getTicker(),
                        instrument.getIssuerName()))
                .toList();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialise a report", exception);
        }
    }
}
