# Event-driven alerts: AgentCore Web Search spike

## Record

- Task: `EDA-D1-01`
- Human owner: Azeez
- Branch: `task/event-driven-alerts/search-spike`
- Review date: 2026-09-27 (Asia/Singapore)
- Status: Harness complete; live AWS evidence pending valid development credentials

This spike is the only Azeez implementation currently unblocked by the feature
plan. It tests the public-data boundary and the feasibility of calling an Amazon
Bedrock AgentCore Web Search Gateway in Tokyo from the Singapore development
environment. It does not establish production architecture or authorize later
infrastructure, ingestion, AI, or operations tasks.

## Verified AWS contract

The AWS documentation was rechecked on 2026-09-27. Web Search is a managed
AgentCore Gateway connector available in Tokyo (`ap-northeast-1`). Connector
`1.2.0` supports target-level domain include filters and request-level domain and
inclusive ISO-8601 UTC publication-date filters. Search accepts a query of at
most 200 characters and 1-25 results. The project spike imposes a tighter maximum
of 10 results.

The connector returns MCP content containing result text, URL, title, and
publication date. In accordance with AWS acceptable-use guidance and the feature
plan, the spike evidence deliberately drops result text/snippets and retains only
the citation metadata needed to evaluate retrieval.

Primary references:

- [AWS Web Search Tool](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/gateway-target-connector-web-search-tool.html)
- [AWS Gateway target configuration](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/gateway-add-target-api-target-config.html)
- [AWS Gateway MCP invocation](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/gateway-using-mcp-call.html)
- [AWS AgentCore supported Regions](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/agentcore-regions.html)
- [AWS AgentCore pricing](https://aws.amazon.com/bedrock/agentcore/pricing/)

## Spike boundary

```text
Singapore development runner
  -> SigV4 / IAM-authorized MCP call
  -> AgentCore Gateway, Tokyo
  -> Web Search connector 1.2.0
  -> mas.gov.sg and sgx.com only
  -> title, URL, domain, publication date only
```

Permitted request values are public issuer, ticker, sector, and event terms plus
public date/domain filters. The harness rejects known private-field labels and
unsupported characters. No client ID/name, account number, holding, exposure,
internal note, agent identity, audit identifier, or credential belongs in a
request or evidence file.

The Gateway uses IAM inbound authorization. Its service role trusts
`bedrock-agentcore.amazonaws.com` with source-account and Tokyo Gateway-ARN
conditions. The role permits Gateway invocation in the project account and Web
Search invocation only against the Tokyo service-owned Web Search ARN. The
calling workload must separately have `bedrock-agentcore:InvokeGateway` on the
created Gateway.

## Implementation and teardown

The runnable harness and instructions are in
`scripts/event-driven-alerts/search-spike/`. Resources are tagged with project,
feature, owner, and purpose. Local state and run evidence are excluded from Git.
Teardown requires the exact gateway ID recorded during provisioning and removes
the target before the Gateway, inline policy, and dedicated role.

If provisioning fails before state is written, inspect the named role
`EdaSearchSpikeGatewayRole` and Gateway `eda-search-spike` in Tokyo and remove
only resources carrying the four spike tags. Never run a broad resource cleanup.

## Evidence gate

The local environment had an AWS profile, but `aws sts get-caller-identity`
returned `InvalidClientTokenId` on 2026-09-27. No AWS resource was created and no
search request was billed. Consequently, cross-region latency, live filtered
results, reliability, and actual cost are not yet measured.

Before marking `EDA-D1-01` complete, Azeez must run from the Singapore development
environment with approved short-lived credentials and attach sanitized evidence
for:

1. successful Gateway/target provisioning and connector version `1.2.0`;
2. at least three bounded searches using only the approved domains and UTC dates;
3. per-run and p50/p95 search latency, success rate, and relevance notes;
4. request inspection showing public search terms only;
5. later Cost Explorer actual charge, distinguished from the listed-price estimate;
6. successful teardown and absence of the target, Gateway, policy, and role.

The downstream search-infrastructure task remains blocked until this evidence and
the applicable detailed ADRs are accepted.
