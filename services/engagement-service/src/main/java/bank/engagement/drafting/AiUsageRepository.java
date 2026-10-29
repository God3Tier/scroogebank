package bank.engagement.drafting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

interface AiUsageRepository extends JpaRepository<AiUsage, AiUsage.Key> {

    @Modifying
    @Query(value = """
            INSERT INTO ai_usage (usage_date, agent_sub, drafts, input_tokens, output_tokens)
            VALUES (:date, :agent, 1, :inputTokens, :outputTokens)
            ON CONFLICT (usage_date, agent_sub) DO UPDATE
               SET drafts = ai_usage.drafts + 1,
                   input_tokens = ai_usage.input_tokens + EXCLUDED.input_tokens,
                   output_tokens = ai_usage.output_tokens + EXCLUDED.output_tokens
            """, nativeQuery = true)
    void addDraft(@Param("date") LocalDate date, @Param("agent") String agent,
            @Param("inputTokens") long inputTokens, @Param("outputTokens") long outputTokens);

    @Query(value = "SELECT COALESCE(SUM(drafts), 0) FROM ai_usage WHERE usage_date = :date AND agent_sub = :agent",
            nativeQuery = true)
    long draftsOn(@Param("date") LocalDate date, @Param("agent") String agent);

    @Query(value = """
            SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM ai_usage
             WHERE usage_date BETWEEN :from AND :to
            """, nativeQuery = true)
    long tokensBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
