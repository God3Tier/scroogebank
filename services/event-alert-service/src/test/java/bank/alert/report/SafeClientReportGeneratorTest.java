package bank.alert.report;

import bank.alert.ai.AiModelGateway;
import bank.alert.ai.AiModelRequest;
import bank.alert.ai.AiProviderException;
import bank.alert.ai.AiSafetyScreen;
import bank.alert.ai.PublicEvidence;
import bank.alert.config.AiProperties;
import bank.alert.operations.OutboundControlService;
import bank.alert.operations.ReservationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SafeClientReportGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    private static final String GOOD = """
            {"abstain":false,"abstentionReason":null,"headline":"Microsoft reports first quarter results",
             "executiveSummary":[{"text":"Microsoft reported first quarter revenue of $92,279 million.","sourceRefs":["source-1"]},
                                 {"text":"Revenue rose 19% from a year earlier.","sourceRefs":["source-1"]}],
             "keyFacts":[{"label":"Revenue","value":"$92,279 million","context":"Up 19% on the year","sourceRefs":["source-1"]}],
             "whyItMatters":[{"text":"Growth was strong across the quarter.","kind":"FACT","sourceRefs":["source-1"]},
                             {"text":"Cloud demand may support further growth.","kind":"POTENTIAL_IMPLICATION","sourceRefs":[]}],
             "marketContext":null,
             "whatToMonitor":["Azure growth next quarter","Guidance on capital spending"],
             "uncertainty":"Only one excerpt was supplied.","sourceRefs":["source-1"]}
            """;

    private final AtomicReference<AiModelRequest> sent = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    private static AiProperties properties(boolean enabled) {
        return new AiProperties(enabled, "test-model", "alert-public-v1", 12_000, 8_000, 1_200, 2,
                Duration.ofSeconds(10), new BigDecimal("0.60"));
    }

    private SafeClientReportGenerator generator(AiModelGateway gateway, boolean enabled) {
        OutboundControlService controls = mock(OutboundControlService.class);
        when(controls.reserveModelTokens(anyInt())).thenReturn(ReservationResult.allowed(1_000));
        AiModelGateway recording = (request, timeout) -> {
            sent.set(request);
            calls.incrementAndGet();
            return gateway.invoke(request, timeout);
        };
        return new SafeClientReportGenerator(properties(enabled), recording, new AiSafetyScreen(),
                new ObjectMapper().findAndRegisterModules(), controls, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ReportInput input() {
        PublicEvidence evidence = new PublicEvidence("source-1", "Microsoft announces first quarter results",
                "Revenue was $92,279 million, up 19% from a year earlier.", "https://news.example/a", "news.example",
                Instant.parse("2026-11-04T00:00:00Z"), List.of("NASDAQ-MSFT"));
        return new ReportInput(
                List.of(new ReportInput.InstrumentRef("NASDAQ-MSFT", "MSFT", "Microsoft Corporation")), List.of(evidence));
    }

    @Test
    void theModelIsToldWhatAnEarlierReportAlreadySaidAndIsAskedToWriteOnlyWhatIsNew() {
        ReportInput withEarlier = new ReportInput(input().instruments(), input().evidence(),
                List.of(new ReportInput.PriorReport("Microsoft earlier headline", List.of("Microsoft guided revenue higher."))));

        ReportOutcome outcome = generator((request, timeout) -> GOOD, true).generate(withEarlier);

        assertThat(outcome.status()).isEqualTo(ReportOutcome.Status.GENERATED);
        assertThat(sent.get().promptVersion()).isEqualTo("alert-client-report-v2.2");
        assertThat(outcome.promptVersion()).isEqualTo("alert-client-report-v2.2");
        assertThat(sent.get().systemInstruction()).contains("alreadyReported").contains("write only what is new")
                .contains("several different developments");
        assertThat(sent.get().publicInputJson()).contains("Microsoft earlier headline").contains("Microsoft guided revenue higher.");
    }

    @Test
    void withNothingEarlierTheModelIsToldNothingAboutAPriorReport() {
        generator((request, timeout) -> GOOD, true).generate(input());

        assertThat(sent.get().publicInputJson()).contains("\"alreadyReported\":[]");
    }

    @Test
    void writesAValidatedReportLabelledAsTheV2Schema() {
        ReportOutcome outcome = generator((request, timeout) -> GOOD, true).generate(input());

        assertThat(outcome.status()).isEqualTo(ReportOutcome.Status.GENERATED);
        assertThat(outcome.report().headline()).isEqualTo("Microsoft reports first quarter results");
        assertThat(outcome.report().executiveSummary()).hasSize(2);
        assertThat(outcome.report().marketContext()).isNull();
        assertThat(outcome.promptVersion()).isEqualTo("alert-client-report-v2.2");
        assertThat(outcome.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void tellsTheModelOnlyPublicThingsAndAsksForTheClosedSchema() {
        generator((request, timeout) -> GOOD, true).generate(input());

        AiModelRequest request = sent.get();
        assertThat(request.operation()).isEqualTo("GENERATE_CLIENT_REPORT");
        assertThat(request.outputSchema()).contains("executiveSummary", "keyFacts", "whyItMatters", "marketContext", "whatToMonitor");
        assertThat(request.systemInstruction()).contains("do not mention any client or portfolio");
        assertThat(request.publicInputJson()).contains("Microsoft Corporation", "source-1")
                .doesNotContain("client", "account", "holding", "portfolio");
    }

    @Test
    void doesNothingWhileTheModelIsSwitchedOff() {
        ReportOutcome outcome = generator((request, timeout) -> GOOD, false).generate(input());

        assertThat(outcome.status()).isEqualTo(ReportOutcome.Status.PENDING);
        assertThat(outcome.reasonCode()).isEqualTo("MODEL_DISABLED");
        assertThat(calls.get()).isZero();
    }

    @Test
    void anInvalidReportIsRefusedNotRepairedAndTriedOnceMore() {
        String invented = GOOD.replace("$92,279 million\",\"context", "$99,999 million\",\"context");

        ReportOutcome outcome = generator((request, timeout) -> invented, true).generate(input());

        assertThat(outcome.status()).isEqualTo(ReportOutcome.Status.ABSTAINED);
        assertThat(outcome.reasonCode()).isEqualTo("UNSUPPORTED_FIGURE");
        assertThat(outcome.report()).isNull();
        assertThat(calls.get()).isEqualTo(2);
    }

    private static final String ONE_SENTENCE = GOOD.replaceAll(
            "\\s*,\\s*\\{\"text\":\"Revenue rose 19% from a year earlier\\.\",\"sourceRefs\":\\[\"source-1\"\\]\\}", "");

    @Test
    void aOneSentenceSummaryIsRefusedAtFirst() {
        assertThat(ONE_SENTENCE).as("the test draft really has one sentence").doesNotContain("Revenue rose 19%");

        ReportOutcome outcome = generator((request, timeout) -> ONE_SENTENCE, true).generate(input());

        assertThat(outcome.status()).isEqualTo(ReportOutcome.Status.ABSTAINED);
        assertThat(outcome.reasonCode()).isEqualTo("INVALID_MODEL_SCHEMA");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aOneSentenceSummaryIsAcceptedOnceThreeDraftsOfTheEventHaveBeenRefused() {
        ReportOutcome afterTwo = generator((request, timeout) -> ONE_SENTENCE, true).generate(input(), 2);
        // Two refused drafts so far: the first try of this call is still strict, the second (the third refusal) relaxes.
        assertThat(afterTwo.status()).isEqualTo(ReportOutcome.Status.GENERATED);
        assertThat(afterTwo.attempts()).isEqualTo(2);

        calls.set(0);
        ReportOutcome afterThree = generator((request, timeout) -> ONE_SENTENCE, true).generate(input(), 3);
        assertThat(afterThree.status()).isEqualTo(ReportOutcome.Status.GENERATED);
        assertThat(afterThree.attempts()).as("accepted at the first try").isEqualTo(1);
        assertThat(afterThree.report().executiveSummary()).hasSize(1);
    }

    @Test
    void theRefusedDraftsOfOneEventNeverAddUpToMoreThanFive() {
        calls.set(0);
        ReportOutcome fourSoFar = generator((request, timeout) -> "{}", true).generate(input(), 4);

        assertThat(calls.get()).as("one draft left, not two").isEqualTo(1);
        assertThat(fourSoFar.attempts()).isEqualTo(1);
    }

    @Test
    void relaxingOnlyAllowsTheShortSummaryAndNothingElse() {
        String invented = ONE_SENTENCE.replace("$92,279 million\",\"context", "$99,999 million\",\"context");

        ReportOutcome outcome = generator((request, timeout) -> invented, true).generate(input(), 4);

        assertThat(outcome.status()).isEqualTo(ReportOutcome.Status.ABSTAINED);
        assertThat(outcome.reasonCode()).isEqualTo("UNSUPPORTED_FIGURE");
    }

    @Test
    void theModelIsToldTheExactSizesTheReportMustHave() {
        generator((request, timeout) -> GOOD, true).generate(input());

        assertThat(sent.get().systemInstruction()).contains("2 or 3 sentences, never 1", "at most 300 characters", "at most 4");
    }

    @Test
    void marketDataAndAdviceAndMalformedJsonAreRefused() {
        assertThat(generator((request, timeout) -> GOOD.replace("\"marketContext\":null", "\"marketContext\":\"Shares closed at $420.\""), true)
                .generate(input()).reasonCode()).isEqualTo("MARKET_CONTEXT_NOT_ALLOWED");
        assertThat(generator((request, timeout) -> GOOD.replace("Growth was strong across the quarter.", "You should buy now."), true)
                .generate(input()).reasonCode()).isEqualTo("ADVICE_LANGUAGE_DETECTED");
        assertThat(generator((request, timeout) -> "{not json", true).generate(input()).reasonCode()).isEqualTo("INVALID_MODEL_JSON");
        assertThat(generator((request, timeout) -> GOOD.replace("\"abstain\":false,", "\"abstain\":false,\"extra\":1,"), true)
                .generate(input()).reasonCode()).isEqualTo("INVALID_MODEL_JSON");
    }

    @Test
    void aModelAbstentionIsNotAReport() {
        ReportOutcome outcome = generator((request, timeout) -> "{\"abstain\":true,\"abstentionReason\":\"Evidence too thin\","
                + "\"headline\":null,\"executiveSummary\":[],\"keyFacts\":[],\"whyItMatters\":[],\"marketContext\":null,"
                + "\"whatToMonitor\":[],\"uncertainty\":null,\"sourceRefs\":[]}", true).generate(input());

        assertThat(outcome.status()).isEqualTo(ReportOutcome.Status.ABSTAINED);
        assertThat(outcome.reasonCode()).isEqualTo("MODEL_ABSTAINED");
    }

    @Test
    void aProviderOutageLeavesTheReportToBeTriedLater() {
        ReportOutcome outcome = generator((request, timeout) -> {
            throw new AiProviderException("MODEL_UNAVAILABLE", true, "down");
        }, true).generate(input());

        assertThat(outcome.status()).isEqualTo(ReportOutcome.Status.PENDING);
        assertThat(outcome.reasonCode()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aNonRetryableProviderFailureStopsAtOnce() {
        ReportOutcome outcome = generator((request, timeout) -> {
            throw new AiProviderException("MODEL_ACCESS_DENIED", false, "no");
        }, true).generate(input());

        assertThat(outcome.status()).isEqualTo(ReportOutcome.Status.PENDING);
        assertThat(calls.get()).isEqualTo(1);
    }
}
