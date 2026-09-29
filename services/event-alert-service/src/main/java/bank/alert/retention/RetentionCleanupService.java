package bank.alert.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;

@Service
@ConditionalOnProperty(prefix = "alert.retention", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RetentionCleanupService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int batchSize;

    public RetentionCleanupService(JdbcTemplate jdbc, Clock clock,
            @Value("${alert.retention.batch-size:100}") int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batch size must be positive");
        this.jdbc = jdbc;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${alert.retention.interval:1h}")
    @Transactional
    public RetentionCleanupOutcome cleanExpired() {
        Timestamp now = Timestamp.from(clock.instant());
        int briefings = jdbc.update("""
                WITH expired AS (
                    SELECT id FROM briefing
                     WHERE expires_at < ?
                     ORDER BY expires_at, id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM briefing b USING expired e WHERE b.id = e.id
                """, now, batchSize);
        int sources = jdbc.update("""
                WITH expired AS (
                    SELECT id FROM source_result
                     WHERE expires_at < ?
                       AND source_domain <> 'expired.invalid'
                     ORDER BY expires_at, id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE source_result s
                   SET title = '[expired]',
                       canonical_url = 'urn:expired:' || s.id,
                       source_domain = 'expired.invalid',
                       published_at = NULL,
                       content_hash = NULL
                  FROM expired e
                 WHERE s.id = e.id
                """, now, batchSize);
        return new RetentionCleanupOutcome(briefings, sources);
    }
}
