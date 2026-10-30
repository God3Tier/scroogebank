package bank.clienthealth.rules;

import java.math.BigDecimal;

/** Immutable configuration for one reproducible ruleset version. */
public record RelationshipHealthRuleset(
        String version,
        RecurringCredit recurringCredit,
        ActivityDecline activityDecline,
        NetOutflow netOutflow
) {
    public static RelationshipHealthRuleset v1() {
        return new RelationshipHealthRuleset(
                "relationship-health-v1",
                new RecurringCredit("recurring-credit-v1", 3, new BigDecimal("20.00"), 7, new BigDecimal("0.25")),
                new ActivityDecline("activity-decline-v1", 6,
                        new BigDecimal("50.00"), new BigDecimal("65.00"), new BigDecimal("80.00")),
                new NetOutflow("sustained-net-outflow-v1", new BigDecimal("500.00"),
                        new BigDecimal("1.25"), new BigDecimal("1.50"), new BigDecimal("2.00")));
    }

    public record RecurringCredit(
            String ruleVersion,
            int minimumOccurrences,
            BigDecimal maximumAmountVariationPercent,
            int minimumGraceDays,
            BigDecimal cadenceGraceFraction
    ) { }

    public record ActivityDecline(
            String ruleVersion,
            int minimumBaselineCount,
            BigDecimal lowPercent,
            BigDecimal mediumPercent,
            BigDecimal highPercent
    ) { }

    public record NetOutflow(
            String ruleVersion,
            BigDecimal minimumPeriodOutflow,
            BigDecimal lowRatio,
            BigDecimal mediumRatio,
            BigDecimal highRatio
    ) { }
}
