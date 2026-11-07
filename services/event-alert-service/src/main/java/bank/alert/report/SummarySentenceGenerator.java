package bank.alert.report;

import bank.alert.ai.AiModelGateway;
import bank.alert.ai.AiModelRequest;
import bank.alert.ai.AiProviderException;
import bank.alert.config.AiProperties;
import bank.alert.config.ReportReviewProperties;
import bank.alert.operations.OutboundControlService;
import bank.alert.operations.ReservationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One sentence per report version, for the news email's summary (news-alert-drafts plan, section 6). The worker
 * writes it straight after the report, as a separate model call that sees only that report's public text: never a
 * client, never which other reports go with it, never an agent's edit. It is written once and reused by every
 * agent and client, so its cost grows with reports, not clients. A sentence that fails a check is not stored, and
 * the email uses the report's first sentence instead.
 */
@Service
public class SummarySentenceGenerator {

    static final String OPERATION = "WRITE_REPORT_SUMMARY_SENTENCE";
    static final String PROMPT_VERSION = "alert-report-summary-sentence-v1";
    static final int MAX_LENGTH = 250;
    private static final int OUTPUT_TOKENS = 200;

    static final String OUTPUT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "properties":{"sentence":{"type":"string","maxLength":250}},
             "required":["sentence"]}
            """;

    private static final String SYSTEM_INSTRUCTION = "Write one plain sentence of at most 250 characters saying what "
            + "happened to the named company and why it matters, using only the supplied report. Name the company. "
            + "Use only figures that appear in the report. Do not give advice, do not mention any client or portfolio, "
            + "do not include citation markers, and return only the closed schema.";

    private static final Pattern CITATION = Pattern.compile("\\[(?:\\d+|src-[^\\]]*)\\]");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("[.!?]\\s+[A-Z]");

    /** The outcome: the sentence, or null with the reason it was not stored. */
    public record Outcome(String sentence, String reasonCode, String model) {
    }

    private final AiProperties ai;
    private final ReportReviewProperties properties;
    private final AiModelGateway gateway;
    private final OutboundControlService outboundControls;
    private final ObjectMapper mapper;

    public SummarySentenceGenerator(AiProperties ai, ReportReviewProperties properties, AiModelGateway gateway,
            OutboundControlService outboundControls, ObjectMapper mapper) {
        this.ai = ai;
        this.properties = properties;
        this.gateway = gateway;
        this.outboundControls = outboundControls;
        this.mapper = mapper;
    }

    public Outcome write(JsonNode report, String issuer, String ticker) {
        if (!properties.summaryEnabled()) return new Outcome(null, "SUMMARY_DISABLED", null);
        if (!ai.enabled()) return new Outcome(null, "MODEL_DISABLED", null);
        String input;
        try {
            input = mapper.writeValueAsString(Map.of("company", issuer, "ticker", ticker == null ? "" : ticker,
                    "report", publicText(report)));
        } catch (JsonProcessingException e) {
            return new Outcome(null, "INPUT_SERIALIZATION_FAILED", null);
        }
        if (input.length() > ai.maxInputCharacters()) return new Outcome(null, "INPUT_TOO_LARGE", null);
        ReservationResult reservation = outboundControls.reserveModelTokens(OUTPUT_TOKENS);
        if (!reservation.allowed()) return new Outcome(null, reservation.code(), null);
        try {
            String raw = gateway.invokeMeasured(new AiModelRequest(OPERATION, ai.model(), PROMPT_VERSION,
                    SYSTEM_INSTRUCTION, input, OUTPUT_SCHEMA, OUTPUT_TOKENS), ai.timeout()).outputJson();
            String sentence = mapper.readTree(raw).path("sentence").asText("").strip();
            String refused = check(sentence, report, issuer, ticker);
            return refused == null ? new Outcome(sentence, null, ai.model()) : new Outcome(null, refused, ai.model());
        } catch (AiProviderException failure) {
            return new Outcome(null, "PROVIDER_FAILURE", ai.model());
        } catch (JsonProcessingException failure) {
            return new Outcome(null, "INVALID_MODEL_JSON", ai.model());
        } catch (RuntimeException failure) {
            return new Outcome(null, "UNEXPECTED_MODEL_FAILURE", ai.model());
        }
    }

    /** Returns the reason the sentence must not be stored, or null when it may be. */
    static String check(String sentence, JsonNode report, String issuer, String ticker) {
        if (sentence == null || sentence.isBlank()) return "EMPTY_SENTENCE";
        if (sentence.length() > MAX_LENGTH) return "SENTENCE_TOO_LONG";
        if (SENTENCE_BREAK.matcher(sentence).find()) return "MORE_THAN_ONE_SENTENCE";
        if (CITATION.matcher(sentence).find()) return "CITATION_MARKER";
        String lower = sentence.toLowerCase(Locale.ROOT);
        if (ReportEdits.ADVICE_PHRASES.stream().anyMatch(lower::contains)) return "ADVICE_LANGUAGE_DETECTED";
        if (!namesIssuer(lower, issuer, ticker)) return "ISSUER_NOT_NAMED";
        String reportText = publicText(report).toLowerCase(Locale.ROOT).replaceAll("(?<=\\d),(?=\\d)", "");
        for (String figure : ReportEdits.numbers(sentence)) {
            Pattern whole = Pattern.compile("(?<![\\d.])" + Pattern.quote(figure) + "(?![\\d])");
            if (!whole.matcher(reportText).find()) return "UNSUPPORTED_FIGURE";
        }
        return null;
    }

    /** The issuer counts as named by its full name, its first word ("Apple" for "Apple Inc") or its ticker. */
    private static boolean namesIssuer(String lowerSentence, String issuer, String ticker) {
        if (issuer != null && !issuer.isBlank()) {
            String name = issuer.toLowerCase(Locale.ROOT);
            if (lowerSentence.contains(name)) return true;
            String first = name.split("[\\s,]+")[0];
            if (first.length() >= 3 && lowerSentence.contains(first)) return true;
        }
        return ticker != null && !ticker.isBlank() && lowerSentence.contains(ticker.toLowerCase(Locale.ROOT));
    }

    /** The report's words, without its source references. */
    static String publicText(JsonNode report) {
        StringBuilder text = new StringBuilder(report.path("headline").asText(""));
        for (String section : List.of("executiveSummary", "whyItMatters")) {
            report.path(section).forEach(item -> text.append(' ').append(item.path("text").asText("")));
        }
        report.path("keyFacts").forEach(fact -> text.append(' ').append(fact.path("label").asText("")).append(' ')
                .append(fact.path("value").asText("")).append(' ').append(fact.path("context").asText("")));
        report.path("whatToMonitor").forEach(item -> text.append(' ').append(item.asText("")));
        text.append(' ').append(report.path("uncertainty").asText(""));
        return text.toString().strip();
    }
}
