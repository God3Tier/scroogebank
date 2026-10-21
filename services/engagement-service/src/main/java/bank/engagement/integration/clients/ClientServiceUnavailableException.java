package bank.engagement.integration.clients;

public class ClientServiceUnavailableException extends RuntimeException {

    public ClientServiceUnavailableException(String message) {
        super(message);
    }

    public ClientServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
