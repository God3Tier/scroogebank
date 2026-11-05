# ADR-0082: One shared list of search websites

- Status: Proposed (2026-11-05; review pending)
- Decision date/time: 2026-11-05 (Asia/Singapore)
- Decision owner(s): Hayyun (proposer and owner); reviewer: Azeez (approved ADR-0079, which this changes)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/event-driven-alerts/search-on-add`
- Related issue/PR: TBD
- Supersedes: [ADR-0079](ADR-0079-agent-added-search-domains-per-company.md) decisions 1, 2 and 4 (per-company websites and who may change them) and the search part of decision 5; the rest of ADR-0079 (the format and blocklist rules, the strict relevance gate, audit, the fixed Terraform list) stands
- Superseded by: N/A

## Context

ADR-0079 let agents add a website for one company. That stores the same website again for every company that needs it, and the same article can be reached through several companies. The agents' request is the plain one: a website an agent adds should be searched for every company, as the shared Reuters, CNBC and FT list already is. ADR-0079 rejected this because a poor website would then be searched for every company. The strict relevance gate (a title naming the company and an event word) already limits what such a website can add, and any agent or admin can remove one.

## Decision Drivers

- No repeated data: one row per website, not one per company.
- Agents, not engineers, widen the search, and every change is traceable and reversible.
- Nothing about the relevance filter or the AI safety screen changes.

## Options Considered

1. **Keep per-company websites** (ADR-0079). Safest per website, but repetitive and slow to use.
2. **One shared list searched for every company.** Chosen.
3. **Add to the Terraform list of shared news sites.** Rejected: that is a deploy, and the point is that agents need no engineer.

## Decision

1. A new table `search_website` holds the websites agents add: the domain, who added it, when, and whether it is active. Removing keeps the row. The shared news sites in Terraform and each company's official sites are unchanged. The old `instrument_source_domain` table is kept as history and no longer read; each distinct active website in it is carried over once.
2. **Who may do what.** Any agent may add a website, whatever their clients hold. Any agent or an admin may remove one. Admins cannot add. The list shows when each website was added and whether the signed-in user added it; other agents' identities are not shown, the audit record holds them.
3. **Guardrails** as in ADR-0079, applied to the whole list: a plain host name (a scheme, path, port, query, wildcard, spaces or an IP address is rejected; input is lower-cased and a leading `www.` is removed); the social, forum and blog blocklist; at most 20 active websites; a website already covered by the shared list or an active website (including a subdomain of one) is rejected as a duplicate.
4. **Search.** Every company's search uses its official sites, the shared news sites and every added website. An added website gets no official-domain exception: the strict relevance gate applies in full.
5. **Audit.** `alert.search-website-added` and `alert.search-website-removed` through the outbox in the same transaction, with the actor, role, domain and correlation ID.
6. **Who holds what.** To make the list useful, `GET /api/alerts/search-holdings` returns each company the caller's clients hold with, for each holding client, the client id, the share of the client's portfolio, the quantity and the value in SGD. An admin also sees the owning agent. Names are not in the alerts database: the page looks client names up in the CRM with the user's own login.
7. **API.** `/api/alerts/search-websites` (list, add, remove), `/api/alerts/search-holdings`, `/api/alerts/search-now` and `/api/alerts/search-schedule` (ADR-0081). The per-company routes under `/api/alerts/search-sources` are removed.

## Consequences

- One row per website, and one place to see and remove it. An article is stored once however many companies reach it.
- A website an agent adds affects every company and every agent's alerts. Any agent or an admin can remove it.
- Each added website lengthens every company's search by one domain, which costs no extra Tavily searches.
- Search quality of an added website is unmeasured. The filter is strict, so a poor website mostly adds nothing.
- The publisher-terms question is not answered per website; the record shows who added it (ADR-0079).

## Security and Privacy Impact

Only public domain names are stored. Client names never leave the CRM except to the signed-in user's own browser, which already may read them. The holdings overview returns anonymous ids, shares, quantities and values to the agent who owns the clients, or to an admin. Authorisation is server-side; the page hides controls but is not relied on. Adding a website lets new third-party text reach the AI classifier; the AI safety screen, source-only citation check and structured output apply unchanged.

## Cost Impact

No new AWS resources and no extra Tavily searches. One small table.

## Operational Impact

Migration `V13` creates `search_website` and carries the existing websites over. No Terraform change for this decision. Removing the per-company routes is a breaking API change for the Event Alerts page only; both ship together.

## Validation

Backend, contract and frontend tests pass, and on a developer machine a website added by one agent was searched for the companies of every agent. Not verified in AWS.

## References

- [ADR-0079](ADR-0079-agent-added-search-domains-per-company.md), [ADR-0081](ADR-0081-search-starts-when-an-agent-adds-a-domain.md), [ADR-0051](ADR-0051-single-issuer-search-and-relevance-gate.md), [ADR-0066](ADR-0066-crm-linked-current-alert-holdings.md)
- [Feature plan](../feature-plans/event-driven-alerts-search-on-add_plan.md)
