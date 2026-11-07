# ADR-0085: Editable, approved reports for news alerts

- Status: Proposed (2026-11-07; owner Hayyun; needs Azeez's approval of the edit checks and Neo Jia Wen's review)
- Decision date/time: 2026-11-07 (Asia/Singapore)
- Decision owner(s): Hayyun; reviewers: Azeez (report design), Neo Jia Wen (engagement)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/event-driven-alerts` (`task/event-driven-alerts/news-alert-drafts`)
- Related plan: [news alerts plan](../feature-plans/event-driven-alerts-news-alert-drafts_plan.md)
- Supersedes: [ADR-0084](ADR-0084-full-coverage-search-and-client-event-reports.md) decisions 12 to 18 (delivery from Event Alerts); amends ADR-0084 decisions 10 and 13 (reports can be edited)
- Superseded by: N/A

## Context

ADR-0084 part 3 sent each approved ticker report to each client straight from Event Alerts. A client holding several tickers got one email per ticker, the report text sat in the email body, agents could not correct a report, and client email went out on two paths with different rules. "Continue monitoring" also overlapped with leaving an alert alone.

## Decision

1. **Two decisions and a final status.** Agents choose Plan client briefing (`INFORMED`) or Dismiss, and can switch between them until the email is sent. `MONITOR` and `MONITORING` remain readable history only; open `MONITORING` alerts became `NEW` (V17). `SENT_OUT` is final and set only by engagement-service's sent-out call.
2. **Editable reports, checked again.** An agent may change the wording of the executive summary, key facts, why it matters, what to monitor and uncertainty. The headline, the sources and which source each sentence cites cannot change. The edited text meets the model's rules again: shape and lengths, every figure the agent added appears in a source the sentence cites (or one of the report's sources), and no advice language. A failure is a 422 naming the section and item. Edits belong to one agent.
3. **Approval is final.** Approving freezes the text the client receives in `approved_report` (with the original and final hashes) for `alert.reports.approval-valid-for`, 7 days by default; after that the agent approves again. A newer search makes a new event and report, never a new version of an approved one.
4. **A visible disclosure.** Every PDF section says it was AI-drafted from public sources and reviewed, or edited, by the relationship manager.
5. **One combined PDF, rendered on the server.** event-alert-service renders the PDF with Apache PDFBox: cover, contents, one section per company in issuer order with its own numbered sources, and the disclaimer once. It holds no client data, is never stored, and the same approvals give byte-for-byte the same file. The browser download uses the same renderer; jsPDF is removed.
6. **A summary sentence per report.** The worker may write one sentence per report version from that report's public text only, behind `ALERT_REPORT_SUMMARY_ENABLED` (off until Azeez reviews 20 samples). No model ever sees which reports one client holds. Edited reports use their first sentence instead.
7. **Sending moves to Follow-up Emails** ([ADR-0086](ADR-0086-news-alert-drafts-in-follow-up-emails.md)). event-alert-service provides the package, send-check, combined PDF and sent-out routes, each checking ownership, status and approval again with the agent's token.
8. **Retention.** Approved text is cleared once every client on it is Sent Out, or 14 days after the approval expired; unapproved edits after 30 days. Hashes stay.

## Consequences

- Agents can correct a report, and the client receives exactly what was approved, as a document they can keep.
- New audit events: `alert.report-edited`, `alert.report-approved`, `alert.summary-generated`, `alert.sent-out`.
- The edit checks are deterministic, not a model: an agent can still write something misleading that passes them. Azeez must accept them before an edited report goes to clients under the bank's name.
- The API task still makes no model call; the summary sentence runs in the worker, which already has the Bedrock grant.
