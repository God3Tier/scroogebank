# Agent Engagement Live Checks (AE-T11)

Date: 2026-10-21 to 2026-10-22 (Asia/Singapore)

Branch: `feat/agent-engagement`, up to `895bc93`

Owner: Neo Jia Wen

Status: **in progress.** Most checks passed in a personal AWS account, including Outlook on 2026-10-22. The project account and the full Nova evaluation are still to do (see [Still to do](#still-to-do)).

## Environment

| | Value |
|---|---|
| AWS account | A team member's personal paid account, not the project account |
| Region | ap-southeast-1 |
| Stacks | `bootstrap`, `crm`, `mail-identity`, `agent-engagement` (runbook steps 1 to 7) |
| Mail mode | Sender address ([ADR-0055](../adr/ADR-0055-sender-address-without-a-domain.md)): the account has no `itsag3t6.com` hosted zone, so mail comes from one verified address |
| SES | Sandbox: 200 messages a day, 1 a second, verified recipients only (`get-account`: `ProductionAccessEnabled: false`, `EnforcementStatus: HEALTHY`) |
| Bedrock | Amazon Nova Micro through `apac.amazon.nova-micro-v1:0`; serverless models are enabled automatically, so there was no model-access step |
| Data | Synthetic clients; real team inboxes only as verified SES recipients, kept in git-ignored tfvars |

Mail in the sandbox reaches only verified identities. That includes **agents**: invites and reminders go to the agent's Cognito email, and follow-up emails to the client's email. Both were verified in mail-identity's `verified_recipients`.

## Results

Reported by the owner after running the [deploy runbook](../runbooks/deploy-aws.md) step 8 check 4 and the AE-T11 list.

| # | Check | Result |
|---|---|---|
| 1 | Holiday sync: log `holiday_sync country=SG …`; calendar stops at December 2027 and shows the 2027 holidays | Passed |
| 2 | Sender address: From `CRM Calendar <sender_address>`; SPF and DKIM pass for `amazonses.com`; delivered to the inbox | Passed, Gmail. DMARC alignment does not apply in this mode (ADR-0055) |
| 3 | Invites: create adds the entry, a time change moves it, cancel removes it | Passed in Gmail and Outlook (see [Outlook](#outlook)) |
| 4 | Reminders arrive within 2 minutes of their due time | Passed |
| 5 | Drafts: template label and holiday shown; names filled in with no `{{`; edit, save and reopen; send to the client with `Reply-To` the agent; second send returns 409 | Passed |
| 5a | AI drafts with `ai_enabled = true` are labelled AI-generated | Passed for the drafts tried; the 24-case evaluation is not finished |
| 6 | Access: a second agent sees none of the first agent's meetings or drafts; admins get no engagement pages and 403 from the API | Passed |
| 7 | Audit: the activity feed and the auditor show the `ENGAGEMENT` events | Passed |
| 8 | Failures: an unverified recipient fails with `ses_rejected`, logged with addresses masked | Passed (seen while testing, below) |
| 9 | Operations: no email addresses in the service's logs; no dispatcher-lag alarm | Passed |

### Outlook

Checked on 2026-10-22 with an Outlook.com agent inbox ([ADR-0052](../adr/ADR-0052-calendar-invites-by-email.md) Validation).

| Check | Result |
|---|---|
| Create | Shown as a meeting invite; the entry is added once the agent accepts it, not automatically |
| Times | Shown in Singapore time |
| Time change | The accepted entry moves |
| Title change | The accepted entry updates |
| Cancel | The entry is shown as cancelled |
| Folder | Reminder emails land in the inbox |
| Accept and decline buttons | No error for the agent; the replies to the organizer address are not processed, by design |
| Reminders | Received |

### Domain mode: client emails filed as bulk mail

Checked on 2026-10-22 with the CRM stack's `domain_name` set, so mail came from `mail.sg.itsag3t6.com`.

| Inbox | Authentication | Where the client email landed |
|---|---|---|
| Gmail | SPF, DKIM and DMARC pass | Promotions tab |
| Outlook.com | SPF, DKIM and DMARC pass | Junk |

Authentication was sound, so the filters judged the sender, `"<agent> via Scrooge Bank" <no-reply@mail.sg.itsag3t6.com>` with a different `Reply-To`, as bulk mail. Fixed on `task/agent-engagement/agent-sender`: client emails now come from `"<agent>" <<given>.<family>@mail.sg.itsag3t6.com>` (ADR-0053 decisions 3 and 9 amended). **Not yet deployed**; see [Still to do](#still-to-do).

## Problems found and fixed

Each was found in the deployed stack. Each fix has automated tests and was redeployed, unless the last column says otherwise.

| Symptom | Cause | Fix |
|---|---|---|
| Sending a draft returned 503 "Your email address could not be looked up", and the editor showed `{{agent_name}}` | Cognito `ListUsers` fails with `InvalidParameterException` if a requested attribute is unset on the user; the service asked for `name`, which user-service never sets | `0e326cd`: ask for `email`, `given_name` and `family_name` only; ADR-0053 decision 4 amended |
| Agents had to edit `{{client_first_name}}` and `{{agent_name}}` by hand | Drafts were edited in their stored form | `f398a32`: edit as plain text, names filled in on read and marked again on save ([ADR-0056](../adr/ADR-0056-drafts-edited-as-plain-text.md)) |
| Sending returned 502 with `ses_error`, and the log gave no reason | An `AccessDeniedException` from SES, which the service did not log | `d1b6ef6`: log SES's error code, status and message with addresses masked; send with `FromEmailAddress` |
| `AccessDeniedException 403 … not authorized to perform ses:SendRawEmail on resource identity/<address>` | In the sandbox SES also authorizes the role against each recipient's identity; the policy allowed only the sender's identity | `09d63b0`: `identity/*` with `ses:FromAddress` kept; ADR-0053 decision 9 and ADR-0055 decision 5 amended |
| Invite status Failed: `MessageRejected 400 Email address is not verified` | The agent's Cognito email was not a verified identity | Verified it; `3397ca9` adds this to the runbook |
| A new meeting did not appear until the page was reloaded | Not found: the follow-up reload returned the old list, although nothing in the path caches API responses | `3397ca9`: the calendar shows the save's own response until a reload includes it, and ignores out-of-order reloads |
| Holiday purposes said "no public holiday in the next 30 days" while the calendar showed Deepavali | The client's free-text country was not recognised as Singapore, and the error did not say so | `895bc93`: a separate message for an unrecognised country; punctuation ignored in matching. **Not yet redeployed** |
| A Client Health draft showed "The client's or your name could not be looked up" instead of its text for `agent1@crm.com` (2026-11-07) | Terraform creates the evaluation accounts with `email` only, and user-service adopts them without setting `given_name`/`family_name`, so `ListUsers` failed with `InvalidParameterException` | Names set on the user by hand. **user-service still adopts without names**: a redeploy brings it back |
| Some AI drafts showed literal `\n` instead of line breaks (2026-11-07) | Nova Lite sometimes escapes line breaks twice in its JSON, which parses to a backslash and an `n` | `DraftSafetyScreen` turns escaped `\n`, `\r\n` and `\r` back into line breaks, and prompt `drafts-v4` asks for single escapes (AE-T14). **Not yet redeployed** |
| Calendar could page into years with no holiday data | Holidays were seeded for 2026 and 2027 only | `e997d52`: daily sync from MOM's data.gov.sg dataset and a calendar limit ([ADR-0057](../adr/ADR-0057-holiday-sync-from-data-gov-sg.md)) |

## Automated tests at `895bc93`

```sh
mvn -B -pl services/engagement-service -am verify
cd frontend && npm run lint && npm test && npm run build
terraform -chdir=infrastructure/terraform/stacks/mail-identity test
terraform -chdir=infrastructure/terraform/stacks/agent-engagement test
```

Results: engagement-service 112 tests; frontend lint, 181 tests and build; mail-identity 5 and agent-engagement 6 Terraform tests. All passed.

## Still to do

1. **Redeploy `895bc93`**, then repeat the holiday-greeting draft for a client whose country is `Singapore`.
2. **Nova evaluation** ([ADR-0054](../adr/ADR-0054-ai-follow-up-drafts-and-holiday-data.md) Validation): 24 drafts, the 12 combinations of purpose, tone and holiday (8 for a Singapore client, 4 for a client without holiday data) twice each, split across two agents because of the 20-a-day cap. Pass: at least 22 AI-generated. Record each draft's purpose, tone, client, label and reason here.
3. **Project account, domain mode:** mail-identity creates the `mail.sg.itsag3t6.com` identity and its records; check `dkim_status` is `SUCCESS`. SPF, DKIM and DMARC passing in Gmail and Outlook: done (see [Domain mode](#domain-mode-client-emails-filed-as-bulk-mail)). Deploy the per-agent sender, then send a client email to Gmail and Outlook.com again: it should come from the agent's name and land in Primary and the inbox, with DMARC still passing. If it still goes to Promotions or Junk, sending history and content are what remain. Verify the agents' and clients' inboxes again there (identities are per account), run the Bedrock `converse` check, and repeat a short smoke test.
4. **Cost:** the session's cost against plan §14.
5. **Follow-ups from Client Health and alerts (ADR-0068)**, in domain mode, where the ALB is HTTPS:
   - From an open Client Health task, Draft email opens the form filled in, and the draft has `[Add your details here]`. Send is refused until it is replaced. Once filled in and sent, the client gets the email and the agent gets the Bcc copy, with no Bcc header in the client's copy.
   - From an event briefing, after Inform clients, Draft email works the same for a CRM-linked client. An older fixture alert has no link.
   - The sent draft shows no text, and `email_drafts` holds none.
   - Include these two purposes in the Nova Lite evaluation, and record how often the model kept the slot.
6. **Q8:** decide whether the sandbox is enough for the demo. It worked here with every recipient verified.
7. Tear down the personal account's stacks when finished.
