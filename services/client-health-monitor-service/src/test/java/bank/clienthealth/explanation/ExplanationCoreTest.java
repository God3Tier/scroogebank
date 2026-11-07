package bank.clienthealth.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.clienthealth.contract.ClientHealthFinding;
import bank.clienthealth.contract.ClientHealthFinding.AnalysisWindow;
import bank.clienthealth.contract.ClientHealthFinding.PriorityInputs;
import bank.clienthealth.contract.ClientHealthFinding.RecurringCreditInterruptionEvidence;
import bank.clienthealth.contract.ClientHealthFinding.Severity;
import bank.clienthealth.contract.ClientHealthFinding.SignalEvidence;
import bank.clienthealth.contract.ClientHealthFinding.SignalType;
import bank.clienthealth.contract.ClientHealthFinding.SignalWindow;
import bank.clienthealth.contract.ClientHealthFinding.SustainedNetOutflowEvidence;
import bank.clienthealth.contract.ClientHealthFinding.TransactionActivityDeclineEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExplanationCoreTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ExplanationSafetyValidator validator = new ExplanationSafetyValidator(json);
    private final ExplanationRequest request = ExplanationRequestFactory.from(finding());

    @Test
    void mapsOnlyAllowedDerivedFacts() throws Exception {
        String serialized = json.writeValueAsString(request);

        assertThat(serialized).contains("RECURRING_CREDIT_INTERRUPTION", "TRANSACTION_ACTIVITY_DECLINE",
                "SUSTAINED_NET_OUTFLOW", "\"expectedCadenceDays\":30", "\"periodCount\":3")
                .doesNotContain("client-sensitive", "finding-sensitive", "account-sensitive",
                        "agent-sensitive", "transaction-sensitive", "12345.67", "merchant text");
    }

    @Test
    void templatesAreDeterministicForEverySignalCombination() {
        TemplateExplanationProvider templates = new TemplateExplanationProvider();
        for (int mask = 1; mask < 8; mask++) {
            int selected = mask;
            var facts = request.facts().stream()
                    .filter(fact -> (selected & (1 << fact.evidenceCode().ordinal())) != 0).toList();
            var subset = new ExplanationRequest("1", ExplanationRequest.PROMPT_VERSION, facts,
                    new ExplanationRequest.Constraints(400, true));

            assertThat(templates.explain(subset)).isEqualTo(templates.explain(subset));
            assertThat(templates.explain(subset).displayText()).hasSizeLessThanOrEqualTo(400);
            assertThat(templates.explain(subset).evidenceCodes()).containsExactlyElementsOf(
                    facts.stream().map(ExplanationRequest.Fact::evidenceCode).toList());
        }
    }

    @Test
    void acceptsStrictSupportedOutput() throws Exception {
        String output = json.writeValueAsString(new Output("1",
                "Verified activity changed across the supplied observation window.",
                "Review the verified account activity before deciding whether contact is appropriate.",
                request.facts().stream().map(ExplanationRequest.Fact::evidenceCode).toList(),
                ExplanationResult.DISCLAIMER));

        assertThat(validator.validate(output, request, "model-test").source())
                .isEqualTo(ExplanationResult.Source.BEDROCK);
    }

    @Test
    void rejectsMalformedUnsupportedOrInventedOutput() throws Exception {
        assertRejected("not-json");
        assertRejected(validJson().replaceFirst("}$", ",\"unexpected\":true}"));
        assertRejected(validJson().replace("SUSTAINED_NET_OUTFLOW", "RECURRING_CREDIT_INTERRUPTION"));
        assertRejected(validJson().replace("Human review required", "Review optional"));
        assertRejected(validJson().replace("Verified activity", "Fraud and financial distress"));
        assertRejected(validJson().replace("Verified activity", "client:secret activity"));
        assertRejected(validJson().replace("Verified activity", "Verified activity changed by 999"));
        assertRejected(validJson().replace(
                "Verified activity", "Verified activity increased by 67 percent"));
        assertRejected(validJson().replace(
                "Verified activity", "Verified activity changed by -66.70 percent"));
        assertRejected(validJson().replace("Verified activity changed", "X".repeat(401)));
    }

    private String validJson() throws Exception {
        return json.writeValueAsString(new Output("1", "Verified activity changed.",
                "Review the verified account activity before deciding whether contact is appropriate.",
                request.facts().stream().map(ExplanationRequest.Fact::evidenceCode).toList(),
                ExplanationResult.DISCLAIMER));
    }

    private void assertRejected(String output) {
        assertThatThrownBy(() -> validator.validate(output, request, "model-test"))
                .isInstanceOf(ExplanationSafetyValidator.UnsafeExplanationException.class);
    }

    private static ClientHealthFinding finding() {
        Instant end = Instant.parse("2026-10-26T00:00:00Z");
        Instant baseline = end.minusSeconds(120L * 86_400);
        Instant comparison = end.minusSeconds(30L * 86_400);
        SignalWindow window = new SignalWindow(baseline, comparison, comparison, end);
        return new ClientHealthFinding("1", "finding-sensitive", "client-sensitive", "run-sensitive",
                "rules-v1", new AnalysisWindow(baseline, end), List.of(
                new SignalEvidence<>("signal-1", "account-sensitive",
                        SignalType.RECURRING_CREDIT_INTERRUPTION, "rule-v1", Severity.HIGH, window,
                        new RecurringCreditInterruptionEvidence(30, 3, 67), "a".repeat(64)),
                new SignalEvidence<>("signal-2", "account-sensitive",
                        SignalType.TRANSACTION_ACTIVITY_DECLINE, "rule-v1", Severity.MEDIUM, window,
                        new TransactionActivityDeclineEvidence(12, 4, new BigDecimal("-66.70")),
                        "b".repeat(64)),
                new SignalEvidence<>("signal-3", "account-sensitive",
                        SignalType.SUSTAINED_NET_OUTFLOW, "rule-v1", Severity.HIGH, window,
                        new SustainedNetOutflowEvidence(3, new BigDecimal("12345.67"),
                                new BigDecimal("23456.78"), new BigDecimal("-11111.11"), "SGD",
                                new BigDecimal("-45.50")),
                        "c".repeat(64))), new PriorityInputs(Severity.HIGH, 3),
                "correlation-sensitive", end);
    }

    private record Output(String schemaVersion, String summary, String suggestedReviewStep,
            List<SignalType> evidenceCodes, String disclaimer) { }
}
