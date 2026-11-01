package bank.alert.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bank.alert.ai.BriefingGenerator;
import bank.alert.ai.BriefingInput;
import bank.alert.ai.BriefingOutcome;
import bank.alert.ai.BriefingStatus;
import bank.alert.ai.ClassificationOutcome;
import bank.alert.ai.ClassificationStatus;
import bank.alert.ai.EventClassifier;
import bank.alert.ai.PublicEvidence;
import bank.alert.ai.AffectedInstrument;
import bank.alert.domain.SourceResult;
import bank.alert.holdings.HoldingsProvider;
import bank.alert.matching.InstrumentMatcher;
import bank.alert.repository.SourceResultRepository;
import bank.alert.scoring.EventSeverityPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SourceResultProcessor {
    private static final Logger log = LoggerFactory.getLogger(SourceResultProcessor.class);

    private final SourceResultRepository sourceResults;
    private final EventClassifier classifier;
    private final BriefingGenerator briefingGenerator;
    private final AlertGenerationService alertGeneration;
    private final HoldingsProvider holdings;
    private final EventSeverityPolicy severityPolicy;

    /** For callers that name the agent themselves, and so never fan out. */
    public SourceResultProcessor(SourceResultRepository sourceResults, EventClassifier classifier,
            BriefingGenerator briefingGenerator, AlertGenerationService alertGeneration) {
        this(sourceResults, classifier, briefingGenerator, alertGeneration, null, null);
    }

    @Autowired
    public SourceResultProcessor(SourceResultRepository sourceResults, EventClassifier classifier,
            BriefingGenerator briefingGenerator, AlertGenerationService alertGeneration,
            HoldingsProvider holdings, EventSeverityPolicy severityPolicy) {
        this.sourceResults = sourceResults;
        this.classifier = classifier;
        this.briefingGenerator = briefingGenerator;
        this.alertGeneration = alertGeneration;
        this.holdings = holdings;
        this.severityPolicy = severityPolicy;
    }

    /**
     * Classifies a stored source once, writes one shared briefing, then creates the
     * alerts for every agent whose clients hold an affected instrument (ADR-0049).
     * Each agent only ever gets alerts for their own clients: generation reads
     * holdings per agent. Severity comes from a fixed table, never from the model.
     * Safe to repeat: alerts that already exist are counted as duplicates.
     */
    public SourceFanOutOutcome processForAgents(SourceFanOutRequest request) {
        SourceResult source = sourceResults.findById(request.sourceResultId())
                .orElseThrow(() -> new IllegalArgumentException("source result does not exist"));
        PublicEvidence evidence = new PublicEvidence(source.getId().toString(), source.getTitle(),
                request.excerpt(), source.getCanonicalUrl(), source.getSourceDomain(),
                source.getPublishedAt(), request.candidateRelevance().keySet().stream().sorted().toList());
        ClassificationOutcome classification = classifier.classify(evidence);
        if (classification.status() != ClassificationStatus.MODEL_ACCEPTED
                || classification.classification() == null) {
            // Reason codes are fixed enumerated values, never model or article text.
            log.info("source not classified source_id={} status={} reason_code={}",
                    source.getId(), classification.status(), classification.reasonCode());
            return SourceFanOutOutcome.notAccepted(classification.status(), classification.reasonCode());
        }

        Set<String> affected = classification.classification().affectedInstruments().stream()
                .map(AffectedInstrument::instrumentId).collect(Collectors.toUnmodifiableSet());
        List<String> agents = holdings.assignedAgentsHolding(affected);
        if (agents.isEmpty()) {
            return new SourceFanOutOutcome(classification.status(), null, "NO_HOLDERS",
                    0, 0, 0, 0, 0, 0);
        }

        BriefingOutcome briefing = briefingGenerator.generate(new BriefingInput(
                classification.classification(), List.of(evidence)));
        if (briefing.briefing() == null) {
            log.warn("briefing not generated source_id={} status={} reason_code={} attempts={}",
                    source.getId(), briefing.status(), briefing.reasonCode(), briefing.attempts());
        }
        BigDecimal severity = severityPolicy.severity(classification.classification());
        BigDecimal relevance = affected.stream()
                .map(id -> request.candidateRelevance().getOrDefault(id, InstrumentMatcher.SECTOR_ONLY))
                .max(Comparator.naturalOrder()).orElse(InstrumentMatcher.SECTOR_ONLY);

        int created = 0;
        int duplicates = 0;
        int below = 0;
        int unevaluable = 0;
        int suppressed = 0;
        for (String agent : agents) {
            AlertGenerationOutcome generation = alertGeneration.generate(new AlertGenerationRequest(
                    source.getId(), classification.classification(), briefing.briefing(), agent,
                    severity, relevance, request.asOf()));
            created += generation.alertsCreated();
            duplicates += generation.duplicates();
            below += generation.belowThreshold();
            unevaluable += generation.unevaluable();
            suppressed += generation.dailyLimitSuppressed();
        }
        return new SourceFanOutOutcome(classification.status(),
                briefing.status() == null ? BriefingStatus.PENDING : briefing.status(),
                briefing.reasonCode(), agents.size(), created, duplicates, below, unevaluable, suppressed);
    }

    public SourceProcessingOutcome process(SourceProcessingRequest request) {
        SourceResult source = sourceResults.findById(request.sourceResultId())
                .orElseThrow(() -> new IllegalArgumentException("source result does not exist"));
        PublicEvidence evidence = new PublicEvidence(source.getId().toString(), source.getTitle(),
                request.excerpt(), source.getCanonicalUrl(), source.getSourceDomain(),
                source.getPublishedAt(), request.candidateInstrumentIds());
        ClassificationOutcome classification = classifier.classify(evidence);
        if (classification.status() != ClassificationStatus.MODEL_ACCEPTED
                || classification.classification() == null) {
            return new SourceProcessingOutcome(classification.status(), null,
                    classification.reasonCode(), null);
        }

        BriefingOutcome briefing = briefingGenerator.generate(new BriefingInput(
                classification.classification(), List.of(evidence)));
        AlertGenerationOutcome generation = alertGeneration.generate(new AlertGenerationRequest(
                source.getId(), classification.classification(), briefing.briefing(),
                request.assignedAgentSubject(), request.eventSeverity(),
                request.retrievalRelevance(), request.asOf()));
        return new SourceProcessingOutcome(classification.status(),
                briefing.status() == null ? BriefingStatus.PENDING : briefing.status(),
                briefing.reasonCode(), generation);
    }
}
