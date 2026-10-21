package bank.engagement.holidays;

/** The holiday source could not be read or returned something unexpected. */
class HolidaySourceException extends RuntimeException {

    HolidaySourceException(String message) {
        super(message);
    }

    HolidaySourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
