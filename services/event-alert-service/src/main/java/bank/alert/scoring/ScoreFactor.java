package bank.alert.scoring;

import java.math.BigDecimal;

public record ScoreFactor(String code, BigDecimal contribution, String explanation) {
}
