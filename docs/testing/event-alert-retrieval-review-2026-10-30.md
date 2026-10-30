# Retrieval review packet — 2026-10-30

Human decision owner: Azeez. Required reviewer: Hayyun.
Related: ADR-0011, ADR-0050, ADR-0051 and PR #165.
Prepared with Codex; no human approval is recorded by this packet.

## Proposed PR scope

Review the current-holdings implementation with scheduled ingestion disabled
and `approved_news_domains = []`. Marking the PR ready for review does not
enable ingestion or approve publisher rights. The branch has already been
pushed; the user will decide when to mark the existing draft PR ready.

## Evidence and decisions still needed

| Gate | Evidence | Status / next decision |
|---|---|---|
| Local engineering and CI | 243 backend tests, 262 frontend tests; Go, process integration and mocked Terraform checks in the holdings plan. All 13 CI jobs passed at `c7f9360`. | Verified for that revision. New commits need their own CI. |
| Recall@10 >=80% | Earlier 12-issuer repeat retrieved 7/8 previously labelled events (87.5%). | Retrospective sample only; independent event labels and reviewer sign-off remain open. |
| Actionable precision >=60%, zero persisted cross-issuer sources | Earlier repeat manually judged 8/8 admitted results actionable and issuer-correct. | Same small retrospective sample; does not approve other source configurations. |
| One credit/basic query, <=30 seconds | New billing smoke below: API reports 1 credit, wall time 11.057 seconds. | Single-request evidence passes; historical account billing is unreconciled. |
| Commercial publisher storage/display rights | Source register has no rights evidence for Reuters/CNBC/FT. | Not approved; keep all three disabled. |
| Live generated briefing | Local live search ran with the model disabled. | AWS/Bedrock validation remains open. |

The earlier measurements are in
[the benchmark](tavily-global-portfolio-benchmark-2026-10-29.md). Its passing
retrospective repeat included commercial candidates. It cannot establish the
same recall for the deployed official-only source configuration.

## New billing smoke

One direct HTTPS request to `/search` used the ignored local key, public DBS
issuer terms, `topic=news`, `search_depth=basic`, `auto_parameters=false`, at
most ten results, and only `dbs.com` and `sgx.com`. The window was 2026-10-01
through the exclusive 2026-10-31 end date. `include_usage=true` was added for
this diagnostic; production adapter behavior was not changed.

Result: success; `usage.credits=1`; 11.057 seconds wall time; three results.
No result content, credential, client data, AWS call or model call was stored.
This was a billing probe, not an execution of the Java relevance gate or a
new recall/precision benchmark. One additional provider credit was consumed.

Tavily's [pricing documentation](https://docs.tavily.com/documentation/api-credits)
states basic searches cost one credit. Its
[search reference](https://docs.tavily.com/documentation/api-reference/endpoint/search)
describes per-request usage reporting. API reporting does not reconcile the
earlier benchmark calls with the account invoice/dashboard.

## Terms review findings

Reviewed 2026-10-30. Tavily's
[Platform Terms](https://www.tavily.com/terms), section 10.2, place compliance
with third-party terms on the customer. A Tavily result therefore does not by
itself demonstrate permission to retain publisher excerpts or display derived
content. This is an engineering review finding, not a legal approval.

The official FT, Reuters and CNBC terms URLs could not be fetched by the
research tool in this session. No inference about permission is made from that
failure. No project licence or written publisher permission was found in the
source register. Keep their existing disabled status; a human reviewer must
record applicable terms/licence, excerpt retention, model processing and
display rights before enabling any of these domains. Official issuer domains
also remain subject to the source-register review before a demo refresh.

## Concrete human review procedure

1. Azeez and Hayyun record whether ADR-0051's design is accepted; leave production
   enablement deferred until the remaining evidence is reviewed.
2. Freeze a historical window and the exact source configuration. Independently
   collect issuer/exchange announcements before looking at Tavily output. Record
   issuer ID, event URL, publication timestamp, event type and relevance label.
3. Run the actual adapter/filter for every labelled issuer, retaining ranks and
   rejection reasons. Match equivalent coverage of the same event explicitly;
   do not silently count another story as the expected event.
4. Review every admitted result, including negative examples. Report raw and
   post-filter Recall@10, actionable precision, cross-issuer persistence,
   per-call latency and API usage. Record denominators and reviewer names.
5. Reconcile historical billing using the Tavily account dashboard; add publisher
   rights evidence if commercial domains are requested. A failed/unreviewed
   gate keeps ingestion disabled. Record the final human decision in ADR-0051
   and the source register, then obtain separate AWS authorization/validation.

No independent human label set or final approval has been invented here.
