# ADR-0052: Calendar integration through emailed iCalendar invites

- Status: Accepted
- Decision date/time: 2026-10-21 11:04 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/agent-engagement/plan`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

Agents keep their meetings in their own mail calendar, typically Gmail or Outlook. The [agent engagement feature](../feature-plans/agent-engagement_plan.md) lets them plan client meetings in the CRM ([ADR-0051](ADR-0051-engagement-service-and-mail-dispatcher.md)). Those meetings should also appear in the calendar the agent actually checks.

The CRM does not know which provider an agent uses. Holding OAuth tokens for agents' mailboxes would mean storing long-lived credentials for systems outside the bank's stack. Mail is already sent through SES ([ADR-0053](ADR-0053-email-delivery-through-ses.md)).

Google's OAuth also has a catch for this project. An app in Testing status allows at most 100 test users, and its refresh tokens expire after 7 days. Calendar scopes are sensitive, so leaving Testing needs Google's verification.

## Decision Drivers

- Works with any mail provider.
- No credentials for agents' mailboxes held by the CRM.
- Little to build and test for one developer.
- Meetings appear, move and disappear in the agent's calendar when they change in the CRM.

## Options Considered

- **Emailed iCalendar invites using iTIP: `METHOD:REQUEST` and `METHOD:CANCEL` (chosen).**
  - Pros: every major calendar understands them. No OAuth and no stored tokens. Reuses SES.
  - Cons: one-way only. How an invite is added depends on the recipient's calendar settings. Agents' replies go nowhere.
- **A subscribed calendar feed (a secret `webcal://` URL per agent).**
  - Pros: no email per change.
  - Cons: providers refresh subscribed feeds only every few hours, which is too slow for meeting changes. It needs a route with no login (a bearer secret in the URL), which goes against the edge design ([ADR-0019](ADR-0019-edge-entry-chain.md)).
- **Google Calendar API or Microsoft Graph, with two-way sync.**
  - Pros: true sync, and the CRM could show the agent's other meetings.
  - Cons: per-agent OAuth consent, refresh tokens encrypted at rest, webhooks or polling, conflict handling, a provider app registration, and Google's Testing-status limits. Only one provider per integration.
- **CalDAV.**
  - Pros: open standard.
  - Cons: Gmail and Outlook.com support it poorly or not at all for third parties, and it still needs credentials.

## Decision

1. **Every event write queues one email to the owning agent** through the mail queue ([ADR-0051](ADR-0051-engagement-service-and-mail-dispatcher.md) decision 4):

   | Change | `METHOD` | `SEQUENCE` | `STATUS` |
   |---|---|---|---|
   | Create | `REQUEST` | 0 | `CONFIRMED` |
   | Update of time, title, location or description | `REQUEST` | previous + 1 | `CONFIRMED` |
   | Cancel | `CANCEL` | previous + 1 | `CANCELLED` |

2. **The calendar entry contains:**
   - `UID`: `<event id>@<mail domain>`. It never changes, so updates and cancellations match the original.
   - `DTSTART` and `DTEND` in UTC (`…Z`), with `DTSTAMP` set to the send time.
   - `SUMMARY`, `LOCATION` and `DESCRIPTION`: exactly what the agent typed. `DESCRIPTION` ends with a link to the event in the CRM (`CRM_APP_URL`).
   - `ORGANIZER`: the CRM's sending address ([ADR-0053](ADR-0053-email-delivery-through-ses.md)).
   - `ATTENDEE`: the agent, with `ROLE=REQ-PARTICIPANT`, `PARTSTAT=NEEDS-ACTION` and `RSVP=FALSE`.
   - No `VALARM`. The CRM sends its own reminders, and an alarm in the invite could give the agent two reminders.
3. **Message format.** `multipart/mixed`, containing:
   - a `multipart/alternative` part with `text/plain`, `text/html`, and `text/calendar; method=REQUEST` (or `CANCEL`) in UTF-8;
   - the same calendar as an `invite.ics` attachment, for mail programs that only read attachments.
4. **The agent is the only attendee.** Clients are never invited, and the CRM never adds a client's name or details to an invite. Only what the agent typed appears.
5. **One-way.**
   - The CRM is the source of truth.
   - Accept or decline replies go to the organizer address and are not processed.
   - Changes the agent makes in their own calendar are not read back. The UI says so.
6. **Preferences.** With `invitesEnabled` off, no invite, update or cancellation is queued. Entries already in the agent's calendar stay as they were.
7. **Library.** iCalendar text is built with a maintained library, ical4j (version pinned in the service's `pom.xml`). It handles line folding and escaping as RFC 5545 requires.

## Rationale

Invites reach every calendar the agents might use and need no credentials, no new infrastructure beyond SES, and no sync logic. The weaknesses are being one-way and depending on recipient settings. Both are acceptable for a CRM where the meeting is created and owned in the CRM, and both are easy to explain in the demo. Two-way sync stays possible later as a separate decision for one chosen provider.

## Consequences

### Positive

- Works with Gmail, Outlook and any other standards-compliant calendar, without the CRM knowing the provider.
- No OAuth tokens to protect, rotate or revoke.
- One sending path for invites, reminders and client emails.

### Negative / Trade-offs

- An edit in the agent's calendar is not synced back and can be overwritten by the next update from the CRM.
- Whether an invite is added automatically or must first be accepted depends on the recipient's calendar settings. Gmail, for example, has a setting for adding invitations from unknown senders.
- Turning invites off leaves earlier entries behind.
- Every event change costs one email. That is negligible in cost, but it uses part of the sandbox's 200 messages a day.

## Security & Privacy Impact

- No mailbox credentials of any kind are stored.
- Invites carry only agent-typed text and a CRM link. They go only to the agent's own address, over TLS from SES ([ADR-0053](ADR-0053-email-delivery-through-ses.md)).
- The CRM never reads agents' calendars.
- The CRM link in the description needs the agent to sign in, so it reveals nothing to someone who only sees the invite.

## Cost Impact

One SES message per event change, at about USD 0.10 per 1,000 messages. Negligible.

## Operational Impact

- Failures appear as `INVITE_FAILED` audit events and as `FAILED` queue rows, retried as in ADR-0051.
- No extra resources or runbook steps beyond SES ([ADR-0053](ADR-0053-email-delivery-through-ses.md)).

## Validation

- Unit tests on the generated calendar text:
  - a stable `UID` across create, update and cancel;
  - `SEQUENCE` goes up on each change;
  - UTC times;
  - the right `METHOD` and `STATUS`;
  - escaping of commas, semicolons and new lines;
  - folding of long lines.
- Check the generated text with an iCalendar validator.
- End to end, from the AWS sandbox to one Gmail and one Outlook inbox: create, move and cancel an event, and confirm the calendar entry is added, moved and removed each time. Record which Gmail setting was used.

## References

- RFC 5545 (iCalendar) and RFC 5546 (iTIP)
- [Agent engagement feature plan](../feature-plans/agent-engagement_plan.md)
- [ADR-0019](ADR-0019-edge-entry-chain.md), [ADR-0051](ADR-0051-engagement-service-and-mail-dispatcher.md), [ADR-0053](ADR-0053-email-delivery-through-ses.md)

## Revision history

- 2026-10-21 11:04 +08:00: proposed.
- 2026-10-21: accepted by the decision owner.
