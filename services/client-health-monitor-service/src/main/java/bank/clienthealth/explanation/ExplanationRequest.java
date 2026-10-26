package bank.clienthealth.explanation;

import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

public record ExplanationRequest(
        String schemaVersion, String promptTemplateVersion, List<Fact> facts, Constraints constraints
) {
    public static final String SCHEMA_VERSION = "1";
    public static final String PROMPT_VERSION = "relationship-health-explanation-v1";

    public ExplanationRequest {
        facts = List.copyOf(facts);
        if (!SCHEMA_VERSION.equals(schemaVersion) || facts.isEmpty()) {
            throw new IllegalArgumentException("A versioned request with facts is required");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fact(
            SignalType evidenceCode, String ruleVersion, int observationWindowDays,
            Integer expectedCadenceDays, Integer daysSinceLastOccurrence,
            Integer previousOccurrenceCount, Integer baselineMonthlyAverageCount,
            Integer comparisonCount, BigDecimal changePercent, Integer periodCount,
            BigDecimal changeFromBaselinePercent
    ) { }

    public record Constraints(int maximumCharacters, boolean humanReviewRequired) { }
}
