package bank.alert.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SafeBriefingGeneratorTest {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Test
    void generatesCitedAiLabelledBriefing() {
        SafeBriefingGenerator generator = generator((request, timeout) -> """
                {"abstain":false,"abstentionReason":null,"headline":"Synthetic issuer update",
                "summary":"The issuer reported a synthetic earnings change.",
                "uncertainty":"The filing does not provide forward guidance.",
                "sourceRefs":["source-1"],
                "suggestedReviewActions":["Review the cited filing and current alert factors."]}
                """, true);

        BriefingOutcome outcome = generator.generate(input());

        assertThat(outcome.status()).isEqualTo(BriefingStatus.GENERATED);
        assertThat(outcome.briefing().aiGenerated()).isTrue();
        assertThat(outcome.briefing().sourceRefs()).containsExactly("source-1");
        assertThat(outcome.briefing().generatedAt()).isEqualTo(NOW);
    }

    @Test
    void acceptsACitationByTheSourcesUrlAndStoresItsReferenceId() {
        SafeBriefingGenerator generator = generator((request, timeout) -> """
                {"abstain":false,"abstentionReason":null,"headline":"Synthetic issuer update",
                "summary":"The issuer reported a synthetic earnings change.",
                "uncertainty":"The filing does not provide forward guidance.",
                "sourceRefs":["https://example.invalid/source"],
                "suggestedReviewActions":["Review the cited filing and current alert factors."]}
                """, true);

        BriefingOutcome outcome = generator.generate(input());

        assertThat(outcome.status()).isEqualTo(BriefingStatus.GENERATED);
        assertThat(outcome.briefing().sourceRefs()).containsExactly("source-1");
    }

    @Test
    void stillRejectsACitationOfASourceThatWasNotSupplied() {
        SafeBriefingGenerator generator = generator((request, timeout) -> """
                {"abstain":false,"abstentionReason":null,"headline":"Synthetic issuer update",
                "summary":"The issuer reported a synthetic earnings change.",
                "uncertainty":"The filing does not provide forward guidance.",
                "sourceRefs":["https://elsewhere.invalid/other"],
                "suggestedReviewActions":["Review the cited filing and current alert factors."]}
                """, true);

        BriefingOutcome outcome = generator.generate(input());

        assertThat(outcome.status()).isEqualTo(BriefingStatus.PENDING);
        assertThat(outcome.reasonCode()).isEqualTo("MISSING_OR_UNKNOWN_CITATION");
    }

    @Test
    void rejectsAdviceLanguageAndLeavesBriefPending() {
        SafeBriefingGenerator generator = generator((request, timeout) -> """
                {"abstain":false,"abstentionReason":null,"headline":"Synthetic update",
                "summary":"You should buy this instrument now.","uncertainty":"Terms may change.",
                "sourceRefs":["source-1"],"suggestedReviewActions":["Review source."]}
                """, true);

        BriefingOutcome outcome = generator.generate(input());

        assertThat(outcome.status()).isEqualTo(BriefingStatus.PENDING);
        assertThat(outcome.reasonCode()).isEqualTo("ADVICE_LANGUAGE_DETECTED");
        assertThat(outcome.briefing()).isNull();
    }

    @Test
    void killSwitchMakesNoModelCall() {
        AtomicInteger calls = new AtomicInteger();
        SafeBriefingGenerator generator = generator((request, timeout) -> {
            calls.incrementAndGet(); return "{}";
        }, false);

        BriefingOutcome outcome = generator.generate(input());

        assertThat(calls).hasValue(0);
        assertThat(outcome.status()).isEqualTo(BriefingStatus.PENDING);
        assertThat(outcome.reasonCode()).isEqualTo("MODEL_DISABLED");
    }

    private static SafeBriefingGenerator generator(AiModelGateway gateway, boolean enabled) {
        return new SafeBriefingGenerator(SafeEventClassifierTest.properties(enabled), gateway,
                new AiSafetyScreen(), new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BriefingInput input() {
        PublicEvidence evidence = SafeEventClassifierTest.evidence("Synthetic earnings increased.");
        EventClassification classification = new EventClassification("Synthetic earnings event",
                EventType.EARNINGS, EventDirection.POSITIVE, new BigDecimal("0.82"),
                List.of(new AffectedInstrument("SYN-SGX-001", "source-1")), List.of("source-1"),
                "global.amazon.nova-2-lite-v1:0", "alert-public-v1", NOW);
        return new BriefingInput(classification, List.of(evidence));
    }
}
