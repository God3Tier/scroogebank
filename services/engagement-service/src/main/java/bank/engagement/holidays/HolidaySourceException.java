package bank.engagement.holidays;

import lombok.experimental.StandardException;

/** The holiday source could not be read or returned something unexpected. */
@StandardException
class HolidaySourceException extends RuntimeException {
}
