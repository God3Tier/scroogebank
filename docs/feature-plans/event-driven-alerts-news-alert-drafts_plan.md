# News alerts: one email per client with one combined report PDF, sent from Follow-up Emails

Owner: Hayyun (event-driven alerts) and Neo Jia Wen (agent engagement). Reviewers: Azeez (report design).
Status: Built on `task/event-driven-alerts/news-alert-drafts`; review by Jiawen and Azeez pending. Last updated: 2026-11-07.
Parent plans: [event-driven alerts](event-driven-alerts_plan.md), [full-coverage reports](event-driven-alerts-full-coverage-reports_plan.md), [agent engagement](agent-engagement_plan.md).
Based on: `feat/event-driven-alerts` at `d6573cf`. This version replaces the 2026-11-06 version (one PDF per report, at most 10 per email) and the first 2026-11-07 draft.

**Changes since 2026-11-06**
- One **combined PDF** per email holds all of the client's reports, laid out like a financial document. There are no separate PDFs and no 10-report limit.
- The email body has an editable **summary** and a generated **guide** to where each company sits in the PDF, with page numbers.
- The send makes one server call to build the combined PDF, not one call per report.
- A staleness warning is shown on approved reports.

**Changes in the round-3 revision (2026-11-07, getting ready for AWS)**
1. **Summary sentence approved:** Azeez approved it. `ALERT_REPORT_SUMMARY_ENABLED` stays off in the application's own default (so a local run never turns it on by accident) and the alert-worker Terraform now sets it on (`report_summary_enabled`, default true).
2. **Anthropic comes with the migrations (V18)** so every environment, including a fresh one after teardown, has it.
3. **Test data is re-created by `scripts/seed-demo-portfolios.py --profile news`** (holdings for one client with Shell, Anthropic and Apple, one with Apple and Anthropic, and the rest Apple), with `--add-websites` and `--search`. Nothing local travels to AWS.
4. **Edit button** reads "Save and preview" and asks before saving; **Download** carries the saved edit; the page **checks for new reports every 20 seconds**; a ticker whose report the AI refused says so.

