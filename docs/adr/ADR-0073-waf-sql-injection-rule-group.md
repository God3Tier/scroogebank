# ADR-0073: SQL-injection rule group on the WAF

- Status: Accepted (2026-11-02; accepted by Neo Jia Wen)
- Decision date/time: 2026-11-02 21:30 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/infrastructure/load-and-zap-tests`
- Related issue/PR: TBD
- Supersedes: N/A (adds to ADR-0019 decision 1)
- Superseded by: N/A

## Context

The WAF on CloudFront (ADR-0019) uses three AWS managed rule groups:
- **Common rule set:** cross-site scripting, file inclusion, size limits.
- **Known bad inputs:** Log4j, Java deserialisation.
- **Amazon IP reputation list.**

It has no SQL-injection rule group.

During the active ZAP scan through the edge on 2026-11-02 ([report](../testing/zap-active-and-direct-2026-11-02.md)), the WAF blocked the cross-site-scripting and deserialisation payloads, but let ZAP's SQL-injection payloads through to the services. The direct scan from inside the VPC found no SQL injection in any service: they use parameterised queries and validate input. So nothing was exploitable, but the edge did nothing about a whole class of attack.

## Decision Drivers

- Defence in depth: a known attack class should be stopped at the edge as well as in the code.
- No false positives on legitimate traffic, particularly the identity-document uploads (binary bodies up to 5 MB, ADR-0041).
- Low cost.

## Options Considered

### Option A: `AWSManagedRulesSQLiRuleSet`, skipping the document-upload path (chosen)

- Pros: maintained by AWS; one rule block; 200 capacity units, keeping the web ACL under the 1,500 included.
- Cons: another rule group's false positives to watch for.

### Option B: rely on the services alone

- Pros: nothing to change.
- Cons: a regression in one service's query code would be exposed at once.

### Option C: write our own SQL-injection match rules

- Pros: full control.
- Cons: we would have to maintain the patterns AWS already maintains.

## Decision

The web ACL gets a rule `sql-injection` at priority 35, after known bad inputs and before IP reputation. It uses AWS's `AWSManagedRulesSQLiRuleSet` with its default actions, blocking on a match. A scope-down statement skips `POST /api/clients/{id}/documents`, whose binary bodies can look like anything, as the size rule already does. Its metric is `<stack>-sqli`.

## Rationale

The services already defend themselves (parameterised queries, validation, JSON-only APIs); the scans showed that. The rule group adds a second layer, so a SQL-injection attempt is blocked before it reaches API Gateway, a service or a database, for about USD 1 a month.

## Consequences

### Positive

- SQL-injection payloads are refused at the edge, with sampled requests to show which rule matched.
- A future query bug is not exposed straight to the internet.

### Negative / Trade-offs

- A legitimate value that looks like SQL (a client's address with `' OR` in it, say) could be blocked. Watch the rule's sampled requests after deployment; the fix would be a rule-action override for the specific rule, as the size rule has.
- The web ACL's capacity rises to about 1,150 of the 1,500 units included in the base price.

## Security & Privacy Impact

Positive: one more layer against injection. No new data is logged; WAF request logging stays off while the account's SCP denies it.

## Cost Impact

About USD 1 a month for the rule, plus USD 0.60 per million requests already charged for the web ACL.

## Operational Impact

None beyond watching the `-sqli` rule's sampled requests for false positives after the first deployment.

## Validation

- **Terraform:** the plan test asserts the prod web ACL has the `sql-injection` rule with `AWSManagedRulesSQLiRuleSet`.
- **On AWS (to do):** on the next deployment, re-run the active edge scan (`tests/security/zap/README.md`). The SQL-injection payloads should appear among the WAF's blocked sampled requests, and the app's normal use, document uploads included, should see no blocks.

## References

- [ADR-0019](ADR-0019-edge-entry-chain.md), [ADR-0041](ADR-0041-client-identity-documents-and-manual-verification.md)
- [Active and direct ZAP scans, 2026-11-02](../testing/zap-active-and-direct-2026-11-02.md)
