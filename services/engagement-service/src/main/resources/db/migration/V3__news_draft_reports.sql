-- News Alert drafts (news-alert-drafts plan, section 7.2). A news draft is one client's email carrying one
-- combined PDF of the reports the agent approved on Event Alerts. It replaces ADR-0084's direct report delivery,
-- whose tables were never deployed.
--
-- email_drafts gets constraint changes only. ALERT_FOLLOW_UP stays allowed so older drafts remain readable; nothing
-- creates one any more. The constraints are changed here rather than in V1, so no environment has to be rebuilt.
ALTER TABLE email_drafts DROP CONSTRAINT email_drafts_purpose_check;
ALTER TABLE email_drafts ADD CONSTRAINT email_drafts_purpose_check
    CHECK (purpose IN ('CHECK_IN', 'MEETING_FOLLOW_UP', 'HOLIDAY_GREETING', 'PRE_HOLIDAY_REVIEW',
                       'HEALTH_CHECK_IN', 'ALERT_FOLLOW_UP', 'NEWS_ALERT'));
ALTER TABLE email_drafts DROP CONSTRAINT email_drafts_tone_check;
ALTER TABLE email_drafts ADD CONSTRAINT email_drafts_tone_check CHECK (tone IN ('FORMAL', 'WARM', 'NONE'));
ALTER TABLE email_drafts DROP CONSTRAINT email_drafts_source_check;
ALTER TABLE email_drafts ADD CONSTRAINT email_drafts_source_check CHECK (source IN ('AI', 'TEMPLATE', 'NEWS'));
ALTER TABLE email_drafts ADD CONSTRAINT email_drafts_news_check
    CHECK ((purpose = 'NEWS_ALERT') = (source = 'NEWS') AND (purpose = 'NEWS_ALERT') = (tone = 'NONE'));

-- The approved reports on a news draft, one row per client alert. summary_line is public report text kept so the
-- summary can be rebuilt without another call; it is cleared when the draft is sent or discarded. active is false
-- once the draft is discarded, so the report can go on a new draft.
CREATE TABLE email_draft_reports (
    draft_id     UUID         NOT NULL REFERENCES email_drafts (id),
    alert_id     UUID         NOT NULL,
    approval_id  UUID         NOT NULL,
    event_id     UUID         NOT NULL,
    issuer_name  VARCHAR(200) NOT NULL,
    headline     VARCHAR(300) NOT NULL,
    news_date    TIMESTAMPTZ  NOT NULL,
    valid_until  TIMESTAMPTZ  NOT NULL,
    final_hash   CHAR(64)     NOT NULL,
    summary_line VARCHAR(400),
    position     INTEGER      NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT true,
    added_at     TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (draft_id, alert_id)
);

-- A client's report is on at most one open or sent news draft.
CREATE UNIQUE INDEX uk_email_draft_reports_active_alert ON email_draft_reports (alert_id) WHERE active;

-- One row per news draft: the hash of the last summary the service built, to tell whether the agent changed it,
-- whether the reports changed after they did, and whether the Sent Out call after sending succeeded.
CREATE TABLE email_draft_news (
    draft_id           UUID     PRIMARY KEY REFERENCES email_drafts (id),
    built_summary_hash CHAR(64) NOT NULL,
    rebuild_suggested  BOOLEAN  NOT NULL DEFAULT false,
    sent_out_synced    BOOLEAN  NOT NULL DEFAULT false
);
