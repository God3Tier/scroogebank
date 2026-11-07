# ADR-0068: Follow-up drafts from Client Health and alerts; agent details; draft text kept only while open

- Status: Accepted (2026-11-01; accepted by the decision owner for engagement-service, and for the team, including the Client Health and alert links, by Neo Jia Wen)
- Decision date/time: 2026-11-01 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/agent-engagement/linked-follow-ups`
- Related issue/PR: TBD
- Supersedes: [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md) in part: decision 2 (two more purposes and a third placeholder, `{{agent_details}}`), decision 4 (the screen checks the slot by purpose) and decision 6 (a draft's text is cleared once it is sent or discarded, and the agent gets a Bcc copy)
- Superseded by: the `ALERT_FOLLOW_UP` purpose by [ADR-0086](ADR-0086-news-alert-drafts-in-follow-up-emails.md) (proposed 2026-11-07); the Client Health follow-up and draft retention stand

## Context

Client Health ([ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md)) decides which clients need attention. Event alerts ([ADR-0066](ADR-0066-crm-linked-current-alert-holdings.md)) tell an agent which clients a public event may affect, and let them record "Inform clients". Neither feature contacts clients: both feature plans list client-facing messages as non-scope. Follow-up drafts (ADR-0054) are where an agent writes to a client. Agents want to go from a task or an alert straight to a draft. The course brief also asks for features that "participate in the overall business workflow", and this gives the three features one workflow while each keeps a distinct job.

Four constraints shape the design:

- **The model's input stays generic.** ADR-0054 decision 2 gives the model the purpose, tone, holidays and placeholders, never who the client is. Task signals ("a recurring credit stopped") and alert content (instrument, headline) are about the client's finances. The plan also bars product, investment and performance content from drafts.
- **A link must be trustworthy.** If a draft records the task or alert it came from, that source must be the agent's own and about the same client. Otherwise the audit trail is misleading.
- **The agent's own words are not screened.** The safety screen checks model output only. Specifics come from the agent, who is responsible for them.
- **Draft text outlives its use.** `email_drafts` kept every subject and body after a draft was sent or discarded. Client erasure ([ADR-0030](ADR-0030-client-data-envelope-encryption.md)) does not reach `engagement-db` (plan Q6).

Alerts on CRM-linked holdings carry the CRM client UUID as `clientRef` (ADR-0066). Older fixture alerts carry a synthetic reference that matches no CRM client.

## Options Considered

- **Generic purposes linked to a verified source, with the agent adding details (chosen).**
  - Pros: the model input is unchanged in kind; the link is checked by the owning service; no advice or inferred findings come from the model.
  - Cons: two more service calls when a follow-up is created; the specific part is the agent's to write.
- **Pass the task's signals or the alert's headline to the model.**
  - Pros: more specific drafts.
  - Cons: client-derived financial data reaches the model; drafts would hint at what the bank inferred, or discuss investments, both against the plans' non-scope.
- **Store the task or alert ID without checking it.**
  - Pros: no new integration.
  - Cons: the audit trail could link an email to a task or alert that is another agent's, or about another client.
- **Keep draft text, and rely on erasure later.**
  - Pros: agents can reread sent emails in the CRM.
  - Cons: text the agent typed, which may hold client details, is kept indefinitely and is not erased.

## Decision

1. **Two follow-up purposes, each linked to its source.**
   - `HEALTH_CHECK_IN` needs `healthTaskId`. `ALERT_FOLLOW_UP` needs `alertId`. Each field is allowed only with its own purpose (400 otherwise).
   - engagement-service reads the source with the agent's own token: `GET /api/relationship-health/tasks/{taskId}` or `GET /api/alerts/{alertId}`, through the internal ALB.
     - 401, 403 or 404 means the source is not the agent's (400).
     - The source must be about the draft's client: the task's `clientId`, or the alert's `clientRef`, must equal it (400 otherwise). An older alert with a synthetic `clientRef` never matches.
     - Any other failure, or no configured base URL, saves nothing (503).
   - Only the source's client field is parsed. The task's explanation and evidence, and the alert's headline, holdings and briefing, are never read, stored, logged or sent to the model.
   - The draft stores the source's ID. `DRAFT_GENERATE` and `EMAIL_SEND` audit events carry it.
2. **The agent's details slot.**
   - The two purposes have one `{{agent_details}}` in the body: in every template, and required exactly once in model output. Any other purpose may not use it, and the subject never holds it. The screen rejects a reply that breaks this rule, and the template is used instead.
   - The prompt lists the slot only for these purposes. Prompt version `drafts-v2`, templates `templates-v2`.
   - The agent sees the slot as `[Add your details here]`. Saving it untouched stores the placeholder again, as for names (ADR-0056).
   - Sending is refused (422) while the slot is still there.
3. **The agent is responsible for what they add.**
   - The agent's text is sent as written. The safety screen and the plan's "no advice" non-scope cover generated content only.
   - The editor says so: "Anything you add is sent as written and is not checked."
   - `EMAIL_SEND` audit data adds `purpose`, `source` and `edited`. `edited` is whether the agent changed the generated text. No text is audited.
4. **Draft text is kept only while the draft is open.**
   - Sending clears `subject_template` and `body_template` in the transaction that marks the draft `SENT`. Discarding clears them too. A failed send keeps them. A table check requires text for open drafts.
   - A daily job, run also soon after start-up, discards open drafts not changed for 14 days (`engagement.drafts.expire-after`). It runs in every task: rows are locked `FOR UPDATE SKIP LOCKED`. Each expired draft is audited as `DRAFT_DISCARD` with actor `system` and `reason: "expired"`. A discard by the agent has `reason: "agent"`.
   - Client emails are Bcc'd to the agent, named only in SES's `Destination`, never in the MIME headers. The agent's copy lives in their own mailbox, as their calendar invites do.
   - What erasure can miss is now limited to open drafts younger than 14 days. A deletion signal from client-service (Q6) would close the rest.
5. **Infrastructure.**
   - The crm stack's `engagement_inputs` gains `alb_https`: the internal ALB's HTTPS base URL and port, only with a custom domain. Client Health's own ALB calls are only made with a custom domain too.
   - The engagement stack sets `CLIENT_HEALTH_BASE_URL` and `ALERT_SERVICE_BASE_URL` from it and allows its tasks into the ALB's security group.
   - Without a custom domain, or with a crm state from before this change, the two purposes fail closed with 503.
6. **Entry points.**
   - On the Client Health page, an agent's open or in-review task has "Draft email".
   - On the event briefing, an affected client the agent has chosen to inform (`INFORMED`), whose `clientRef` is a CRM client ID, has "Draft email".
   - Both open `/agent/drafts?purpose=…&clientId=…&healthTaskId=…|alertId=…`, which fills in the form. The two purposes are offered only from such a link.

## Rationale

The link gives the three features one workflow without moving any data the model may not see. Client Health and alerts decide who to contact and why. Engagement handles how. Checking the source through its owner, with the agent's token, reuses the ownership rules those services already enforce, as the client check does with client-service. A slot that blocks sending makes the split between generic and specific visible, and stops a placeholder email from reaching a client. Clearing text at the end of a draft's life is the smallest change that bounds both retention and the erasure gap, and the Bcc keeps the agent's record outside the CRM.

## Consequences

### Positive

- Agents go from a task or an alert to a reviewed email in one step, and the audit trail links the email to its source.
- The model's input and the screen's rules are unchanged in kind.
- `engagement-db` keeps no text for finished drafts, and abandoned drafts are cleared within 14 days.

### Negative / Trade-offs

- A sent email can no longer be reread in the CRM, only in the agent's mailbox.
- The two purposes depend on Client Health and event-alert-service being up, and on a custom domain.
- If the model often leaves out the slot, these purposes mostly get templates. The live Nova Lite evaluation (AE-T11) should include them.
- Unscreened agent text can contain anything. Accountability rests on the agent and the `edited` flag.
- `subjectTemplate` and `bodyTemplate` become nullable, and `DraftPurpose` gains two values. Both are breaking contract changes, accepted in `docs/api/accepted-breaking-changes.txt`. The frontend ships with them.

## Security & Privacy Impact

- No client-derived data reaches the model. `theModelGetsTheDetailsSlotButNothingFromTheTask` checks that the task ID and client ID are absent from the model input.
- Source lookups use the agent's own token. engagement-service gets no new credentials.
- Agent-typed text is kept only while a draft is open, at most 14 days without a change.
- The Bcc address is the agent's own, taken from Cognito as for `Reply-To`, and never stored.

## Cost Impact

Negligible: two short HTTP calls per follow-up of these purposes, and one daily query.

## Operational Impact

- New configuration: `CLIENT_HEALTH_BASE_URL`, `ALERT_SERVICE_BASE_URL`, `engagement.drafts.expire-after` (14d), `engagement.drafts.expiry-interval` (24h).
- Apply the crm stack before agent-engagement, as now.
- `V1__engagement_foundation.sql` is edited in place: two purposes, nullable templates, `health_task_id`, `alert_id`, `edited`, and the text check. Reset local engagement-db volumes.
- The Client Health page and the event briefing gain a link. Their owners review those changes.

## Validation

- `DraftApiTest`:
  - the source checks: required, wrong purpose, not the agent's, another client, a synthetic `clientRef`, and the source unavailable;
  - the model sees the slot but nothing from the task;
  - an untouched slot blocks sending, and filling it in sends;
  - the text is cleared on send and discard;
  - the Bcc goes to the agent;
  - `edited` appears in the audit data.
- `DraftExpiryTest`: drafts expire after 14 days, in batches, with system audit events. Recent and sending drafts are left alone.
- `DraftSafetyScreenTest` and `draft-screen-cases.json` (v2): the slot is required for the two purposes and refused elsewhere, and every template passes.
- `PlaceholdersTest`: the slot round trip.
- `HttpFollowUpSourcesTest`: the agent's token is sent, only the client field is read, and errors fail closed.
- `SesMailChannelTest`: the Bcc is in the destination only.
- Frontend: `AgentDrafts.test.jsx`, `ClientHealthMonitor.test.jsx`, `EventBriefings.test.jsx` and `engagement.test.js`.
- Terraform: `stacks/agent-engagement` plan tests cover the ALB rule and base URLs with and without `alb_https`.

## References

- [ADR-0030](ADR-0030-client-data-envelope-encryption.md), [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md), [ADR-0053](ADR-0053-email-delivery-through-ses.md), [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md), [ADR-0056](ADR-0056-drafts-edited-as-plain-text.md), [ADR-0066](ADR-0066-crm-linked-current-alert-holdings.md), [ADR-0067](ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md)
- [Agent engagement plan](../feature-plans/agent-engagement_plan.md) (Q6, Q7), [Agent engagement API](../api/engagement-api.yaml)
- [Client Health contracts](../api/client-health-monitor-contracts.md), [Event-driven alerts contracts](../api/event-driven-alerts-contracts.md)

## Revision history

- 2026-11-01: proposed by the decision owner, who chose verified links, generic AI input with agent details, agent responsibility for added text, and text kept only while a draft is open. Alert follow-ups were added once alerts carried CRM client IDs (ADR-0066).
