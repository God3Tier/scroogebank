package bank.alert.crm;

/** The CRM could not be asked. This is "unknown", never "deleted": callers fail closed. */
public class DirectoryUnavailableException extends RuntimeException {

    public DirectoryUnavailableException(String message) {
        super(message);
    }

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
