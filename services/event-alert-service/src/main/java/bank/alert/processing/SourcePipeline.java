package bank.alert.processing;

import bank.alert.config.AiProperties;
import bank.alert.config.PipelineProperties;
import bank.alert.domain.Instrument;
import bank.alert.domain.SourceResult;
import bank.alert.matching.InstrumentMatch;
import bank.alert.matching.InstrumentMatcher;
import bank.alert.repository.InstrumentRepository;
import bank.alert.repository.SourceResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns stored source results into alerts (ADR-0049). Each pass takes the oldest
 * sources not yet finished, matches them to instruments deterministically, and
 * hands a matched source to {@link SourceResultProcessor}, which classifies it
 * once and creates the alerts for every agent who holds an affected instrument.
 *
 * A source is marked finished only when its outcome is definitive. If the model
 * cannot be used right now (switched off, over quota, provider down) the source
 * stays pending and the pass stops, so the next run tries again and no source is
 * silently lost. A failure on one source is counted and the pass moves on.
 */
@Service
public class SourcePipeline {

    private static final Logger log = LoggerFactory.getLogger(SourcePipeline.class);

    /** Reasons that mean "not now", not "no": the source is tried again on a later run. */
    static final Set<String> DEFERRED_REASONS = Set.of(
            "MODEL_DISABLED", "CONFIG_DISABLED", "OPERATOR_DISABLED",
            "MODEL_TOKEN_QUOTA_REACHED", "MONTHLY_BUDGET_REACHED", "PROVIDER_FAILURE");

    private final SourceResultRepository sources;
    private final InstrumentRepository instruments;
    private final InstrumentMatcher matcher;
    private final SourceResultProcessor processor;
    private final AiProperties ai;
    private final PipelineProperties properties;
    private final Clock clock;

    public SourcePipeline(SourceResultRepository sources, InstrumentRepository instruments,
            InstrumentMatcher matcher, SourceResultProcessor processor, AiProperties ai,
            PipelineProperties properties, Clock clock) {
        this.sources = sources;
        this.instruments = instruments;
        this.matcher = matcher;
        this.processor = processor;
        this.ai = ai;
        this.properties = properties;
        this.clock = clock;
    }

    public PipelineSummary processPending() {
        if (!ai.enabled()) return PipelineSummary.skipped("MODEL_DISABLED");
        Instant now = clock.instant();
        List<SourceResult> pending = sources.findByProcessedAtIsNullAndExpiresAtAfterOrderByRetrievedAtAsc(
                now, PageRequest.of(0, properties.maxSourcesPerRun()));
        List<Instrument> master = pending.isEmpty() ? List.of() : instruments.findByActiveTrueOrderByIdAsc();

        int examined = 0;
        int noMatch = 0;
        int processed = 0;
        int created = 0;
        int deferred = 0;
        int errors = 0;
        for (SourceResult source : pending) {
            examined++;
            List<InstrumentMatch> matches = matcher.match(source.getTitle(), source.getExcerpt(), master);
            if (matches.isEmpty()) {
                finish(source, "NO_MATCH", now);
                noMatch++;
                continue;
            }
            Map<String, BigDecimal> relevance = new LinkedHashMap<>();
            matches.forEach(match -> relevance.put(match.instrumentId(), match.relevance()));
            String excerpt = source.getExcerpt() != null ? source.getExcerpt() : source.getTitle();

            SourceFanOutOutcome outcome;
            try {
                outcome = processor.processForAgents(
                        new SourceFanOutRequest(source.getId(), excerpt, relevance, now));
            } catch (RuntimeException failure) {
                errors++;
                log.warn("pipeline source failed error_code={}", failure.getClass().getSimpleName());
                continue;
            }
            if (!outcome.accepted()) {
                if (DEFERRED_REASONS.contains(outcome.reasonCode())) {
                    deferred++;
                    break; // the model is unavailable for every remaining source too
                }
                finish(source, outcome.classificationStatus().name(), now);
                processed++;
                continue;
            }
            created += outcome.alertsCreated();
            finish(source, outcome.alertsCreated() > 0 ? "ALERTS_CREATED"
                    : "NO_HOLDERS".equals(outcome.reasonCode()) ? "NO_HOLDERS" : "NO_ALERTS", now);
            processed++;
        }
        return new PipelineSummary(examined, noMatch, processed, created, deferred, errors, null);
    }

    private void finish(SourceResult source, String outcome, Instant at) {
        source.markProcessed(outcome, at);
        sources.save(source);
    }
}
