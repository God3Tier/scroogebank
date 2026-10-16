package bank.alert.api;

public record BriefingResponse(
        String summary,
        boolean aiGenerated,
        String modelVersion,
        String promptVersion,
        String uncertainty) {
}
