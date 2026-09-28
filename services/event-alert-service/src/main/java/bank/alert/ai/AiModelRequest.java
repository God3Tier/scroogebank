package bank.alert.ai;

public record AiModelRequest(String operation, String model, String promptVersion,
        String systemInstruction, String publicInputJson, String outputSchema,
        int maximumOutputTokens) {
}
