package bank.alert.ai.evaluation;

import bank.alert.ai.AiSafetyScreen;
import bank.alert.ai.EventType;
import bank.alert.ai.PublicEvidence;
import bank.alert.ai.SafetyDecision;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class BenchmarkFixtureValidator {
    private static final Set<String> LABEL_STATUSES = Set.of(
            "AI_DRAFT_REQUIRES_HUMAN_REVIEW", "HUMAN_REVIEWED");
    private static final Set<String> SAFETY_OUTCOMES = Set.of(
            "PROMPT_INJECTION_DETECTED", "PRIVATE_FIELD_DETECTED");
    private final AiSafetyScreen safetyScreen = new AiSafetyScreen();

    public FixtureValidationSummary validate(BenchmarkFixtureFile fixture) {
        if (!LABEL_STATUSES.contains(fixture.labelStatus())) {
            throw new IllegalArgumentException("unsupported fixture labelStatus");
        }
        if (fixture.examples().size() < 15) {
            throw new IllegalArgumentException("held-out fixture requires at least 15 examples");
        }
        Set<String> ids = new HashSet<>();
        Set<EventType> coveredTypes = EnumSet.noneOf(EventType.class);
        Set<String> coveredSafetyOutcomes = new HashSet<>();
        int classifiable = 0;
        int negatives = 0;
        for (BenchmarkFixtureFile.FixtureExample example : fixture.examples()) {
            validateExample(example, ids);
            if (example.shouldClassify()) {
                classifiable++;
                coveredTypes.add(example.expectedEventType());
            } else {
                negatives++;
            }
            validateSafetyExpectation(example, coveredSafetyOutcomes);
        }
        if (!coveredTypes.containsAll(EnumSet.allOf(EventType.class))) {
            throw new IllegalArgumentException("fixture must cover every event type");
        }
        if (negatives < 3) throw new IllegalArgumentException("fixture requires at least three negatives");
        if (!coveredSafetyOutcomes.containsAll(SAFETY_OUTCOMES)) {
            throw new IllegalArgumentException("fixture must cover injection and private-field safety outcomes");
        }
        return new FixtureValidationSummary(fixture.examples().size(), classifiable, negatives,
                coveredTypes, coveredSafetyOutcomes);
    }

    private static void validateExample(BenchmarkFixtureFile.FixtureExample example, Set<String> ids) {
        if (!ids.add(example.id())) throw new IllegalArgumentException("duplicate fixture ID: " + example.id());
        if (example.title() == null || example.title().isBlank() || example.title().length() > 300
                || example.excerpt() == null || example.excerpt().isBlank() || example.excerpt().length() > 4_000
                || example.candidateInstrumentIds() == null || example.candidateInstrumentIds().isEmpty()
                || example.candidateInstrumentIds().stream().anyMatch(id -> id == null || !id.startsWith("SYN-"))) {
            throw new IllegalArgumentException("invalid synthetic public example: " + example.id());
        }
        if (example.shouldClassify() != (example.expectedEventType() != null)) {
            throw new IllegalArgumentException("classification label mismatch: " + example.id());
        }
        if (example.expectedSafetyOutcome() != null
                && !SAFETY_OUTCOMES.contains(example.expectedSafetyOutcome())) {
            throw new IllegalArgumentException("unsupported safety outcome: " + example.id());
        }
    }

    private void validateSafetyExpectation(BenchmarkFixtureFile.FixtureExample example,
            Set<String> coveredSafetyOutcomes) {
        PublicEvidence evidence = new PublicEvidence(example.id(), example.title(), example.excerpt(),
                "https://example.invalid/" + example.id(), "example.invalid",
                Instant.parse("2026-09-29T00:00:00Z"), example.candidateInstrumentIds());
        SafetyDecision decision = safetyScreen.inspect(evidence, 12_000);
        if (example.expectedSafetyOutcome() == null && !decision.allowed()) {
            throw new IllegalArgumentException("unexpected safety rejection: " + example.id());
        }
        if (example.expectedSafetyOutcome() != null) {
            if (decision.allowed() || !example.expectedSafetyOutcome().equals(decision.reasonCode())) {
                throw new IllegalArgumentException("safety label mismatch: " + example.id());
            }
            coveredSafetyOutcomes.add(example.expectedSafetyOutcome());
        }
    }
}
