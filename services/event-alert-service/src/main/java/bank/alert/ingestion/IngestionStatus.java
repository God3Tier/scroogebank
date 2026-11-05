package bank.alert.ingestion;

public enum IngestionStatus {
    DISABLED,
    CONTROL_BLOCKED,
    NO_QUERIES,
    INVALID_FILTER,
    ALREADY_RUNNING,
    ALREADY_SUCCEEDED,
    SUCCEEDED,
    FAILED
}
