# ADR-0086: News Alert drafts in Follow-up Emails

- Status: Proposed (2026-11-07; owner Neo Jia Wen; built by Hayyun, pending her review)
- Decision date/time: 2026-11-07 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen; reviewers: Hayyun, Azeez
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/event-driven-alerts` (`task/event-driven-alerts/news-alert-drafts`)
- Related plan: [news alerts plan](../feature-plans/event-driven-alerts-news-alert-drafts_plan.md)
- Supersedes: the `ALERT_FOLLOW_UP` purpose of [ADR-0068](ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md); amends [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md) (a draft not written by a model); with [ADR-0085](ADR-0085-editable-approved-reports-for-news-alerts.md), replaces ADR-0084 part 3
- Superseded by: N/A

## Context

All client email should leave from one place with one set of rules. Event Alerts now decides who hears about which report and approves it (ADR-0085); Follow-up Emails sends.

## Decision

1. **News Alert drafts.** Purpose `NEWS_ALERT`, tone `NONE` and source `NEWS` are fixed and accepted only together; `POST /api/engagement/drafts` refuses them. A draft is one client's email. "Notify selected clients of news" (`POST /api/engagement/drafts/news`) adds each chosen client's approved report to that client's open News Alert draft, or a new one. A report is on at most one open or sent draft; a draft holds at most `engagement.news.max-reports-per-email` reports (default 30, a safety limit to measure).
2. **The body.** A greeting and one line per company (at most 10), built from each report's summary line with no model call. The agent may edit it; it is theirs and is not checked. When the reports change, an unchanged summary and subject are rebuilt; a changed one is kept and the page offers "Rebuild summary?".
3. **Added at send, outside what the agent edits:** a guide to where each company is in the PDF, with page numbers from the rendered PDF, then the portfolio line, the disclaimer and the relationship manager's details.
4. **Sending.** Each report is checked again (send-check), the combined PDF is fetched in one call with its own 15-second timeout and checked against the approved hashes, one email goes to the client with the PDF attached (Reply-To and one Bcc to the agent), and the clients move to Sent Out. A failed Sent Out call leaves the draft sent with "Retry status update". Every request stays within API Gateway's 30-second limit.
5. **Removing and deleting.** A report can be taken off an open draft (the client stays in Plan client briefing); the last one discards the draft. Discarding or expiring a draft releases its reports. The page can delete several drafts at once and offers to dismiss the related reports.
6. **The alert follow-up is removed.** `ALERT_FOLLOW_UP` stays readable on older drafts; a new one is refused (400). `FollowUpSources.alertClient` is gone.
7. **The direct delivery is removed:** the `alertreports` package, its V3 tables (never deployed), `ENGAGEMENT_ALERT_REPORTS_ENABLED` and Terraform `alert_reports_enabled`, and the `ALERT_REPORT_*` audit actions. `DRAFT_REPORT_REMOVED` is added to the auditor allow-list.
8. **Data.** V3 changes `email_drafts` constraints only (in a new migration, so no environment is rebuilt) and adds `email_draft_reports` and `email_draft_news`.

## Consequences

- One send path, one throttle, one audit trail (`DRAFT_GENERATE`, `EMAIL_SEND` with approval IDs and hashes, `DRAFT_REPORT_REMOVED`).
- engagement-service attaches files: `OutgoingMail` has a general attachment list and `MimeMessageFactory` builds `multipart/mixed` for any attachment.
- One Bcc per email means one per client (open question for Neo Jia Wen).
- **One open draft per client.** A partial unique index (V4) allows one `DRAFT` or `SENDING` News Alert draft per client; two Notify clicks at once retry by adding to the first. A draft being sent is skipped (`DRAFT_BEING_SENT`).
- **A final Sent Out refusal ends the retry.** After a send, `NOT_AUTHORISED` or `INVALID_STATUS` is final: the report is listed as not marked (`sent_out_unmarked`) and the retry button is no longer offered for it; only an unreachable alerts service is retried.
- **Accepted: the dismiss block is page-only** (see ADR-0085): a direct API call can still dismiss a client on a draft; the send-check stops the send for them.
- The batch Notify buttons on Event Alerts call the same `POST /api/engagement/drafts/news` once per event and approve and send nothing.
