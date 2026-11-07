package bank.alert.matching;

import java.math.BigDecimal;

/** One instrument a public article is about, and how directly it says so. */
public record InstrumentMatch(String instrumentId, BigDecimal relevance, String reason) {
}
