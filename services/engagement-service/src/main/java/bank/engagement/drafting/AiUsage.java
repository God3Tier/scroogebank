package bank.engagement.drafting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/** Model use per agent per Singapore day, for the caps (ADR-0054 decision 7). */
@Entity
@Table(name = "ai_usage")
@IdClass(AiUsage.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AiUsage {

    @Id
    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Id
    @Column(name = "agent_sub", length = 64)
    private String agentSub;

    @Column(nullable = false)
    private int drafts;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    static class Key implements Serializable {
        private LocalDate usageDate;
        private String agentSub;
    }
}
