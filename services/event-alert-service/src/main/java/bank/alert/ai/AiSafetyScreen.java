package bank.alert.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class AiSafetyScreen {
    private static final List<String> INJECTION_MARKERS = List.of(
            "ignore previous instructions", "ignore all instructions", "system prompt",
            "developer message", "follow these instructions", "you are chatgpt", "reveal your prompt");
    private static final List<Pattern> PRIVATE_MARKERS = List.of(
            Pattern.compile("(?i)\\bsyn-client-[0-9]{3}\\b"),
            Pattern.compile("(?i)\\bsyn-account-[0-9]{3}\\b"),
            Pattern.compile("(?i)\\bsyn-agent-[0-9]{3}\\b"),
            Pattern.compile("(?i)\\b(?:account|client)[ _-]?number\\s*[:=]"),
            Pattern.compile("(?i)\\binternal[ _-]?note\\s*[:=]"));

    public SafetyDecision inspect(PublicEvidence evidence, int maximumCharacters) {
        String combined = evidence.title() + "\n" + evidence.excerpt();
        if (combined.length() > maximumCharacters) return new SafetyDecision(false, "INPUT_TOO_LARGE");
        String lowered = combined.toLowerCase(Locale.ROOT);
        if (INJECTION_MARKERS.stream().anyMatch(lowered::contains)) {
            return new SafetyDecision(false, "PROMPT_INJECTION_DETECTED");
        }
        if (PRIVATE_MARKERS.stream().anyMatch(pattern -> pattern.matcher(combined).find())) {
            return new SafetyDecision(false, "PRIVATE_FIELD_DETECTED");
        }
        return new SafetyDecision(true, null);
    }
}
