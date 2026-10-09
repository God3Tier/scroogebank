package bank.alert.ingestion;

public record RetryResult<T>(T value, int attempts) {
}
