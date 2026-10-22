# ADR-0053: Email delivery through Amazon SES

- Status: Accepted
- Decision date/time: 2026-10-21 11:04 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/agent-engagement/plan`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: [ADR-0055](ADR-0055-sender-address-without-a-domain.md) in part (decisions 1, 3 and 9, only for an account without the domain's hosted zone); [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md) decision 2, if accepted (mail-identity is destroyed with every deployment)

## Context

engagement-service ([ADR-0051](ADR-0051-engagement-service-and-mail-dispatcher.md)) sends three kinds of email:

| Kind | Recipient | Trigger |
|---|---|---|
| Calendar invites, updates and cancellations ([ADR-0052](ADR-0052-calendar-invites-by-email.md)) | The agent | An event write |
| Meeting reminders | The agent | The dispatcher, at the reminder's due time |
| Follow-up emails ([ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md)) | A client | The agent pressing Send |

What the CRM already has:

- The project account owns the domain `itsag3t6.com`, with its public hosted zone in Route 53, kept outside Terraform ([ADR-0042](ADR-0042-terraform-deployment.md)).
- The app is served at `sg.<domain>` for prod.
- Prod and staging stacks are created and destroyed for each session ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0046](ADR-0046-human-deploy-access-mfa-role.md)).
- An agent's email address is a Cognito attribute and is not in the access token.
- A client's email address is encrypted in client-service ([ADR-0030](ADR-0030-client-data-envelope-encryption.md)). The owning agent can read it through `GET /api/clients/{clientId}`.

**SES sandbox** (checked in the SES Developer Guide, 2026-10-21). Every new account starts in the sandbox, separately in each region. In the sandbox:

- mail can be sent only to verified addresses and domains, or to the mailbox simulator;
- at most 200 messages in 24 hours;
- at most 1 message a second.

Production access is requested from AWS, which gives an initial response within 96 hours and may ask for more information.

## Decision Drivers

- Send from the CRM's own domain, with SPF, DKIM and DMARC passing.
- No third-party accounts or API keys.
- Recipient verification must not be repeated after every teardown.
- Store as little personal data as possible.
- Cost.

## Options Considered

### Sending service

- **Amazon SES (chosen).**
  - Pros: IAM-authenticated with no secret, in-region, sends raw MIME (needed for invites), cheap.
  - Cons: the sandbox limits recipients and volume.
- **A third-party email API (for example SendGrid or Mailgun).**
  - Pros: easy start.
  - Cons: an outside account, an API key to store in Secrets Manager, and data processed outside AWS.
- **Amazon SNS email subscriptions.**
  - Pros: none that apply here.
  - Cons: fixed formatting, no attachments or calendar parts, and every recipient must confirm a subscription.
- **Opening the client email in the agent's own mail program (`mailto:`).**
  - Pros: no sending infrastructure for client emails.
  - Cons: no record of what was sent or when, and invites and reminders would still need a sender. The owner chose SES for client emails too.

### Where the SES identities live

- **In `stacks/agent-engagement`.**
  - Pros: one stack.
  - Cons: every teardown destroys the domain identity and the verified recipient addresses. Each session would need new DKIM records verified, and every team inbox would need its verification link clicked again.
- **A small long-lived stack, `stacks/mail-identity`, that session scripts never destroy (chosen).**
  - Pros: identities and DNS records persist like the domain itself. SES identities cost nothing while idle.
  - Cons: one more stack to apply once.
- **By hand in the console, like the domain.**
  - Pros: no Terraform.
  - Cons: undocumented drift, and the configuration set and records would also be by hand.

### Where the agent's address comes from

- **Cognito `ListUsers` with `Filter: sub = "<sub>"` (chosen).**
  - Pros: no change to user-service. `sub` is a searchable attribute. Always the current address.
  - Cons: needs `cognito-idp:ListUsers` on the pool, and the call is eventually consistent.
- **A new user-service `GET /api/users/me`.**
  - Pros: user-service stays the only reader of user profiles.
  - Cons: a change to Feature 1 code. It is no help to the dispatcher, which runs without a caller's token.
- **The frontend sends the address from the ID token when the agent opts in.**
  - Pros: no lookup.
  - Cons: the address is supplied by the client, so it cannot be trusted.

## Decision

1. **SES v2 in ap-southeast-1.**
   - The sending domain is `mail.<legal_entity>.<domain>` for prod (`mail.sg.itsag3t6.com`), and `mail.<legal_entity>-<environment>.<domain>` otherwise.
   - Each sending domain has Easy DKIM, a custom MAIL FROM domain (`bounce.<sending domain>`) and a DMARC record at `p=none`.
2. **`stacks/mail-identity`** is a long-lived stack, applied once per environment and never destroyed by the session scripts. It holds:
   - the domain identity and its DNS records, in the existing hosted zone;
   - the configuration set, publishing send, delivery, bounce and complaint counts to CloudWatch;
   - the email identities of the team inboxes used as demo recipients.

   `stacks/agent-engagement` reads the identity and configuration set by name.
3. **Addresses.**
   - Invites and reminders come from `CRM Calendar <no-reply@<sending domain>>`.
   - Client emails come from `"<agent name> via Scrooge Bank" <no-reply@<sending domain>>`, with `Reply-To` set to the agent's address, so replies reach the agent.
4. **Agent address and name.**
   - The service looks them up with Cognito `ListUsers` filtered by `sub`, asking only for `email`, `given_name` and `family_name` (amended 2026-10-21: `name` dropped; see the revision history).
   - Results are cached in memory for 15 minutes.
   - A user who is not found or is disabled is not mailed: the row becomes `FAILED` with `agent_email_unavailable` or `agent_disabled`.
5. **Client address.**
   - Read at send time from client-service `GET /api/clients/{clientId}` with the agent's own token, and used for that one message only.
   - It is never stored, logged or cached.
   - If client-service refuses (not owned, deleted) or is unavailable, nothing is sent.
6. **Sending.**
   - Every message is built as MIME with Jakarta Mail and sent with SES v2 `SendEmail` using raw content and the configuration set.
   - The dispatcher sends at most 1 message a second, and backs off on SES throttling errors.
7. **What is stored.** Only the SES message ID and the send time. No resolved bodies, no recipient addresses, and logs mask addresses.
8. **Sandbox for the demo.**
   - Recipients are verified team inboxes, and demo clients are seeded with those addresses.
   - The mailbox simulator is used to test bounces and complaints.
   - Production access is requested only if plan open question Q8 decides it is needed.
9. **IAM.**
   - The task role may call `ses:SendRawEmail` and `ses:SendEmail` only on the sending identity and the configuration set, with the `ses:FromAddress` condition set to the `no-reply` address. (Amended 2026-10-21: on every identity in the account and region instead of only the sending identity, still with the `ses:FromAddress` condition; see the revision history.)
   - It may call `cognito-idp:ListUsers` only on the CRM user pool.

## Rationale

SES is the only option that needs no outside account or secret, sends calendar invites, and stays inside AWS and the region. Keeping identities in a long-lived stack removes the worst part of the teardown model: re-verifying every recipient each session. Looking agents up in Cognito by `sub` works for the dispatcher, which has no caller token, without changing Feature 1. Reading the client's address only at send time, with the agent's own token, keeps client-service's ownership check and encryption in charge of client data.

## Consequences

### Positive

- One sending path for every kind of email, authenticated with SPF, DKIM and DMARC on the CRM's own domain.
- Nothing to rotate. The task role is the only credential.
- No client address is stored outside client-service.

### Negative / Trade-offs

- In the sandbox, the feature can only email verified inboxes, at most 200 a day and 1 a second. Real use would need production access, which AWS may refuse or delay.
- Team inboxes stand in for synthetic clients' addresses during the demo. The client records themselves stay synthetic.
- A third stack (`mail-identity`) must be applied once, before `agent-engagement`.
- The Cognito lookup is eventually consistent, so an address changed moments earlier may not be used yet.
- In the sandbox, account-level suppression-list management is not available. Bounces are only counted and alarmed, not acted on.

## Security & Privacy Impact

- Email is authenticated with SPF (via the custom MAIL FROM domain), DKIM and DMARC.
- The task role can send only from the `no-reply` address of its own identity.
- Client addresses are read with the agent's own token, so an agent can only email their own clients. They are used once and never stored or logged.
- `Reply-To` exposes the agent's work address to the client, which is intended.
- Email content to clients is reviewed by the agent ([ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md)), and all data is synthetic.
- Calls to SES and Cognito use TLS through the NAT gateway.

## Cost Impact

Prices to be checked on the SES pricing page.

- SES costs about USD 0.10 per 1,000 messages, plus a small charge per GB of attachments. Demo volume is well under USD 1.
- Identities and the configuration set cost nothing.
- DNS records go in the existing hosted zone, so there is no extra charge.

## Operational Impact

- **One-time setup:**
  1. Apply `stacks/mail-identity`.
  2. Wait for DKIM verification (usually minutes; can take longer).
  3. Click the verification email in each team inbox.

  Add these steps to [deploy-aws.md](../runbooks/deploy-aws.md).
- **Session start and stop:** unchanged except for `stacks/agent-engagement`. `mail-identity` stays.
- **Monitoring** ([ADR-0027](ADR-0027-observability-baseline.md)):
  - the configuration set's CloudWatch metrics;
  - alarms on bounce rate and on sending errors;
  - `REMINDER_FAILED`, `INVITE_FAILED` and `EMAIL_SEND_FAILED` audit events.
- **Teardown of the whole project:** destroy `mail-identity` last.

## Validation

- The domain identity shows as verified, and DKIM shows as successful.
- Mail received in Gmail shows SPF, DKIM and DMARC as passing (in "Show original").
- Sending to an unverified address in the sandbox fails cleanly: the row becomes `FAILED` and an audit event is recorded.
- The mailbox simulator's bounce address raises the bounce metric.
- Throttling: queue 30 reminders due at the same moment, and confirm they are all sent at most one a second without being marked `FAILED`.
- In CI, the SES adapter is tested against LocalStack SES. A test asserts that no address or body reaches the logs.

## References

- Amazon SES Developer Guide: "Request production access (Moving out of the Amazon SES sandbox)", "Easy DKIM", "Using a custom MAIL FROM domain", "Configuration sets"
- Amazon Cognito API Reference: `ListUsers` (searchable attributes include `sub`; IAM-signed requests)
- [Agent engagement feature plan](../feature-plans/agent-engagement_plan.md)
- [ADR-0027](ADR-0027-observability-baseline.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0030](ADR-0030-client-data-envelope-encryption.md), [ADR-0042](ADR-0042-terraform-deployment.md), [ADR-0046](ADR-0046-human-deploy-access-mfa-role.md), [ADR-0051](ADR-0051-engagement-service-and-mail-dispatcher.md), [ADR-0052](ADR-0052-calendar-invites-by-email.md), [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md)

## Revision history

- 2026-10-21 11:04 +08:00: proposed.
- 2026-10-21: accepted by the decision owner.
- 2026-10-21: decisions 1, 3 and 9 superseded in part by [ADR-0055](ADR-0055-sender-address-without-a-domain.md): without the domain's hosted zone, mail comes from one verified address. This record is otherwise unchanged.
- 2026-10-21: decision 4 amended after the first deployment. `ListUsers` fails with `InvalidParameterException` when the user lacks a requested attribute, and user-service never sets `name`, so every lookup failed. The service now asks for `email`, `given_name` and `family_name`, which user-service sets on every user. It asks for less than before; the decision is otherwise unchanged.
- 2026-10-21: decision 9 amended after the first deployment. In the SES sandbox every recipient is a verified identity, and SES also checked the task role against the recipient's identity: sends to clients failed with `AccessDeniedException` on `identity/<recipient>`. The role may now send on every identity in the account and region (`identity/*`) and through the configuration set. The `ses:FromAddress` condition is unchanged, so the role still sends only from its own address; recipients are limited by the service, which mails only the agent and that agent's own clients.
- 2026-10-22: [ADR-0058](ADR-0058-every-deployment-ends-with-full-teardown.md) (proposed) would supersede decision 2: `mail-identity` is created and destroyed with every deployment, so SES verification is repeated each time.
