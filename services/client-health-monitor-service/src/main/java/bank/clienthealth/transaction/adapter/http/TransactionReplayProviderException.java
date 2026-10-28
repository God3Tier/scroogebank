package bank.clienthealth.transaction.adapter.http;

final class TransactionReplayProviderException extends RuntimeException {
    TransactionReplayProviderException(String message) {
        super(message);
    }

    TransactionReplayProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
