package bank.engagement.integration.sources;

public class SourceNotFoundException extends RuntimeException {

    public SourceNotFoundException() {
        super("follow-up source not found for the caller");
    }
}
