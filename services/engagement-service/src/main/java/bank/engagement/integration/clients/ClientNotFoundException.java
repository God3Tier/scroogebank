package bank.engagement.integration.clients;

public class ClientNotFoundException extends RuntimeException {

    public ClientNotFoundException() {
        super("client not found for the caller");
    }
}
