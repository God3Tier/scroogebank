package bank.alert.ingestion;

public enum IngestionStatus {
    DISABLED,
    NO_QUERIES,
    ALREADY_RUNNING,
    ALREADY_SUCCEEDED,
    SUCCEEDED,
    FAILED
}
