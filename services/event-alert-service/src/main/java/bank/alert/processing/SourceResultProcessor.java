package bank.alert.processing;

import bank.alert.ai.BriefingGenerator;
import bank.alert.ai.BriefingInput;
import bank.alert.ai.BriefingOutcome;
import bank.alert.ai.BriefingStatus;
import bank.alert.ai.ClassificationOutcome;
import bank.alert.ai.ClassificationStatus;
import bank.alert.ai.EventClassifier;
import bank.alert.ai.PublicEvidence;
import bank.alert.domain.SourceResult;
import bank.alert.repository.SourceResultRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SourceResultProcessor {

    private final SourceResultRepository sourceResults;
    private final EventClassifier classifier;
    private final BriefingGenerator briefingGenerator;
    private final AlertGenerationService alertGeneration;

    public SourceResultProcessor(SourceResultRepository sourceResults, EventClassifier classifier,
            BriefingGenerator briefingGenerator, AlertGenerationService alertGeneration) {
        this.sourceResults = sourceResults;
        this.classifier = classifier;
        this.briefingGenerator = briefingGenerator;
        this.alertGeneration = alertGeneration;
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
