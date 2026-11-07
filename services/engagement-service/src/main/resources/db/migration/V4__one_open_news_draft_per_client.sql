-- A client has at most one open News Alert draft (news-alert-drafts plan, round 2, from Jiawen's review). Two Notify clicks
-- at the same moment could each make a draft for the same client; the second now hits this index and the service retries
-- by adding to the first. SENDING is included because a failed send gives a draft back by setting it to DRAFT, which a
-- second draft made meanwhile would break.
CREATE UNIQUE INDEX uk_email_drafts_open_news_per_client
    ON email_drafts (owner_agent_sub, client_id)
    WHERE purpose = 'NEWS_ALERT' AND status IN ('DRAFT', 'SENDING');

-- After a send, an alert the alerts service refuses to mark Sent Out for good (NOT_AUTHORISED, INVALID_STATUS) is kept
-- here with its reason, so the page can say which reports could not be marked instead of offering a retry for ever.
ALTER TABLE email_draft_news ADD COLUMN sent_out_unmarked JSONB NOT NULL DEFAULT '[]'::jsonb;
