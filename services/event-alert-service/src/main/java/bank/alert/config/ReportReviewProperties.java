package bank.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Report review and approval (news-alert-drafts plan, sections 5.2 and 6).
 *
 * @param approvalValidFor how long an approval can be used to send; after it the agent approves again
 * @param staleAfter       when the page highlights how old an approval is
 * @param summaryEnabled   whether the worker writes one summary sentence per report version; off until Azeez has
 *                         reviewed 20 sample sentences
 */
@ConfigurationProperties(prefix = "alert.reports")
public record ReportReviewProperties(Duration approvalValidFor, Duration staleAfter, boolean summaryEnabled) {

    public ReportReviewProperties {
        approvalValidFor = approvalValidFor == null ? Duration.ofDays(7) : approvalValidFor;
        staleAfter = staleAfter == null ? Duration.ofDays(5) : staleAfter;
        if (approvalValidFor.isNegative() || approvalValidFor.isZero()) {
            throw new IllegalArgumentException("alert.reports.approval-valid-for must be positive");
        }
    }
}
