# ADR-0079: Agents add their own search domains per company

- Status: Accepted (2026-11-04; owner Hayyun; reviewed and approved by Azeez)
- Decision date/time: 2026-11-04 (Asia/Singapore)
- Decision owner(s): Hayyun (proposer and owner); reviewer: Azeez (approved)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/event-driven-alerts/custom-source-domains`
- Related issue/PR: TBD
- Supersedes: none; extends [ADR-0051](ADR-0051-single-issuer-search-and-relevance-gate.md) decision 2 (official domains are fixed reference data)
- Superseded by: [ADR-0082](ADR-0082-one-shared-list-of-search-websites.md) in part (decisions 1, 2 and 4, and the search part of 5: one shared list of websites replaces per-company ones)

## Context

The event-alert worker asks Tavily to search only approved domains, and rejects any result from another domain before it is stored (ADR-0051). For each company the approved list is the company's official domains, held in `instrument.official_domains` and set only by a database migration, plus the shared news sites Reuters, CNBC and FT. Nobody can add a source without a code change and a deploy.

Agents are the people who know which sources matter for the companies their clients hold, so they asked for a way to add their own. The feature must be usable from the UI.

## Decision Drivers

- Agents, not engineers, should be able to widen the search for a company.
- Every domain is a place we store and summarise text from, so each change must be limited, reversible and traceable to a person.
- One company's domains are shared by every agent who holds it, so one agent's choice reaches other agents' alerts.
- The relevance filter and the AI safety screen must keep working on the new sources.

## Options Considered

1. **Keep migrations as the only way.** Safest, but agents cannot act and every addition needs an engineer.
2. **Global extra domains** added to every company's search. Rejected: a bad domain would pollute every company's alerts.
3. **Admin approval for each domain.** Rejected by the requester: admins are the bank's staff and this is an agents' tool.
4. **Per-company domains, added by agents, live at once, with guardrails and an audit trail.** Chosen.

## Decision

1. A new table `instrument_source_domain` holds custom domains per company: instrument, domain, who added it (user subject), when, and whether it is active. Deactivating keeps the row. Official domains and the shared news list are unchanged.
2. **Who may do what.**
   - An agent may add a domain only to a company that one of their own clients currently holds. Ownership is checked against current CRM ownership, as for alerts (ADR-0066).
   - Any agent who currently holds that company may deactivate one of its custom domains, and so may an admin. Admins cannot add.
   - The list shows each domain, when it was added, and whether the signed-in agent added it. Other agents' identities are not shown; the audit record holds them.
3. **Live at once.** There is no approval step. Safety comes from the guardrails in decision 4 and from the audit trail.
4. **Guardrails.**
   - Format: a plain host name such as `example.com`. A scheme, path, port, query, wildcard, spaces or an IP address is rejected. Input is lower-cased and a leading `www.` is removed.
   - A short blocklist of social, forum and blog platforms is rejected (for example reddit.com, x.com, facebook.com, youtube.com, medium.com). It is a list in `SourceDomainPolicy`, so changing it is a reviewed code change.
   - At most 10 active custom domains per company.
   - A domain already covered by the company's official domains, the shared news list, or an active custom entry (including a subdomain of one) is rejected as a duplicate.
   - Adding a domain that was earlier deactivated creates a new entry.
5. **Search and filter.** The worker adds active custom domains to the company's approved domain list for Tavily and for the URL check. They are not treated as official domains: the strict relevance gate applies in full (the company must be named in the title, and an event word must be in the title). Only genuine official domains keep the existing exception that lets the company name appear in the excerpt.
6. **Audit.** Each add and each deactivation writes an audit event through the existing outbox (`alert.search-domain-added`, `alert.search-domain-deactivated`) with the actor, role, company, domain and correlation ID, in the same transaction as the change.
7. **API and UI.** Under `/api/alerts/search-sources` (already routed by the load balancer). The Event Alerts page gets a "Search sources" section per company: list, add, deactivate.
8. The Terraform allow-list of shared news domains is unchanged and does not apply to per-company domains, which live in the database.

## Consequences

- Agents can widen a company's search without a deploy. Change history is in the audit trail.
- One agent's added domain affects every agent holding that company. Any holder can remove it.
- Search quality for a custom domain is unmeasured. The filter is strict, so a poor source mostly adds nothing rather than noise.
- Each extra domain does not add Tavily credits: the same search carries a longer domain list.
- The publisher-terms question is not answered for each new domain. This decision records that agents take on that responsibility when they add a source; see Security.

## Security and Privacy Impact

- Only public domain names are stored. No client, account or holding data is sent in the search or stored with the domain.
- Adding a source lets new third-party text reach the AI classifier. The existing AI safety screen, source-only citation check and structured output apply unchanged. A hostile page is a prompt-injection risk, which the guardrails reduce (blocklist, strict gate, cap) but cannot remove.
- Authorisation is server-side: agents are checked against current holdings, and the UI hides controls but is not relied on.
- Terms of use are not checked by the system. An agent adding a commercial site is responsible for allowing its use; the record shows who added it.

## Cost Impact

No new AWS resources. One small table. No extra Tavily credits per search.

## Operational Impact

New migration `V11` in event-alert-service. No Terraform change. The worker reads the new table on every run, so a change takes effect on the next scheduled run. Ingestion and AI remain off until switched on (ADR-0051 amendment).

## Validation

Unit, integration, API and UI tests listed in the feature plan. Checked against real Tavily on 2026-11-04 (about 3 credits): a search limited to one added domain returned results only from that domain, and the worker's built searches carried it and returned only approved domains, with the strict gate still rejecting weak matches. Not verified: the effect of custom domains on search quality over time.

## References

- [ADR-0051](ADR-0051-single-issuer-search-and-relevance-gate.md), [ADR-0066](ADR-0066-crm-linked-current-alert-holdings.md)
- [Feature plan](../feature-plans/event-driven-alerts-custom-source-domains_plan.md)
- [Source register](../security/event-alert-source-register.md)
