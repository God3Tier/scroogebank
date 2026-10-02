package bank.alert.ai.evaluation;

import bank.alert.ai.EventType;

import java.util.Set;

public record FixtureValidationSummary(int examples, int classifiable, int negatives,
        Set<EventType> coveredEventTypes, Set<String> coveredSafetyOutcomes) {
    public FixtureValidationSummary {
        coveredEventTypes = Set.copyOf(coveredEventTypes);
        coveredSafetyOutcomes = Set.copyOf(coveredSafetyOutcomes);
    }
}
