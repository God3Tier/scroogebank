# ADR-0055: Send from a verified address when there is no domain

- Status: Accepted
- Decision date/time: 2026-10-21 21:14 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/agent-engagement`
- Related issue/PR: TBD
- Supersedes: [ADR-0053](ADR-0053-email-delivery-through-ses.md) decisions 1, 3 and 9 in part (only for an account without the domain's hosted zone)
- Superseded by: N/A

## Context

[ADR-0053](ADR-0053-email-delivery-through-ses.md) decision 1 sends all engagement mail from `mail.<app host>`, a subdomain of the CRM's domain, with Easy DKIM, a custom MAIL FROM domain and DMARC. Those need DNS records in the domain's Route 53 hosted zone.

Only the project account owns `itsag3t6.com` and its hosted zone. The CRM stack already deploys without a domain in any other account, such as a teammate's personal one: `domain_name` is optional, and the app falls back to its `*.cloudfront.net` address ([ADR-0042](ADR-0042-terraform-deployment.md) decision 8). Agent engagement had no such fallback:

- `stacks/mail-identity` required `domain_name` and failed without the hosted zone;
- `stacks/agent-engagement` stopped its plan when the CRM stack had no domain.

So engagement could not be deployed or demonstrated outside the project account. Registering a domain in another account costs about USD 15 a year, which AWS credits do not cover ([deploy-aws.md](../runbooks/deploy-aws.md#register-a-domain-once-per-account)).

SES can also verify a single email address as an identity. It sends a verification link to that address, and needs no DNS records. SES then signs the message with its own `amazonses.com` DKIM key and uses its own MAIL FROM domain, so SPF and DKIM pass but neither aligns with the From address's domain. Whether DMARC then passes depends on that domain's published policy.

## Decision Drivers

- Engagement must deploy in any account the CRM deploys in.
- No change to how the project account sends mail.
- No change to engagement-service code.
- Least-privilege IAM stays: the task role sends only as one identity, from one address.
- No real addresses in the repository.

## Options Considered

- **Fall back to one verified sender address when `domain_name` is not set (chosen).**
  - Pros: no domain or DNS needed; configuration only; the project account is unchanged.
  - Cons: no DKIM or DMARC alignment for the sender's domain, so delivery depends on that domain's DMARC policy; one person's inbox becomes the sender.
- **Require a domain in every account (ADR-0053 as accepted).**
  - Pros: proper authentication everywhere.
  - Cons: engagement cannot run in an account without a domain unless someone pays for one.
- **Use a domain identity for a domain hosted outside Route 53.**
  - Pros: DKIM alignment.
  - Cons: DNS records would be added by hand outside Terraform, and someone still has to own a domain.
- **Turn engagement mail off without a domain (`mail_enabled = false`).**
  - Pros: nothing to verify.
  - Cons: invites, reminders and client emails, the point of the feature, cannot be shown.

## Decision

1. **Mode.** With the CRM stack's `domain_name`, ADR-0053 applies unchanged. Without it, mail comes from `sender_address`, a single SES email identity, with no hosted zone lookup and no DNS records.
2. **`stacks/mail-identity`.**
   - `domain_name` becomes optional.
   - A new `sender_address` variable is required when `domain_name` is null. It is ignored when `domain_name` is set.
   - Without a domain, the stack creates the email identity for `sender_address` with the same configuration set, and skips the domain identity, DKIM, MAIL FROM and DMARC.
   - An address that is both `sender_address` and in `verified_recipients` is created once.
3. **`stacks/agent-engagement`.**
   - It takes the same `sender_address`, and uses it when the CRM stack has no `app_host`.
   - It looks the identity up by that address, so a mismatch with mail-identity fails the plan.
   - The plan also fails, as before, when there is neither a domain nor a `sender_address`.
4. **Addresses** (replaces ADR-0053 decision 3 in this mode).
   - Invites and reminders come from `CRM Calendar <sender_address>`.
   - Client emails come from `"<agent name> via Scrooge Bank" <sender_address>`, with `Reply-To` set to the agent, as before.
   - The domain part of `sender_address` becomes `ENGAGEMENT_MAIL_DOMAIN`, the calendar `UID` suffix ([ADR-0052](ADR-0052-calendar-invites-by-email.md)). UIDs stay unique because they start with the event's UUID.
5. **IAM** (replaces ADR-0053 decision 9 in this mode). The task role may send only as the `sender_address` identity, through the configuration set, with the `ses:FromAddress` condition set to `sender_address`.
6. **Real addresses** go only in the git-ignored tfvars. Examples use `example.test`.

## Rationale

The fallback mirrors the CRM stack's own: a domain when the account has one, a working default when it does not. Both modes share the configuration set, the IAM shape and the service settings, so engagement-service needs no change and the project account keeps ADR-0053's authenticated sending. The cost is weaker authentication outside the project account, which is acceptable for test and demo deployments and is documented in the runbook.

## Consequences

### Positive

- Engagement deploys in any account, with no domain to buy.
- The project account is unchanged.

### Negative / Trade-offs

- Without a domain, the message is not DKIM-signed by the sender's domain, so DMARC alignment fails. If that domain publishes `p=quarantine` or `p=reject`, as many webmail providers do, mail may go to spam or be refused. The runbook says to check the policy and prefer a domain with `p=none` or no DMARC record.
- Mail comes from a team member's address, not a `no-reply` address. Bounces still go to SES's MAIL FROM domain, and replies to client emails still go to the agent through `Reply-To`.
- `sender_address` must be the same in two stacks. Runbook step 4a copies it from mail-identity's tfvars into agent-engagement's.
- In the sandbox, the sender address can also receive mail, as any verified identity can.

## Security & Privacy Impact

- The task role still sends as one identity, from one address, through one configuration set.
- No real address is committed. Tests and examples use `example.test`.
- Weaker authentication makes the mail easier to spoof in this mode. It is used only for test and demo deployments with synthetic data.

## Cost Impact

None. An email identity costs nothing, and no hosted zone or domain is needed in this mode.

## Operational Impact

- Runbook step 4a copies `domain_name` from the CRM stack's tfvars. Without it, the operator sets `sender_address` in mail-identity's tfvars, step 4a copies it into agent-engagement's, and the operator clicks the verification email once.
- Troubleshooting rows cover an unverified sender address, mismatched values and mail going to spam.

## Validation

- `terraform test` in `stacks/mail-identity`: without a domain, there is no hosted zone lookup or DNS record, the sender identity uses the configuration set, and neither variable set fails the plan.
- `terraform test` in `stacks/agent-engagement`: without a domain, the service gets `sender_address` and its domain, and the IAM policy allows only that identity and address; without either, the plan fails.
- In an account without the domain (AE-T11): an invite reaches a verified inbox, and Gmail's "Show original" shows SPF and DKIM passing for `amazonses.com`.

## References

- Amazon SES Developer Guide: "Creating an email address identity", "Authenticating email with DKIM in Amazon SES", "Complying with DMARC authentication protocol in Amazon SES"
- [ADR-0042](ADR-0042-terraform-deployment.md), [ADR-0052](ADR-0052-calendar-invites-by-email.md), [ADR-0053](ADR-0053-email-delivery-through-ses.md)
- [Deploy runbook, step 4a](../runbooks/deploy-aws.md)

## Revision history

- 2026-10-21: proposed.
- 2026-10-21 21:14 +08:00: accepted by the decision owner.
