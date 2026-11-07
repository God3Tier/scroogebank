package bank.engagement.drafting;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The reports on News Alert drafts and each draft's summary bookkeeping (V3, news-alert-drafts plan 7.2). A report
 * row stays active while its draft is open or sent, so a client's report is never on two drafts; discarding a draft
 * releases its reports.
 */
@Component
@RequiredArgsConstructor
class NewsDraftStore {

    /** One approved report on a draft. {@code summaryLine} is null once the draft is sent or discarded. */
    record Report(UUID draftId, UUID alertId, UUID approvalId, UUID eventId, String issuer, String headline,
            Instant newsDate, Instant validUntil, String finalHash, String summaryLine, int position) {
    }

    /** An alert the alerts service refused to mark Sent Out for good, with its reason (NOT_AUTHORISED or INVALID_STATUS). */
    record Unmarked(UUID alertId, String reason) {
    }

    record News(UUID draftId, String builtSummaryHash, boolean rebuildSuggested, boolean sentOutSynced,
            List<Unmarked> unmarked) {
    }

    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    List<Report> reports(UUID draftId) {
        return jdbc.query("""
                SELECT draft_id, alert_id, approval_id, event_id, issuer_name, headline, news_date, valid_until,
                       final_hash, summary_line, position
                  FROM email_draft_reports WHERE draft_id = ?
                 ORDER BY lower(issuer_name), headline, alert_id
                """, (rs, n) -> new Report(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6),
                rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(), rs.getString(9).strip(),
                rs.getString(10), rs.getInt(11)), draftId);
    }

    /** Whether the alert is already on an open or sent News Alert draft. */
    boolean active(UUID alertId) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM email_draft_reports WHERE alert_id = ? AND active",
                Integer.class, alertId);
        return found != null && found > 0;
    }

    void add(Report report, Instant now) {
        jdbc.update("""
                INSERT INTO email_draft_reports (draft_id, alert_id, approval_id, event_id, issuer_name, headline,
                                                 news_date, valid_until, final_hash, summary_line, position, added_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, report.draftId(), report.alertId(), report.approvalId(), report.eventId(), report.issuer(),
                report.headline(), Timestamp.from(report.newsDate()), Timestamp.from(report.validUntil()),
                report.finalHash(), report.summaryLine(), report.position(), Timestamp.from(now));
    }

    int remove(UUID draftId, UUID alertId) {
        return jdbc.update("DELETE FROM email_draft_reports WHERE draft_id = ? AND alert_id = ?", draftId, alertId);
    }

    /** A discarded draft gives its reports back, so they can go on a new draft. */
    void release(UUID draftId) {
        jdbc.update("UPDATE email_draft_reports SET active = false, summary_line = NULL WHERE draft_id = ?", draftId);
    }

    /** A sent draft keeps its reports (so they are not sent again) but not their text. */
    void clearText(UUID draftId) {
        jdbc.update("UPDATE email_draft_reports SET summary_line = NULL WHERE draft_id = ?", draftId);
    }

    Optional<News> news(UUID draftId) {
        return jdbc.query("""
                SELECT draft_id, built_summary_hash, rebuild_suggested, sent_out_synced, sent_out_unmarked::text
                  FROM email_draft_news WHERE draft_id = ?
                """,
                (rs, n) -> new News(rs.getObject(1, UUID.class), rs.getString(2).strip(), rs.getBoolean(3), rs.getBoolean(4),
                        unmarked(rs.getString(5))),
                draftId).stream().findFirst();
    }

    void saveNews(UUID draftId, String builtSummaryHash, boolean rebuildSuggested) {
        jdbc.update("""
                INSERT INTO email_draft_news (draft_id, built_summary_hash, rebuild_suggested) VALUES (?, ?, ?)
                ON CONFLICT (draft_id) DO UPDATE SET built_summary_hash = EXCLUDED.built_summary_hash,
                       rebuild_suggested = EXCLUDED.rebuild_suggested
                """, draftId, builtSummaryHash, rebuildSuggested);
    }

    void suggestRebuild(UUID draftId, boolean suggested) {
        jdbc.update("UPDATE email_draft_news SET rebuild_suggested = ? WHERE draft_id = ?", suggested, draftId);
    }

    /** After a Sent Out call: whether nothing is left to retry, and the alerts that can never be marked. */
    /** @param unmarked the reports refused for good; null keeps what an earlier try stored */
    void sentOutResult(UUID draftId, boolean synced, List<Unmarked> unmarked) {
        if (unmarked == null) {
            jdbc.update("UPDATE email_draft_news SET sent_out_synced = ? WHERE draft_id = ?", synced, draftId);
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(unmarked);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("UPDATE email_draft_news SET sent_out_synced = ?, sent_out_unmarked = CAST(? AS jsonb) WHERE draft_id = ?",
                synced, json, draftId);
    }

    private List<Unmarked> unmarked(String json) {
        try {
            List<Unmarked> list = new java.util.ArrayList<>();
            mapper.readTree(json == null ? "[]" : json).forEach(node ->
                    list.add(new Unmarked(UUID.fromString(node.path("alertId").asText()), node.path("reason").asText())));
            return list;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    /** The alerts on the agent's open News Alert drafts, for the dismiss block on Event Alerts. */
    List<UUID> openAlerts(String owner) {
        return jdbc.queryForList("""
                SELECT r.alert_id FROM email_draft_reports r JOIN email_drafts d ON d.id = r.draft_id
                 WHERE d.owner_agent_sub = ? AND d.status IN ('DRAFT', 'SENDING') AND r.active
                 ORDER BY r.alert_id
                """, UUID.class, owner);
    }
}
