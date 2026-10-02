package bank.alert.ai;

import java.util.List;

public record BriefingModelOutput(Boolean abstain, String abstentionReason, String headline,
        String summary, String uncertainty, List<String> sourceRefs,
        List<String> suggestedReviewActions) { }