**Changes in the round-2 revision (2026-11-07, testing and Jiawen's review)**
1. **Edit mode (5.2):** one Edit / Preview toggle turns the report into a single plain-text box in the same place, with no separate form.
2. **Notify several tickers at once (5.2):** two buttons under the page summary make drafts across all tickers, one for every ready client and one for the ticked clients; the ticks are kept when the agent switches ticker; a hint under the per-ticker Notify explains them; a floating button scrolls back to them. None of them approves or sends anything.
3. **One open news draft per client (5.5, 7.2):** a unique index and a retry, so two Notify clicks at once cannot make two drafts for a client. A draft that is being sent is not added to.
4. **A final Sent Out refusal ends the retry (5.6, 10):** a report refused for good after sending is listed as not marked.
5. **The dismiss block is page-only (8):** accepted, with the reason.
6. **An old Monitoring status** reads as "Monitoring (old option)".

**Changes in the first revision (review of 2026-11-07)**
1. The summary is built from **one AI sentence per report**, which the worker writes once and every client then reuses. No model call sees which reports one client holds. This replaces the one model call per draft.
2. Every send must finish within API Gateway's **30-second** limit (section 11).
3. The PDF call gets its own timeout, separate from the 5-second limit for other calls between the services.
4. Drafts are no longer changed by a model call when reports are added. Building the summary needs no model call.
5. The "every company named" check is dropped from the summary checks, because sources such as Bloomberg would fail it.
6. Approved text is cleared only on Sent Out or expiry, so a client moved from Dismiss back to Plan client briefing can still be sent.
7. A single report can be downloaded **without an approval**, for admins and for reading before approving.
8. The PDF date comes from the newest approval, not from today, so the same approvals always give the same PDF.
9. "The agent edited the summary" is detected with a hash of the summary text, not with the draft's `edited` column.

## 1. Problem

- Client email is sent from two places, with different rules:
  - Follow-up Emails (ADR-0054, ADR-0068)
  - the Event Alerts page, through `alertreports/` (ADR-0084 decisions 12 to 18)
- A client holding several tickers gets one email per ticker. The report text is in the email body, so the client has no document to keep.
- The agent cannot correct a report before it goes out.
- "Continue monitoring" overlaps with leaving an alert alone.

## 2. Goal

- **One send path.** All client email is sent from Follow-up Emails. The Event Alerts page decides who hears about which report, lets the agent edit and approve the report, and never sends.
- **One email per client, with one PDF.** The PDF holds all of the client's approved reports as one document, with a cover page, contents and one section per company. The email body holds a short summary, built from each report's AI sentence, which the agent can edit.
- **Editable reports.** The agent can edit a report before approving it. Edits are checked again, shown to the client, and final once approved.
- **Clear statuses.** Each client on a report is New, Plan client briefing, Dismiss or Sent Out. Sent Out is final and shown in its own view.

## 3. Scope and non-scope

**In scope:** everything in section 5.

**Not in scope:**
- How reports are searched, grouped, scored or first written. ADR-0084 parts 1 and 2 stay.
- Automatic or scheduled sending.
- Client data in any model call or in the PDF. This includes which set of reports one client holds.

## 4. Terms

- **Report:** the `event_report` for one ticker's event. A new search run on the same ticker makes a **new event and report**, not a new version (ADR-0084 decision 7: writing the report closes the event).
- **Client status:** the `alert` row for one client on one report.
- **Approved report:** one agent's frozen copy of a report version, which may include their edits.
- **Summary sentence:** one AI-written sentence for one report version, made by the worker (section 6).
- **Combined PDF:** one document built from the approved reports on one draft. It is what the client receives.
- **News draft:** an `email_drafts` row with `source = NEWS`, for one client, linked to one or more approved reports.

## 5. Flow

### 5.1 Event Alerts page: decide (alerts team)

| Shown to agent | Stored value | Can change to |
|---|---|---|
| New | `NEW` | Plan client briefing, Dismiss |
| Plan client briefing | `INFORMED` | Dismiss |
| Dismiss | `DISMISSED` | Plan client briefing |
| Sent Out | `SENT_OUT` (new) | Nothing. Set only when the news email is sent. |

- Remove "Continue monitoring" (`MONITOR`, `MONITORING`). Existing `MONITORING` rows become `NEW`.
- New help text for "Plan client briefing": "Records that you plan to brief them, then takes you to review and approve the report. No message is sent yet."
- After saving "Plan client briefing", the page moves focus to the report panel. The same clients stay selected.
- A client on an open news draft cannot be dismissed here. The page says "This client is on an open news draft. Remove the report from that draft in Follow-up Emails first."
- A client moved from Dismiss back to Plan client briefing can use the agent's existing approval while it is still valid (5.2, step 6).

### 5.2 Event Alerts page: edit and approve the report (alerts team)

1. **Edit (optional).** One toggle in the report bar, **Edit** / **Preview**, changes the report in place. In Edit the report is **one large text box** holding the whole report as plain text, with these headings: EXECUTIVE SUMMARY, KEY FACTS (only if the report has any), WHY IT MATTERS, WHAT TO MONITOR and UNCERTAINTY. The headline and the sources stay on the page as fixed text. There is no separate form and no scrolling to another place.
   - Items are one per line: numbered for the summary and why it matters, `Label | Value | Context` for key facts, a dash for what to monitor. Citation markers are not shown; each stays with its item by position.
   - The number of summary sentences, key facts and why-it-matters points is fixed (each is tied to its sources). What to monitor can be 2 to 4 lines.
   - **Preview** reads the text back. A wrong count, a missing heading or a key fact without three parts is refused in the page with the line named. Otherwise the text is sent to the server, which runs the checks in 5.3 and saves it; a refusal keeps the agent in Edit with the section, item and line named. Approve is disabled while editing.
   - Edits belong to this agent only; other agents holding the ticker still see the original.
2. **Approve.** One button, "Approve report for X selected clients", replaces the branch's checkbox and "Send report to X selected clients". Before approving, the page warns: "Approval is final. You cannot edit this report again after approving."
3. **On approval,** event-alert-service saves an **approved report**: the agent, report ID and version, the final text (edited or not), an `edited` flag, the original and final hashes, and `approved_at`.
4. **Visible replacement.** The button becomes **"Notify selected clients of news"** in a different style. Above it: "Approved by you at HH:MM" (with "with your edits" when edited). An `aria-live` message announces the change.
5. **"Notify selected clients of news"** calls `POST /api/engagement/drafts/news` (5.5), then opens that client's draft in Follow-up Emails. Under it a hint reads: "Opens this client's draft in Follow-up Emails. Working through several tickers? Use "Notify all ready clients" at the top of this page to make every draft in one go."
6. **Approval is final.** It cannot be undone or edited. It stays usable for `alert.reports.approval-valid-for` (default 7 days). After that the page offers "Approve again", which starts from the current report. A newer event on the same ticker is a separate report with its own approval.
7. **Staleness warning.** Wherever an approved report is shown (Event Alerts and the news draft), the page shows the news date and "Approved N days ago, can be sent for M more days". From day 5 the line is highlighted, and the wording says the same thing as the highlight, so colour is not the only signal.
8. **Download.** Any report can be downloaded as a PDF before approval, and by admins, through the single-report endpoint (5.4).
9. **Notify several tickers at once.** The ticked clients are kept by the page, so they survive switching ticker. A panel under the "N need a decision" summary has two buttons, each with a short description, and shows how many clients and reports each covers (from `GET /api/alerts/approved-reports`):
   - **Notify all ready clients:** makes a draft for every client who is in Plan client briefing and whose report the agent has approved, across all tickers.
   - **Notify ticked clients:** the same, but only for the ticked clients.
   Each groups the clients by event, calls `POST /api/engagement/drafts/news` once per event, then opens the Follow-up Emails **list** (no draft is opened) with a notice. **Neither button approves or sends anything**: approval stays final and one ticker at a time.
10. **Floating helper.** When the panel is scrolled out of view and at least two reports are ready, a small button floats at the bottom right: "Several tickers? Notify them all at once". Its tooltip says "Make drafts for every approved report in one go. Click to jump to the buttons at the top." Clicking scrolls the panel into view, moves focus to it and highlights it briefly (without motion if the browser asks for reduced motion).

### 5.3 Checks on edited text (alerts team)

`ClientReportValidator` already checks model output. It now runs again on the final text at every save and again at approval:
- **Citations:** every citation marker must point to one of the report's own sources.
- **Figures:** every number in a cited sentence must appear in a cited source title or excerpt, after normalisation (ADR-0084 decision 10). An added figure with no source fails.
- **Advice:** advice language fails, for example "we recommend", "buy", "sell" or "add to your position".
- **Shape:** required sections are present, and section and total length limits hold.

A failure returns 422 naming the section and sentence. Nothing is saved until the text passes.

### 5.4 The PDF (alerts team renders it, engagement attaches it)

- event-alert-service renders the PDF **on the server** with Apache PDFBox (Apache-2.0 licence).
- **Layout, like a financial document:**
  1. **Cover page:** "Portfolio update", the document date, and "Prepared by {agent name}, your relationship manager". No client name.
  2. **Contents:** one line per company, with its headline and page number.
  3. **One section per company,** each starting on a new page, in alphabetical order by issuer. Each section has:
     - issuer, ticker and headline
     - the disclosure line (below)
     - executive summary, key facts, why it matters, what to monitor, uncertainty
     - its own numbered sources, starting at [1]
  4. **Closing page:** the disclaimer, once.
  5. **Footer on every page:** page number and "Portfolio update, {document date}".
- **Disclosure line** under each company's headline, visible:
  - edited: "AI-drafted from public sources and edited by your relationship manager, {agent name}."
  - not edited: "AI-drafted from public sources and reviewed by your relationship manager, {agent name}."
  - not yet approved (single-report download only): "AI-drafted from public sources. Not yet reviewed."
- **Document date:** the Singapore date of the newest `approved_at` among the reports in the document. For a single report that is not yet approved, it is the report's creation date. It is never the date of rendering.
- **Same input, same file:** the PDF metadata (creation date, document ID) is fixed from the same inputs. The same set of approvals always gives byte-for-byte the same PDF, so the agent's preview is the file that is sent. The PDF is rendered when needed and **not stored**.
- **No client data:** no name, holdings, score or values.
- **File name:** `Portfolio-update-{document date}.pdf`.
- **Start pages:** the renderer returns each company's start page with the PDF. The contents page and the email's "Where to find each report" guide (5.5) both use these numbers, so they always match.
- **Two entry points, one renderer:**
  - `POST /api/alerts/approved-reports/pdf {approvalIds}`: the combined PDF, for the news draft preview and for sending.
  - `GET /api/alerts/events/{eventId}/report/pdf?version=`: one report, with no approval needed, for downloads on Event Alerts by agents and admins.
  - `frontend/src/utils/reportPdf.js` (jsPDF) is removed.
- **Safety limit:** a draft holds at most `engagement.news.max-reports-per-email` reports, default 30. It is there to protect send time (section 11) and file size, and is not a product rule. Set it from a real measurement before the ADR.

### 5.5 Follow-up Emails: the news draft (engagement team)

**Creating or extending drafts.** `POST /api/engagement/drafts/news` takes `{eventId, alertIds}`. For each client:
- If the client has an open news draft, this report is added to it.
- Otherwise a new news draft is made for the client. A unique index allows one open draft (`DRAFT` or `SENDING`) per client; two clicks at once retry by adding to the first.
- A client whose draft is being sent is skipped (`DRAFT_BEING_SENT`): its reports were already read for the send.
- A report already on an open or sent draft for that client is not added again.
- A client whose draft is at the safety limit is skipped, and the page names them.
- **No model call happens here.** The summary sentences come with the approval package (7.1), so a "Notify" click for many clients is quick.

**Alternate entry.**
- On Follow-up Emails the agent chooses Purpose **News Alert** (it replaces "Alert follow-up"). A Client dropdown then lists clients who have at least one approved, unsent report in Plan client briefing.
- Choosing a client lists those reports by headline, all ticked.
- Under the list: "Can't find the report you wanted? It must be set with a status of 'Plan client briefing' and be approved by you." followed by a link, "See all reports", to the Event Alerts page.
- "Create draft" does the same as the redirect for that client.

**Locked fields when `source = NEWS`:**
- Client: the draft's one client.
- Purpose: News Alert.
- Tone: None, which is allowed only with News Alert.
- "Regenerate" is hidden. "Rebuild summary" (below) takes its place.

**Reports on the draft.**
- They are shown as a read-only list, each with its news date and staleness line (5.2).
- One "Preview PDF" link opens the combined PDF exactly as the client will get it.
- Each report has a "Remove" button that takes it off the draft. The client stays in Plan client briefing.
- Reports can be added only through the two entry points, not by hand.

**Subject.** "Your portfolio update: {Issuer A}, {Issuer B} and N more", cut to 120 characters. The agent can edit it.

**Body.** It works like a financial document's cover note, in two parts.

```
Dear {{client_name}},

Here is an update on {N} companies in your portfolio.
• Apple Inc: {summary sentence}
• Shell plc: {summary sentence}
… and {N - 10} more companies, listed below.

Where to find each report in the attached PDF (Portfolio-update-2026-11-07.pdf):
• Apple Inc: Apple reports record Q4 revenue. Page 3
• Shell plc: Shell raises dividend. Page 6
```

**Part 1: the summary.** This is the part the agent can edit.
- **How it is built:** the summary is built by engagement-service from each report's summary sentence (section 6), in the same order as the PDF, **with no model call**.
- **Which sentence each report uses:**
  - A report that was not edited uses its AI summary sentence, when there is one.
  - A report that was edited uses the first sentence of its approved executive summary. Agent-edited text never goes to a model.
  - When the summary sentence is switched off or failed its checks, the report also uses the first sentence of its approved executive summary.

  Citation markers are removed in every case.
- **Length:** at most 10 company lines are listed. The rest are named in the guide below. That keeps the editable body short: about 2,600 characters at most, inside the 5,000 `body_template` limit.
- **Agent edits:** what the agent writes is theirs and is not checked (ADR-0068 decision 3). The editor says so.
- **When reports change:** adding or removing a report rebuilds the summary, unless the agent has changed it.
  - To tell, engagement-service keeps a hash of the last summary it built (7.2) and compares it with the current text.
  - If the agent changed it, the page asks "The reports on this draft changed. Rebuild summary?" and the agent chooses.
  - Editing only the subject does not trigger this prompt.

**Part 2: "Where to find each report".**
- One line per company: issuer, headline and start page, in PDF order.
- Not written by a model and not editable. It is built from the PDF rendered at send time (5.6), so its page numbers always match.
- Shown read-only under the summary in the editor, labelled "Added from the PDF. Updates automatically."
- Added at send time, so it does not count toward the body limit.

**Fixed text.** This is added at send time, outside the body, and shown read-only under it with the label "Added to every email. You cannot change this."
- The portfolio line: "Your portfolio has exposure to {Issuer A} and {Issuer B}, which may be affected by these events."
- The disclaimer.
- The relationship manager's name and email.

### 5.6 Sending (engagement team)

1. **Check.** engagement-service asks event-alert-service, using the agent's token: `POST /api/alerts/send-check {alertIds}`. Each report must still be:
   - the agent's
   - in Plan client briefing (`INFORMED`)
   - covered by a valid approval

   If any fails, nothing is sent. The page names the failing reports so the agent can remove them and send again.
2. **Fetch.** engagement-service fetches the combined PDF in **one call**: `POST /api/alerts/approved-reports/pdf {approvalIds}`. This call has its own timeout (section 11). The response includes:
   - the final hash of each report
   - the PDF's hash
   - each company's start page

   engagement-service compares the report hashes with the ones it stored when the reports were added. This is a **consistency check**: it shows that the approvals have not changed since the draft was made. It does not check the PDF itself, which comes from the same service as the hashes. The guide is built from the start pages.
3. **Send.** One SES message goes to the client, through the existing `DraftService.send` path:
   - Reply-To the agent, and one Bcc to the agent
   - the throttle
   - one PDF attachment, in `multipart/mixed`

   `OutgoingMail` gets a general attachment list. `MimeMessageFactory` builds `multipart/mixed` for any attachment, not only for `invite.ics`.
4. **Mark Sent Out.** After SES accepts the message, engagement-service calls `POST /api/alerts/sent-out {alertIds, draftId, sesMessageId}`, which is idempotent. If this call fails, the draft is still `SENT`, and the page offers "Retry status update", which calls `POST /api/engagement/drafts/{id}/news-status`.
5. **Clear the draft.** The draft text is cleared on send (ADR-0068 decision 4). The audit keeps the hashes.

### 5.7 Follow-up Emails: deleting several drafts (engagement team)

- **Selecting:** each Drafts-tab row gets a checkbox, and the header has "Select all". Sent, Sending and Discarded rows cannot be selected.
- **Deleting:** "Delete selected (N)" asks for confirmation, then deletes each one. A failure is reported against its row; the others still go.
- **Dismiss prompt:** if Y of the deleted drafts were news drafts, the page asks "Do you also want to dismiss the related reports for the Y deleted drafts?"
  - **Yes:** every report on those drafts is set to Dismiss for that client, through the existing event action endpoint. Reports already Sent Out or Dismissed are skipped, and the page says so.
  - **No:** they stay in Plan client briefing.
- **Expiry:** the 14-day draft expiry never changes a client's status.

### 5.8 Event Alerts page: Sent out view (alerts team)

- A "Sent out" tab lists each sent client and report: ticker, headline, edited or not, client, sent time.
- Sent Out rows are read-only.
- The main view leaves out Sent Out clients, and leaves out reports whose clients are all Sent Out or Dismissed.
- The admin's read-only view has the same tab.

## 6. The summary sentence

**Status:** approved by Azeez. Off in the application's default; on in the alert-worker Terraform (`report_summary_enabled`).

- **What it is:** one sentence per report version, at most 250 characters, saying what happened and why it matters. It is not a summary of a client's set of reports.
- **Who writes it and when:** the **worker**, straight after it writes the report, as a separate model call. The report schema `alert-client-report-v2` does not change.
  - It is written once and stored on the report. Every agent and every client who gets that report reuses it.
  - Cost grows with the number of reports, not the number of clients.
- **What the model sees:** only that one report's public text, which is the same text the worker already sent to write the report. It never sees:
  - which client gets the report
  - which other reports go with it
  - any agent-edited text
- **Checks:**
  - at most 250 characters, and one sentence
  - every number appears in that report's text
  - it names the report's issuer
  - no advice language
  - no citation markers

  If any check fails, no sentence is stored, and the summary uses the first sentence of the executive summary instead (5.5).
- **Switch:** `ALERT_REPORT_SUMMARY_ENABLED`. Azeez has approved the sentence, so the worker's Terraform turns it on; the application's own default stays `false`. While it is off, every report uses its first sentence.
- **Budget:** each sentence counts toward the worker's existing token quota and the USD 25 monthly guard.

### Model choice (Nova only)

Tested with the `crm` profile on 2026-11-06 in ap-southeast-1: each of these models answered 3 out of 3 calls.

| Model (profile) | Where it runs | Context window* | Price* (USD per 1M tokens, in/out) | Fit |
|---|---|---|---|---|
| `apac.amazon.nova-micro-v1:0` | APAC regions only | 128K | ~0.035 / 0.14 | Cheapest. Text only. Weakest at careful wording. |
| `apac.amazon.nova-lite-v1:0` | APAC regions only | 300K | ~0.06 / 0.24 | Already used by engagement drafts. Misplaced placeholders in drafts (ADR-0068 note). |
| `apac.amazon.nova-pro-v1:0` | APAC regions only | 300K | ~0.80 / 3.20 | Strongest Nova v1. More than one sentence needs. |
| `global.amazon.nova-2-lite-v1:0` | Any region (global profile) | 1M | ~0.30 / 2.50 | Newer and stronger than Nova Lite. Already the worker's model, and already allowed by the worker's IAM policy. |

\* Context windows and prices are from memory. The `crm-infra-admin` role cannot read the AWS Pricing API (`AccessDeniedException`), so check both on the Bedrock pricing page before the ADR.

- **Context window doesn't decide this.** One report is about 1,000 to 1,500 input tokens.
- **Cost doesn't decide this either.** One sentence costs well under USD 0.001 on any of the four models, and only one sentence is made per report.
- **Security decides it.**
  - The input is the public report text the worker already sends to `global.amazon.nova-2-lite-v1:0`. Using that model adds **no new data flow and no IAM change**.
  - The API task keeps model calls switched off.
  - An APAC-only model would keep processing in APAC, but it would need a new Bedrock grant on the worker's role for text that already goes through the global profile.

**Recommendation:** `global.amazon.nova-2-lite-v1:0`, in the worker. If the team wants all alert processing kept in APAC, move both the report and the sentence to `apac.amazon.nova-pro-v1:0` together, in one ADR.

## 7. Data and contracts

### 7.1 event-alert-service

**Data**
- `alert.status`: drop `MONITORING`, add `SENT_OUT`. Old `alert_decision` rows with `MONITOR` stay readable.
- `event_report`: adds `summary_sentence` and `summary_model`, both nullable.
- New table `report_edit`: `event_report_id`, `version`, `agent_sub`, the edited sections as JSON, and `updated_at`. One row per agent and version, holding the latest edit that has not been approved.
- New table `approved_report`:
  - `id`, `event_report_id`, `version`, `agent_sub`
  - `final_text` (JSON), `edited`, `original_hash`, `final_hash`
  - `approved_at`, `valid_until`, `text_cleared_at`

  Unique on `(event_report_id, version, agent_sub)`.

**Endpoints**

| Method and path | Purpose |
|---|---|
| `PUT /api/alerts/events/{eventId}/report/edit` | Saves edits after the 5.3 checks. 422 on failure. |
| `POST /api/alerts/events/{eventId}/report/approval` `{expectedReportVersion}` | Approves. 409 if the version changed, or if this agent already approved the report. |
| `GET /api/alerts/events/{eventId}/report/pdf?version=` | One report as a PDF, with no approval needed. For agents and admins. |
| `GET /api/alerts/approved-reports?clientRef=` | For the alternate entry: approved, unsent, `INFORMED` reports for one of the caller's clients. |
| `POST /api/alerts/approved-reports/package` `{eventId, alertIds}` | For draft creation. For each alert: `approvalId`, client reference, issuer, headline, news date, `valid_until`, final hash, and the summary line (the summary sentence, or the first sentence, per 5.5). |
| `POST /api/alerts/approved-reports/pdf` `{approvalIds}` | The combined PDF, the PDF hash, the final hash of each report, and `sections[{approvalId, issuer, headline, startPage}]`. |
| `POST /api/alerts/send-check` `{alertIds}` | The pre-send check (5.6). |
| `POST /api/alerts/sent-out` `{alertIds, draftId, sesMessageId}` | Moves `INFORMED` to `SENT_OUT`. Idempotent. |
| Existing decision and event action endpoints | Accept only `INFORM_CLIENT` and `DISMISS`. 409 on `SENT_OUT`. |

Every endpoint that takes `approvalIds` checks that each approval belongs to the caller. The old `report/delivery-package` endpoint is removed.

**Retention:** an approval's `final_text` is cleared when every client on it is Sent Out, or 14 days after `valid_until`, whichever comes first. **Dismissing a client does not clear it**, because that client can be moved back to Plan client briefing while the approval is still valid. The hashes stay after clearing.

### 7.2 engagement-service

**Data**
- `email_drafts` (V1 edit, allowed before teardown). Constraints only, no new columns:
  - `purpose` replaces `ALERT_FOLLOW_UP` with `NEWS_ALERT`
  - `tone` adds `NONE`
  - `source` adds `NEWS`
  - `client_id` stays required, because there is one client per draft
- New `V3__news_draft_reports.sql`, replacing the branch's `V3__alert_report_deliveries.sql`. It creates two tables.
  - `email_draft_reports`:
    - columns: `draft_id`, `alert_id`, `approval_id`, `event_id`, `issuer_name`, `final_hash`, `summary_line`, `position`
    - unique on `(draft_id, alert_id)`
    - a partial unique index, so an `alert_id` is on at most one draft whose status is `DRAFT`, `SENDING` or `SENT`
    - `summary_line` is public report text, kept so the summary can be rebuilt without another call. It is cleared when the draft is sent or discarded.
  - `email_draft_news`, one row per news draft:
    - `draft_id` (primary key)
    - `built_summary_hash`: the hash of the last summary engagement-service built, used to tell whether the agent changed it (5.5)
    - `sent_out_unmarked`: after sending, the alerts the alerts service refused to mark Sent Out for good, with the reason (`NOT_AUTHORISED` or `INVALID_STATUS`)
  - Migration `V4__one_open_news_draft_per_client.sql` adds the partial unique index on `email_drafts (owner_agent_sub, client_id)` where the purpose is `NEWS_ALERT` and the status is `DRAFT` or `SENDING`, and the `sent_out_unmarked` column. `SENDING` is included because a failed send sets the draft back to `DRAFT`, which a second draft made meanwhile would break.

**Rules**
- `NEWS_ALERT` requires `tone = NONE`, `source = NEWS` and at least one report. `NONE` and `NEWS` are refused with any other purpose.
- The ADR-0068 alert follow-up is removed: the alert `{{agent_details}}` slot and `FollowUpSources.alertClient`. `HEALTH_CHECK_IN` is unchanged.
- Settings:
  - `engagement.news.max-reports-per-email`, default 30 (5.4)
  - `engagement.news.summary-lines`, default 10 (5.5)
  - `engagement.sources.pdf-timeout`, default 15 seconds (section 11)

**Endpoints**
- `POST /api/engagement/drafts/news`
- `DELETE /api/engagement/drafts/{id}/reports/{alertId}`
- `POST /api/engagement/drafts/{id}/summary/rebuild`, for "Rebuild summary" (no model call)
- `GET /api/engagement/drafts/{id}/pdf`, for "Preview PDF"
- `POST /api/engagement/drafts/{id}/news-status`
- `GET /api/engagement/drafts/news/open-alerts`, for the dismiss block in 5.1

**Removed (ADR-0084 part 3 and the combined-email work that was never merged)**
- the `alertreports/` package
- `HttpReportPackages` and `ReportPackages`
- the old V3
- `ENGAGEMENT_ALERT_REPORTS_ENABLED`, and the Terraform variable `alert_reports_enabled`
- the `ALERT_REPORT_*` audit actions, and their auditor allow-list entries
- `sendAlertReport` in `engagement.js`

The uncommitted work on `task/event-driven-alerts/combined-client-report` (including its V4) is not committed. This plan replaces it.

## 8. Authorisation

- Every call between the two services uses the agent's own token. Ownership, `INFORMED` and approval are checked again at draft creation, at report add, and at send.
- Admins: read-only on Event Alerts, including the Sent out tab and single-report PDF download. They cannot edit, approve, draft or send.
- Hiding a control on the page is never the only check. **One accepted exception:** the block on dismissing a client who is on an open news draft (5.1) is made by the page, because event-alert-service cannot see engagement's database. A direct API call can still dismiss that client. This is safe, since the send-check then returns `NOT_INFORMED` and nothing is sent to that client, and it is recorded as accepted in ADR-0085 and ADR-0086.

## 9. Audit

| Event | Service | Data (hashes, never text) |
|---|---|---|
| `alert.decision-recorded` | alerts | Unchanged, without monitor |
| `alert.report-edited` (new) | alerts | event, report version, agent, sections changed, edit hash |
| `alert.report-approved` (new) | alerts | approval ID, version, agent, `edited`, original and final hashes |
| `alert.summary-generated` (new) | alerts (worker) | report ID, version, model ID, tokens, passed or failed |
| `alert.sent-out` (new) | alerts | alert IDs, approval IDs, draft ID, SES message ID |
| `DRAFT_GENERATE`, `DRAFT_DISCARD` | engagement | purpose `NEWS_ALERT`, source `NEWS`, alert IDs, report count |
| `EMAIL_SEND` | engagement | as today, plus approval IDs, final hashes and the PDF hash |
| `DRAFT_REPORT_REMOVED` (new) | engagement | draft ID, alert ID |

The auditor allow-list adds the six new events and drops the four `ALERT_REPORT_*` events.

## 10. Errors

| Case | Behaviour |
|---|---|
| An edit fails the checks | 422 naming the sentence. Nothing saved. |
| The report version changed before approval | 409. Reload. |
| The approval expired, or the client was dismissed or reassigned, before sending | Send refused, naming the reports. The agent removes them and sends. |
| A report hash in the PDF response does not match the stored hash | Send refused with 409 "The approved reports changed; reopen the draft". Logged. |
| The PDF call exceeds its timeout | Send refused with 503. Nothing sent. The page suggests removing some reports. |
| event-alert-service down | 503. Nothing created or sent. |
| No summary sentence for a report (switched off, Bedrock down, failed checks) | That report's line uses its first sentence. Nothing blocks. |
| The draft is at the safety limit | That client is skipped and named. |
| Sent Out update fails after SES accepted (the alerts service is unreachable) | Draft `SENT`, with "Retry status update". |
| A report is refused for good after sending (the client was reassigned, or is not in Plan client briefing) | The draft counts as synced; the page lists the reports that could not be marked and why. No retry is offered for them. |
| Two Notify clicks at once for one client | One draft; the second adds to it. |
| Two tabs send the same draft | The existing draft send lease stops the second. |

## 11. Non-functional requirements, cost and security

**Cost**
- no new AWS resources
- one small model call per report (not per draft or per client) when the summary sentence is switched on
- PDFs are rendered when needed and not stored

**Security**
- client addresses are never stored
- no client data, no client's set of reports and no agent-written text goes to a model
- no client data goes into the PDF
- edited report text is kept only until sent or expired

**Accessibility**
- the edit boxes and checkboxes are labelled
- the change from approve to notify is announced
- the staleness warning is in text, not only in colour

**Performance and time limits**
- **Overall limit:** the browser reaches the services through API Gateway HTTP API, then a VPC Link, then the internal ALB ([api.tf](../../infrastructure/terraform/stacks/crm/api.tf)). HTTP API integrations time out after **30 seconds**, a hard limit. The ALB's `idle_timeout = 60` is longer, so it does not matter here. **Every request must finish in under 30 seconds.**
- **The send** is the longest request: send-check, PDF render and fetch, SES, then sent-out. Budget:

  | Step | Time |
  |---|---|
  | send-check | under 1 s |
  | PDF render and fetch | up to `engagement.sources.pdf-timeout`, default 15 s |
  | SES | up to the existing 10 s send timeout, normally under 1 s |
  | sent-out | under 1 s |

  Measure a 30-report render locally and in AWS. Set the safety limit so the render takes well under 15 seconds.
- **Other calls between the services** keep `engagement.sources.timeout`, which is 5 seconds. No model call is made inside a request from the agent.
- **Creating drafts** (`drafts/news`) makes one package call per click, whatever the number of clients, and no model call.

## 12. Tests

**Alerts**
- the state machine, including the `MONITORING` migration and moving from Dismiss back to Plan client briefing with an approval that is still valid
- every edit check:
  - a number added in a cited sentence fails
  - an unknown citation fails
  - advice wording fails
  - a removed section fails
- approval is final, approval expiry, and the staleness values
- retention: text is cleared on Sent Out and on expiry, and not on Dismiss
- combined PDF:
  - cover, contents, one section per company in issuer order, sources numbered from [1] in each section, disclaimer once
  - the disclosure line: edited, not edited, and not yet approved
  - no client data
  - the document date comes from the newest approval
  - the same approvals give byte-for-byte the same PDF on different days
  - one report, and 30 reports
- single-report download: works without an approval, and works for admins
- summary sentence:
  - each check
  - a report that names Bloomberg or Reuters still passes
  - the switch
  - written once per report version
- endpoint tests, including requests for other agents' approvals

**Engagement**
- two parallel Notify calls for one client make one draft; the database refuses a second open draft (`DRAFT` or `SENDING`); a draft being sent is skipped
- a report refused for good after sending is listed and ends the retry; an unreachable alerts service still offers it
- `NEWS_ALERT` / `NONE` / `NEWS` validation
- draft creation:
  - a report is added to the client's existing draft
  - the safety limit
  - a report is never on two drafts
  - no model call happens
- the summary:
  - edited reports use their first sentence
  - at most 10 lines, with "and N more"
  - it is rebuilt when a report is added or removed
  - the "Rebuild summary?" prompt appears only when the summary hash changed, not after a subject-only edit
- the "Where to find each report" guide matches the PDF's contents page, updates after a report is removed, and cannot be edited
- the fixed text is always added
- the MIME message, with no attachment and with one PDF
- a hash mismatch is refused, and a PDF timeout is refused
- the Sent Out call and its retry
- the old V3 and `alertreports/` are gone

**Frontend**
- the Edit / Preview toggle: one text box in place, the text format, the line-numbered errors, and a refused save keeping the agent in Edit
- the batch panel: all ready clients, ticked clients, the kept ticks, the descriptions, the floating helper and the hint under Notify
- the unmarked-reports message and no retry for them
- an old Monitoring status reads as "Monitoring (old option)"
- two decision options
- edit and approve, with the final warning
- the visible button replacement, and the Notify link
- the staleness line
- the locked fields, the report list and Preview PDF
- the summary editor and "Rebuild summary"
- the alternate entry, with its help text and link
- bulk delete with the dismiss prompt, for both Yes and No
- the dismiss block
- the Sent out tab
- single-report download before approval

**Local end to end** with the `log` mail provider:
- 2 clients, 3 tickers, and one client with 12 reports
- check the combined PDF and the raw MIME
- time a 30-report send against the 30-second limit
- screenshots at desktop and phone width

## 13. Deployment and rollback

- **Order:**
  1. alerts migrations
  2. engagement V1 edit and new V3 (teardown)
  3. both services
  4. the frontend
- **Terraform:** remove `alert_reports_enabled` from `stacks/agent-engagement`. No Bedrock change: the summary sentence runs in the worker, which already has `bedrock:InvokeModel` on `global.amazon.nova-2-lite-v1:0`.
- **Rollback:** go back to the previous images. Do this before the first news email is sent, or first change `SENT_OUT` rows back to `INFORMED`, since the old image does not know that value.

## 14. ADRs required before implementation

1. **Alerts, owner Hayyun, with Azeez's approval:** amends ADR-0084.
   - Decisions 10 and 13: reports can be edited, are checked again, are final once approved, and carry a visible disclosure.
   - Decisions 12 to 18: sending moves to Follow-up Emails.
   - The new set of decisions and the Sent Out status.
   - The combined PDF rendered on the server, and the single-report download.
   - The summary sentence, written by the worker, and its model.
2. **Engagement, owner Neo Jia Wen:** amends ADR-0054 and ADR-0068.
   - News Alert replaces Alert follow-up.
   - Tone None and source News.
   - One client per news draft, with one combined PDF.
   - The summary built from per-report sentences, the "Where to find each report" guide, and the fixed text added at send.
   - Bulk delete and the Sent Out callback.

## 15. Open questions

1. **Azeez:** are the edit checks in 5.3 enough for an edited report to go to clients under the bank's name?
2. **Azeez:** is the summary sentence wanted, or is the first sentence of the executive summary enough?
3. **Hayyun and Neo Jia Wen:** is 7 days right for approval validity, with the warning highlighted from day 5?
4. **Hayyun and Neo Jia Wen:** is 30 the right safety limit per email once measured, and 10 the right number of summary lines?
5. **Neo Jia Wen:** one Bcc per email means one per client. Is that the right record for the agent?
6. **Neo Jia Wen:** should the cover page carry the client's name? It is left out so that the PDF holds no client data and is never stored.

## 16. Tasks

| Task ID | Task | Primary Dev | Collaborators | Files/Components | Branch | Dependencies/Conflict Zones | Status | Last Sync |
|---|---|---|---|---|---|---|---|---|
| NA-01 | ADR: alerts amendment | Hayyun | Azeez, Neo Jia Wen | `docs/adr/` | `task/event-driven-alerts/news-alert-drafts` | None | Built on the branch, review pending | 2026-11-07 |
| NA-02 | ADR: engagement amendment | Neo Jia Wen | Hayyun | `docs/adr/` | `task/agent-engagement/news-alert-drafts` | None | Built on the branch, review pending | 2026-11-07 |
| NA-03 | States: remove Monitor, add Sent Out, migration | Hayyun | | `AlertStatus`, `AlertDecisionType`, `AlertStateMachine` | `task/event-driven-alerts/news-alert-drafts` | NA-01 | Built on the branch, review pending | 2026-11-07 |
| NA-04 | Report edits and checks, approval, expiry, retention, staleness values | Hayyun | Azeez | `report/`, `ClientReportValidator`, migration | `task/event-driven-alerts/news-alert-drafts` | NA-03 | Built on the branch, review pending | 2026-11-07 |
| NA-05 | PDF renderer (PDFBox): combined and single-report endpoints, fixed document date and metadata | Hayyun | | `report/`, `pom.xml` | `task/event-driven-alerts/news-alert-drafts` | NA-04 | Built on the branch, review pending | 2026-11-07 |
| NA-06 | Package, send-check, sent-out, approved-reports endpoints | Hayyun | Neo Jia Wen | `api/`, `openapi.yaml` | `task/event-driven-alerts/news-alert-drafts` | NA-04; contract shared with NA-09 | Built on the branch, review pending | 2026-11-07 |
| NA-07 | Summary sentence in the worker, with its checks, behind a switch | Hayyun | Azeez | `report/`, `processing/`, `ai/` | `task/event-driven-alerts/news-alert-drafts` | NA-01 | Built on the branch, review pending | 2026-11-07 |
| NA-08 | Event Alerts page: two options, editor, approve then Notify, staleness line, Sent out tab, dismiss block, single-report download | Hayyun | | `TickerReports.jsx`, `alertEvents.js`, remove `reportPdf.js` | `task/event-driven-alerts/news-alert-drafts` | NA-04 to NA-06, NA-09 | Built on the branch, review pending | 2026-11-07 |
| NA-09 | Engagement: purpose, tone, source, V3 tables, `drafts/news`, summary build and rebuild, preview, guide, fixed text, attachment in mail, send flow and time budget | Neo Jia Wen | Hayyun | `drafting/`, `notifications/`, `integration/sources/`, V1, V3 | `task/agent-engagement/news-alert-drafts` | NA-02, NA-06 | Built on the branch, review pending | 2026-11-07 |
| NA-10 | Remove ADR-0084 part 3 (engagement, auditor, Terraform) | Neo Jia Wen | Hayyun | `alertreports/`, old V3, `AuditAction`, auditor, `stacks/agent-engagement` | `task/agent-engagement/news-alert-drafts` | NA-09 | Built on the branch, review pending | 2026-11-07 |
| NA-11 | Follow-up Emails: locked fields, report list, Preview PDF, summary editor, alternate entry, bulk delete and prompt | Neo Jia Wen | | `AgentDrafts.jsx`, `engagement.js` | `task/agent-engagement/news-alert-drafts` | NA-09; `engagement.js` is changed by both teams | Built on the branch, review pending | 2026-11-07 |
| NA-12 | Runbooks, schema docs, API docs, auditor docs | Hayyun | Neo Jia Wen | `docs/` | Both | All | Done on the branch | 2026-11-07 |
| NA-13 | Anthropic company (V18), seeding script profile, worker summary switch, AWS checklist | Hayyun | Neo Jia Wen | `db/migration`, `scripts/`, `stacks/alert-worker` | `task/event-driven-alerts/news-alert-drafts` | NA-12 | Done on the branch | 2026-11-07 |
