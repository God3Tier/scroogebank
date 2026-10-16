# AgentCore Web Search spike (EDA-D1-01)

This is a bounded, disposable spike for Azeez's approved Singapore-to-Tokyo
AgentCore Web Search task. It is not production infrastructure. It provisions a
Tokyo (`ap-northeast-1`) IAM-authorized Gateway, pins the managed `web-search`
connector to `1.2.0`, and enforces `mas.gov.sg` and `sgx.com` at target level.

The invocation accepts only short public-market queries, repeats a subset of the
domain allowlist at request level, requires inclusive UTC publication bounds, and
stores only title, URL, domain, and publication date. It does not store snippets.

## Prerequisites

- Python 3.12 or later.
- A temporary AWS development identity with permission to manage the named IAM
  role and AgentCore Gateway resources and invoke the created Gateway.
- Billing tags activated for `Project`, `Feature`, `Owner`, and `Purpose` if the
  team wants tag-filtered Cost Explorer evidence.
- Approval to spend against the feature's USD 50 development hard cap.

Do not place access keys in this directory. Use an AWS CLI profile or short-lived
environment credentials.

## Run

```powershell
cd scripts/event-driven-alerts/search-spike
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python -m unittest -v test_agentcore_spike.py
.venv\Scripts\python agentcore_spike.py --profile <profile> provision
.venv\Scripts\python agentcore_spike.py --profile <profile> invoke `
  --query "DBS Group official market announcement" `
  --domains "mas.gov.sg,sgx.com" `
  --date-from "2026-09-26T00:00:00Z" `
  --date-to "2026-09-27T23:59:59Z" `
  --max-results 5 `
  --ack-public-data
```

Run at least three bounded invocations from the Singapore development environment
and preserve the sanitized evidence files outside Git. Record p50/p95 latency,
success rate, result relevance, and the later Cost Explorer charge in the spike
report. A local workstation run does not prove Singapore-to-Tokyo latency.

## Teardown

Read `.spike-state.json`, then explicitly confirm its gateway ID:

```powershell
.venv\Scripts\python agentcore_spike.py --profile <profile> teardown `
  --confirm <gateway-id-from-state>
```

The command deletes only the recorded target, Gateway, inline policy, and IAM
role. Confirm they are absent in the Tokyo console. Keep sanitized evidence and
billing records, then delete the untracked local state/virtual environment.

## IAM boundary

The Gateway role trusts `bedrock-agentcore.amazonaws.com`, constrained by source
account and Tokyo Gateway ARN pattern. Its inline policy permits only
`bedrock-agentcore:InvokeGateway` on Gateways in the project account and
`bedrock-agentcore:InvokeWebSearch` on the Tokyo service-owned Web Search ARN.
The invoking identity separately needs `InvokeGateway` on the created Gateway.
