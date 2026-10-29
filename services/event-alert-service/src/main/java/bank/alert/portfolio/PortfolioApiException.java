package bank.alert.portfolio;

import org.springframework.http.HttpStatus;

/** A refused holdings request. The status says why; the message never carries private data. */
public class PortfolioApiException extends RuntimeException {

    private final HttpStatus status;

    public PortfolioApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static PortfolioApiException notFound() {
        return new PortfolioApiException(HttpStatus.NOT_FOUND, "account or portfolio not found");
    }

    public static PortfolioApiException forbidden(String message) {
        return new PortfolioApiException(HttpStatus.FORBIDDEN, message);
    }

    public static PortfolioApiException conflict(String message) {
        return new PortfolioApiException(HttpStatus.CONFLICT, message);
    }

    public static PortfolioApiException unprocessable(String message) {
        return new PortfolioApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
