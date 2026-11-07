package bank.dashboard.upstream.dto;

import java.util.Set;

/**
 * An agent's clients onboarded against the team median, from client-service's
 * {@code GET /aggregates/clients/team-comparison} (ADR-0038). It names no other
 * agent and holds no other agent's figure.
 *
 * @param clientsOnboarded the caller's own count
 * @param teamMedian       the median across the comparison group, or null
 *                         when the group is smaller than {@link #MIN_COHORT}
 * @param cohortSize       agents in the group: those who created a client in
 *                         the last 12 months that is not deleted
 */
public record TeamComparison(int days, String from, String to, long clientsOnboarded, Double teamMedian,
                             long cohortSize) {

    /** The smallest group whose median is shown, as in client-service. */
    public static final int MIN_COHORT = 5;

    /** The only windows the comparison covers, each ending today. */
    public static final Set<Integer> WINDOW_DAYS = Set.of(7, 30, 90);

    /**
     * client-service already hides the median for a small group. Checking
     * again here costs nothing and keeps the rule if that ever regresses.
     */
    public TeamComparison withoutSmallCohortMedian() {
        return cohortSize >= MIN_COHORT ? this : new TeamComparison(days, from, to, clientsOnboarded, null, cohortSize);
    }
}
