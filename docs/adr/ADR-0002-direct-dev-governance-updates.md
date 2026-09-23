# ADR-0002: Direct `dev` updates for project-wide Git and agent governance

- Status: Accepted
- Decision date/time: 2026-09-24 04:01:12 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- AI-assisted draft: Yes — Codex
- Related branch: `feat/git-workflow-policy`
- Related issue/PR: Not yet assigned
- Supersedes: ADR-0001 decision item 7 only for the exception defined below

## Context

Project-wide Git-workflow and coding-agent instructions apply to every contributor and need to take effect from the shared `dev` baseline. Routing small, documentation-only governance changes through a task branch, feature branch, and pull request adds ceremony without changing application behavior.

ADR-0001 requires every integration into `dev` to use a reviewed pull request. Azeez has authorized a narrow exception for shared Git and agent-governance maintenance.

## Decision

1. A qualifying project-wide Git-workflow or coding-agent instruction change may be committed and pushed directly to `dev` without a pull request.
2. A qualifying commit may change only:
   - `AGENTS.md`
   - `CLAUDE.md`
   - `SKILLS.md`
   - Git-workflow or agent-governance sections of `project_plan.md`
   - the ADR recording that same governance decision
3. The exception does not cover application code, CI workflows, infrastructure, deployment configuration, permissions, secrets, dependencies, feature documentation, or unrelated repository content. Mixed changes must be split or follow the normal PR path.
4. Before the direct push, the contributor or agent must:
   - warn that PR discussion/review and PR-only CI may be skipped;
   - fetch and inspect the latest `origin/dev`;
   - verify the commit contains only qualifying files and content;
   - run applicable documentation checks;
   - verify that the push is a non-forced fast-forward and does not overwrite anyone's work.
5. Azeez's standing authorization removes the need to ask again whether a PR is wanted for a qualifying change, but it does not remove the warning or safety checks.
6. The completion report must identify the direct-to-`dev` commit, checks performed, and any skipped safeguards.
7. Repository protection remains authoritative. If `dev` rejects a direct push, do not bypass protection; use the normal reviewed PR path.
8. Direct pushes to `staging` and `main`, force pushes, and direct feature/code integrations into `dev` remain prohibited.

## Consequences

### Positive

- Shared agent instructions and Git governance can take effect quickly for the entire team.
- Small documentation-only policy updates avoid unnecessary branch and PR overhead.
- The explicit allowlist prevents the exception from becoming a route for application or deployment changes.

### Trade-offs and risks

- Qualifying changes may lack PR discussion, independent review, and PR-only CI results.
- Contributors must carefully separate governance edits from unrelated changes.
- A mistaken governance instruction can immediately affect everyone working from `dev`.

## Alternatives considered

### Retain mandatory PRs for every `dev` change

Rejected by the decision owner because it adds unnecessary ceremony for narrowly scoped shared Git and agent instructions.

### Allow any documentation change to go directly to `dev`

Rejected because feature, architecture, API, security, and operational documentation can materially change system obligations and should retain normal review controls.

### Allow `.github` and CI workflow changes

Rejected because executable automation and permission-bearing configuration carry materially higher supply-chain and deployment risk.
