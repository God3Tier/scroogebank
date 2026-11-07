# ADR-0001: User-directed push policy for unprotected work branches

- Status: Accepted
- Decision date/time: 2026-09-24 03:54:28 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- AI-assisted draft: Yes — Codex
- Related branch: `feat/git-workflow-policy`
- Related issue/PR: Not yet assigned
- Supersedes: None

## Context

The repository's branch path is `task/<feature>/<subtask> -> feat/<feature> -> dev -> staging -> main`. Pull requests give the team review history, visible CI results, and traceability, but requiring a PR for every movement between short-lived work branches adds unnecessary ceremony when a human owner intentionally requests a direct push.

Coding agents also need an unambiguous rule for requests such as “push this to the feature branch.” Silently choosing a PR can disregard the user's intended workflow, while silently pushing into another branch can skip review and PR-triggered checks.

## Decision

1. Pull requests remain the default way to integrate one branch into another.
2. Publishing or updating the current working branch is a routine push and does not require asking whether a PR is needed.
3. If a user asks to integrate into a named unprotected `task/*` or `feat/*` branch without specifying the method, the agent must ask whether the user wants a PR or a direct push.
4. If the user explicitly chooses a direct push, the agent must warn them of the exact destination and that PR review, discussion, or PR-only CI may be skipped.
5. A direct integration is allowed only after fetching current refs, checking branch protection and ancestry, confirming ownership, running relevant checks, and verifying that the push is a non-forced fast-forward that does not overwrite another contributor's work.
6. Direct integration does not waive the required branch path, feature plan, ADRs, tests, documentation, ownership, or reporting.
7. Integrations into `dev`, `staging`, and `main` always require reviewed PRs and applicable CI gates.
8. Bypassing branch protection and force-pushing shared branches remain prohibited.
9. The completion report must record the user's choice, destination, checks performed, and any PR-only safeguards skipped.

## Consequences

### Positive

- The user's explicit integration preference is followed for unprotected work branches.
- Agents do not create unnecessary PRs for ordinary current-branch pushes.
- Direct integrations remain deliberate, visible, and bounded by synchronization and safety checks.

### Trade-offs and risks

- A direct push to a feature branch may lack a PR review record or may not run checks configured only for pull requests.
- The warning and completion report add a small amount of process.
- Repository settings remain authoritative; this ADR cannot grant permission where branch protection denies it.

## Alternatives considered

### Require a PR for every branch integration

Rejected because it adds ceremony even when the human owner knowingly requests a direct integration into an unprotected short-lived branch.

### Always direct-push when a destination branch is named

Rejected because an unspecified method is not informed consent to skip review or PR-triggered CI.

### Allow direct pushes to environment branches

Rejected because `dev`, `staging`, and `main` are shared promotion gates and require stronger review, CI, and auditability.

## Follow-up

- Keep repository branch protection enabled for `dev`, `staging`, and `main`.
- Ensure CI has a push-triggered path for shared feature branches where practical; until then, warn when relevant checks are PR-only.
- Revisit this policy if the team introduces merge queues or changes branch protection rules.
