# Event-alert current-changes integration - 2026-10-30

Human owner: Azeez. Method: explicitly user-authorized direct merge/push into
`feat/event-driven-alerts`, an unprotected integration branch.
Integration task: `task/event-driven-alerts/current-changes-integration`.

## Scope and ancestry

The integration is prepared from feature tip `dedb306` and contains both:

- Retrieval/universe implementation through local commit `2347a8b`, including
  the search-universe work at `70c494d` (PR #163).
- Current-holdings planning/audit at `8d1c417` and the feature-owner's agent-event
  clarification at `735acfe`.

Only one documentation conflict occurred, in the parent feature plan's task
section. Both the retrieval verification and holdings-planning content were kept.
Application, script and alert-worker Terraform content is unchanged from the
retrieval branch. No unrelated work is included.

## Product clarification

The intended primary experience is one event briefing per agent with an
affected-client list/count and Inform clients / Monitor / Dismiss choices.
Holdings and individual exposure rows support relevance and drill-down, rather
than separate personalised model reviews or duplicate primary cards per client.
Inform records intent for an explicit authorised client set; it sends no message.

This is recorded in the parent/subplan, glossary and proposed ADR-0066.
The grouped read/decision/UI workflow and maintained CRM-linked database holdings
remain future implementation. Existing per-client evidence is not misrepresented
as an already implemented grouped experience.

## Verification of the combined tree

- `mvn -B clean verify`: all six reactor modules succeeded; **456 tests**,
  zero failures, errors or skips, including 204 event-alert tests,
  PostgreSQL/Flyway V1-V9 and LocalStack-backed identity tests.
- Alert-worker `terraform fmt -check`, `init -backend=false`, `validate`
  succeeded; `terraform test` passed **6/6** with the mocked AWS provider and
  overridden CRM remote-state data. No AWS plan/apply was performed.
- New planning-document local links resolve. Git whitespace checks pass and
  no unresolved conflict paths remain.
- Ingestion/model remain disabled by default. No real AWS, Tavily or model
  calls were made for this integration.
- No Go/frontend application code changed in the new local commits.
  Before integration, PR #163's backend, Go, frontend and gRPC jobs were green;
  those results do not constitute CI for the later combined commits.

## Safeguards and limits

The user chose direct merge and push after being told that PR discussion/review
and PR-only CI may be skipped. GitHub reported the target branch unprotected.
The workflow runs on PRs and pushes to main/dev/staging, so this direct feature
push does not itself run that CI. Local tests above supply the verification for
this integration; no reviewer approval is claimed.

Fetch and ancestry checks precede the non-forced push; if the destination advances
outside this history, stop/reconcile rather than overwrite. Check the remote SHA
after the push. The audit and draft decisions remain available for human review;
no production enablement, ADR acceptance or promotion into dev/staging/main is
implied.

The unrelated local edit to
`infrastructure/terraform/bootstrap/terraform.tfvars.example` and root untracked
`plan.md` were excluded and preserved.
