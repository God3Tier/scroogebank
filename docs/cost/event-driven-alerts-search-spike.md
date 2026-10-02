# Event-driven alerts search-spike cost record

- Owner: Azeez
- Task: `EDA-D1-01`
- Recorded: 2026-09-27 (Asia/Singapore)
- Feature development hard cap: USD 50
- Feature monthly operating alert: USD 25
- Project hard limit: USD 300; protected contingency is not authorized

## Current result

No AWS resources or Web Search queries were created from this workspace because
the configured AWS credentials returned `InvalidClientTokenId`. Measured cost for
this attempted run is therefore USD 0.00. This is not evidence of the cost of a
successful search.

AWS pricing was rechecked on 2026-09-27. The plan's working estimate is USD 7 per
1,000 Web Search queries, or USD 0.007 for one successful query, excluding any
other AgentCore Gateway, logging, monitoring, data transfer, and tax charges.
Listed-price arithmetic is an estimate, not an actual bill.

## Live-run budget

The spike is limited to three initial Web Search calls (estimated search charge
USD 0.021) and must stop after ten calls (estimated search charge USD 0.070) unless
Azeez explicitly records why further sampling is necessary. Result count is
limited to five by the documented run command and ten by code.

After the run, record the Cost Explorer unblended cost when billing data becomes
available. Include the time window, Region, service/usage type, activated cost
allocation tags, query count, any non-search charges, taxes, and billing-data lag.
Do not label the USD 0.007/query calculation as actual cost.

## Cleanup

Delete the target, Gateway, inline role policy, and dedicated IAM role immediately
after evidence collection. Confirm deletion in Tokyo and retain only sanitized
evidence. An undeleted resource or unexpected charge is a stop condition for all
later feature infrastructure.
