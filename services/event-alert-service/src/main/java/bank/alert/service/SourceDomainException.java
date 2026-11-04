package bank.alert.service;

import org.springframework.http.HttpStatus;

/** A refused search-domain request. The code is stable for the UI; the message carries no private data. */
public class SourceDomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public SourceDomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static SourceDomainException invalid(String code, String message) {
        return new SourceDomainException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static SourceDomainException conflict(String code, String message) {
        return new SourceDomainException(HttpStatus.CONFLICT, code, message);
    }

    public static SourceDomainException forbidden() {
        return new SourceDomainException(HttpStatus.FORBIDDEN, "NOT_HELD",
                "you can only change the sources of a company one of your clients holds");
    }

    public static SourceDomainException notFound() {
        return new SourceDomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "company or domain not found");
    }
}
