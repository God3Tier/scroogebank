package bank.alert.ai;

import java.time.Instant;
import java.util.List;

public record AgentBriefing(String headline, String summary, String uncertainty,
        List<String> sourceRefs, List<String> suggestedReviewActions, boolean aiGenerated,
        String modelVersion, String promptVersion, Instant generatedAt) {
    public AgentBriefing {
        sourceRefs = List.copyOf(sourceRefs);
        suggestedReviewActions = List.copyOf(suggestedReviewActions);
    }
}
