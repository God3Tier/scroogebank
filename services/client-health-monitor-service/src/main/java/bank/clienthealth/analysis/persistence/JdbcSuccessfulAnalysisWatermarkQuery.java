package bank.clienthealth.analysis.persistence;

import bank.clienthealth.analysis.port.SuccessfulAnalysisWatermarkQuery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded aggregate query used by the restart-safe watermark-age gauge. */
public final class JdbcSuccessfulAnalysisWatermarkQuery
        implements SuccessfulAnalysisWatermarkQuery {
    private final JdbcTemplate jdbc;

    public JdbcSuccessfulAnalysisWatermarkQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<Instant> latestSuccessfulWatermark() {
        OffsetDateTime value = jdbc.queryForObject("""
                SELECT MAX(watermark)
                FROM relationship_health_analysis_runs
                WHERE status = 'SUCCEEDED' AND watermark IS NOT NULL
                """, OffsetDateTime.class);
        return Optional.ofNullable(value).map(OffsetDateTime::toInstant);
    }
}
