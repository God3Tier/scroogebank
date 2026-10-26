package bank.clienthealth.explanation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Strictly parses model output and rejects unsupported facts or unsafe claims. */
public final class ExplanationSafetyValidator {
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?i)\\b(?:client|account|agent|transaction|task|finding)[_:#-][a-z0-9._:-]+|"
                    + "\\b[a-f0-9]{64}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b");
    private static final Pattern MONEY = Pattern.compile("[$£€]|\\b[A-Z]{3}\\s*-?\\d");
    private static final Pattern PROHIBITED = Pattern.compile(
            "(?i)\\b(fraud|fraudulent|money laundering|hardship|financial distress|"
                    + "attrition|churn|leaving the bank|salary|creditworthy|loan|investment|product)\\b");
    private final ObjectMapper mapper;

    public ExplanationSafetyValidator(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public ExplanationResult validate(String json, ExplanationRequest request, String modelId) {
        final ModelOutput output;
        try {
            output = mapper.readValue(json, ModelOutput.class);
        } catch (JsonProcessingException invalid) {
            throw new UnsafeExplanationException("INVALID_JSON");
        }
        Set<?> requested = request.facts().stream().map(ExplanationRequest.Fact::evidenceCode)
                .collect(java.util.stream.Collectors.toSet());
        if (!"1".equals(output.schemaVersion()) || output.evidenceCodes() == null
                || output.evidenceCodes().size() != requested.size()
                || !new HashSet<>(output.evidenceCodes()).equals(requested)) {
            throw new UnsafeExplanationException("INVALID_EVIDENCE_CODES");
        }
        requireText(output.summary(), "summary");
        requireText(output.suggestedReviewStep(), "suggestedReviewStep");
        if (!ExplanationResult.DISCLAIMER.equals(output.disclaimer())) {
            throw new UnsafeExplanationException("MISSING_DISCLAIMER");
        }
        String text = String.join(" ", output.summary(), output.suggestedReviewStep(), output.disclaimer() + ".");
        if (text.length() > request.constraints().maximumCharacters()) {
            throw new UnsafeExplanationException("OUTPUT_TOO_LONG");
        }
        if (IDENTIFIER.matcher(text).find() || MONEY.matcher(text).find() || PROHIBITED.matcher(text).find()) {
            throw new UnsafeExplanationException("UNSUPPORTED_CLAIM");
        }
        Set<String> allowedNumbers = allowedNumbers(request);
        var numbers = NUMBER.matcher(text);
        while (numbers.find()) {
            if (!allowedNumbers.contains(normalize(numbers.group()))) {
                throw new UnsafeExplanationException("INVENTED_NUMBER");
            }
        }
        return new ExplanationResult("1", output.summary(), output.suggestedReviewStep(),
                output.evidenceCodes(), output.disclaimer(), ExplanationResult.Source.BEDROCK, modelId);
    }

    private static Set<String> allowedNumbers(ExplanationRequest request) {
        Set<String> allowed = new HashSet<>();
        request.facts().forEach(fact -> {
            add(allowed, fact.observationWindowDays()); add(allowed, fact.expectedCadenceDays());
            add(allowed, fact.daysSinceLastOccurrence()); add(allowed, fact.previousOccurrenceCount());
            add(allowed, fact.baselineMonthlyAverageCount()); add(allowed, fact.comparisonCount());
            add(allowed, fact.changePercent()); add(allowed, fact.periodCount());
            add(allowed, fact.changeFromBaselinePercent());
        });
        return allowed;
    }

    private static void add(Set<String> target, Number value) {
        if (value != null) {
            String normalized = normalize(value.toString());
            target.add(normalized); target.add(normalized.replaceFirst("^-", ""));
        }
    }

    private static String normalize(String value) {
        return new BigDecimal(value).stripTrailingZeros().toPlainString().toLowerCase(Locale.ROOT);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new UnsafeExplanationException("MISSING_" + field.toUpperCase(Locale.ROOT));
    }

    private record ModelOutput(
            String schemaVersion, String summary, String suggestedReviewStep,
            java.util.List<bank.clienthealth.contract.ClientHealthFinding.SignalType> evidenceCodes,
            String disclaimer
    ) { }

    public static final class UnsafeExplanationException extends RuntimeException {
        public UnsafeExplanationException(String reason) { super(reason); }
    }
}
