package bank.engagement.drafting;

/** The model that writes drafts. Its input is public data and placeholders only (ADR-0054 decision 2). */
public interface DraftModelGateway {

    /**
     * @throws DraftModelException the model did not answer in time or failed
     */
    ModelReply invoke(String systemPrompt, String userInputJson);

    record ModelReply(String text, long inputTokens, long outputTokens) {
    }
}
