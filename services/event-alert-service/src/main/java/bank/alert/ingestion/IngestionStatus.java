package bank.alert.ingestion;

public enum IngestionStatus {
    DISABLED,
    CONTROL_BLOCKED,
    NO_QUERIES,
    ALREADY_RUNNING,
    ALREADY_SUCCEEDED,
    SUCCEEDED,
    FAILED
}
