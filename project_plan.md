# CS301 UBS/Scrooge Global Bank CRM — Project Plan & Repository Operating Model

> **Purpose:** This file is the single starting point for humans and coding agents (Codex, Claude, or other approved assistants) working on the project. It defines the project scope, repository structure, branch/promotion model, feature workflow, ADR discipline, agent context files, testing/CI/CD expectations, documentation requirements, AWS cost controls, and AI-usage controls.
>
> **Rule:** This file describes *how the project is run*. Detailed implementation decisions belong in feature plans and ADRs. If this file conflicts with the official CS301 project specification or AI Usage Policy, the official course documents take precedence.

Coding agents should use the root [`AGENTS.md`](AGENTS.md) as their entry point. It directs them to read this plan and the relevant feature plans, ADRs, and technical documentation before making changes.

---

## 1. Project Objective

Build a secure, cloud-native, maintainable and scalable back-office CRM for a global bank. The CRM must support two roles — **Admin** and **Agent** — and manage:

1. CRM users (admins/agents)
2. Client profiles
3. Client bank accounts
4. Client account transactions imported from an external system
5. Three innovation features, with **Feature 4 requiring AI integration**

The project must be deployable on AWS, have automated testing and CI/CD, and be explainable by the team during the project demonstration.

### Project-specific constraints

- **Course maximum total AWS/Bedrock budget: USD 400.** Exceeding this can trigger a severe grade penalty.
- **Project hard spending limit: USD 300.** The team MUST design, deploy, test, and operate as though USD 300 is the absolute usable ceiling.
- **Reserved emergency buffer: USD 100.** The remaining USD 100 of the course allocation is contingency only and MUST NOT be treated as normal project budget. It may be used only if the team explicitly approves an exceptional need.
- The team is responsible for monitoring all AWS usage and preventing accidental overspend.
- The system must use dummy/synthetic project data only. Never place real credentials, secrets, `.env` contents, or real confidential/PII data into an AI assistant.
- Every team member must be able to explain and defend the architecture, code, AWS resources, trade-offs and costs they contributed to.
- GenAI may assist with design, coding, testing, IaC, CI/CD, debugging, cost/security suggestions and documentation, but all generated work must be verified by the team.
- The final report must acknowledge GenAI usage, including the tools used, where/how they were used, and representative prompts required by the course policy.

### Mandatory internal budget guardrail

The official course allocation is USD 400, but this project operates under a stricter rule:

- **USD 300 = hard project spending limit**
- **USD 100 = protected contingency buffer**
- Normal architecture, implementation, testing, staging, production/demo, Bedrock usage, logging, databases, networking, and load testing must fit inside the USD 300 limit.
- The USD 100 buffer is not available for convenience, experimentation, or avoidable idle resources.
- Any proposed use of the contingency buffer requires an explicit team decision recorded in an ADR or project-level decision record, including reason, expected spend, owner, and cleanup plan.
- If projected spend approaches USD 300, cost reduction takes priority over non-essential infrastructure/features.

---

# 2. Team, Technology Baseline, and Ownership Model

## 2.1 Team roster

The project has **8 developers**:

- Azeez
- Joseph
- Jiawen
- Hayyun
- Yonghuey
- Orvin
- Garvit
- Swayam

These names are the canonical owner names used in feature plans, ADRs, task tables, PRs, and resource ownership records.

## 2.2 Language/runtime baseline

- **Java 25 (LTS) is the default backend/runtime baseline for the project.**
- New Java code should target Java 25 unless an accepted ADR explicitly justifies an exception.
- Other languages/runtimes may be introduced **only as needed for a clear feature, tooling, infrastructure, frontend, testing, or integration purpose**.
- Adding a new runtime that becomes part of the deployed architecture or long-term maintenance surface requires an ADR covering rationale, ownership, security, operational impact, CI/CD impact, and cost.
- Pin/document exact versions in the appropriate build files, CI configuration, and runbooks so all developers and agents use the same toolchain.

## 2.3 Explicit task ownership and conflict prevention

Every feature plan MUST decompose the feature into tasks and assign each task to a named developer before parallel implementation begins. The goal is to make file/component ownership visible and prevent two people/agents from unknowingly changing the same area.

Each task entry must include at least:

- Task ID
- Description
- Assigned developer
- Expected files/components
- Dependencies/blockers
- Branch name
- Status
- Last sync/update timestamp

A task may have one primary owner and optional collaborators, but **only one primary owner should be responsible for a given implementation task at a time** unless the plan explicitly says otherwise.

If two tasks are expected to modify the same files, schema, API contract, shared infrastructure, or cross-cutting component, the conflict MUST be identified in the plan first and coordinated before coding.

## 2.4 Frequent Git synchronization rule

Humans and coding agents must synchronize frequently to minimize merge conflicts and stale assumptions. At minimum:

1. `git fetch` the remote before starting/resuming a task.
2. Check the current relevant shared `feat/<feature-name>` branch and recent changes before editing shared files.
3. Rebase/merge the latest shared `feat/<feature-name>` branch into the subtask branch regularly during active work, especially before touching shared contracts, schemas, infrastructure, or documentation.
4. Fetch/synchronize again before committing a substantial change and before opening/updating a PR.
5. Never assume chat context reflects the latest repository state; **the repository is the source of truth**. Agents must inspect/fetch current Git state before making decisions that could conflict with teammates.
6. Do not force-push shared branches (`dev`, `staging`, `main`, any shared `feat/<feature-name>` branch, or another developer's branch).
7. If upstream changes create ambiguity, stop, surface the conflict, update the feature plan/task ownership if necessary, then continue.

Recommended rhythm during active collaborative work: fetch at task start, after any teammate merge notification, before changing shared files, and before PR/merge.

For agentic coding sessions, `git fetch --prune` should be treated as a routine context-refresh step. If a session is long-running or another developer may have merged related work, fetch again before making further shared-file changes. The goal is to minimize stale-context conflicts, not to rely on a fixed timer.

---

# 3. Non-Negotiable Engineering Rules

These rules apply to every human contributor and every AI coding agent.

1. **No architectural or significant design decision may exist only in chat, code comments, or a team member's memory. It MUST be captured in an ADR.**
2. **ADRs are additive/append-only.** Existing ADRs are not deleted or silently rewritten to hide history.
3. If a previous decision is changed, create a new ADR that **supersedes** the earlier ADR while retaining the earlier record.
4. Every ADR must identify the **date/time** and the **human owner/decision-maker(s)**. AI assistants may draft an ADR, but a human team member must own/confirm the decision.
5. Every feature starts with a `<feature-name>_plan.md` before substantial implementation.
6. No feature is considered complete until its plan, code, tests, ADRs, docs, migrations, API changes and operational instructions are updated.
7. All branch promotion follows the agreed flow and CI/CD gates. PRs are mandatory for feature/code promotion into `dev` and for every promotion into `staging` or `main`; no direct `feat/...` push to an environment branch is allowed. Direct integration into an unprotected `task/*` or `feat/*` branch, plus the narrowly scoped project-wide Git/agent-governance exception for `dev`, is permitted only under Section 5.4.
8. Secrets must never be committed. Use AWS secret/configuration mechanisms and environment-specific configuration.
9. Never use production-like real personal/confidential banking data. Use synthetic/dummy data.
10. All API behavior must remain documented and backward compatibility considered when APIs change.
11. All major business actions must generate appropriate audit logs.
12. AWS resources must be tagged, cost-monitored, and removed/paused when not needed.
13. No coding agent may waive these rules because it believes a shortcut is faster.
14. Java 25 is the default backend runtime; other deployed runtimes require explicit justification and documentation.
15. Every implementation task must have a named primary developer in the relevant feature plan before parallel work begins.
16. Humans and agents must fetch/synchronize Git frequently and re-check upstream state before editing shared files or opening a PR.
17. Branch ownership is recorded in feature plans and, when used, PRs; developer names are not required in branch names. Use `task/<feature>/<subtask>` for subtask work and integrate only through `task/<feature>/<subtask> -> feat/<feature> -> dev -> staging -> main`.
18. Normal project spend MUST remain within USD 300; the reserved USD 100 buffer is protected contingency.

---

# 4. Repository Bootstrap Structure

Codex should initially create/normalize the repository around the following structure. Exact application folders may evolve through ADRs, but the governance files are mandatory.

```text
/
├── project_plan.md                  # This file: project-wide operating model
├── AGENTS.md                        # Canonical instructions for Codex/agentic coding tools
├── CLAUDE.md                        # Claude-specific entry point; must align with AGENTS.md
├── SKILLS.md                        # Approved/useful skills and skill discovery workflow
├── README.md                        # Human-friendly project overview and quick start
├── .gitignore
├── .env.example                    # Names only; NEVER real secret values
│
├── docs/
│   ├── architecture/
│   │   ├── system-overview.md
│   │   ├── diagrams/
│   │   └── data-flow.md
│   ├── adr/
│   │   ├── README.md
│   │   └── ADR-0001-....md
│   ├── feature-plans/
│   │   └── <feature-name>_plan.md
│   ├── api/
│   │   └── openapi.yaml
│   ├── database/
│   │   ├── schema.md
│   │   └── migrations.md
│   ├── testing/
│   │   ├── test-strategy.md
│   │   └── test-data.md
│   ├── runbooks/
│   │   ├── local-development.md
│   │   ├── deploy-development.md
│   │   ├── deploy-staging.md
│   │   ├── deploy-production.md
│   │   ├── backup-restore.md
│   │   └── rollback.md
│   ├── security/
│   │   └── security-model.md
│   ├── observability/
│   │   └── monitoring-alerting.md
│   ├── cost/
│   │   ├── budget.md
│   │   └── resource-register.md
│   └── ai/
│       ├── AI_USAGE_LOG.md
│       └── representative-prompts.md
│
├── frontend/                        # Presentation layer
├── backend/                         # Domain/application layer (exact modularization decided by ADR)
├── external-transaction-server/     # Dummy external transaction provider for Feature 3
├── infrastructure/                  # Terraform/CloudFormation/CDK; choice locked by ADR
├── scripts/                         # Repeatable local/dev/CI helper scripts
├── tests/                           # Cross-service/integration/e2e tests where appropriate
└── .github/ or equivalent-ci/       # CI/CD configuration used by the repo
```

## Repository rule

Do **not** let the folder structure imply an architecture decision that has not yet been made. For example, whether `backend/` becomes a modular monolith or multiple microservices is an architectural decision and therefore requires an ADR.

---

# 5. Branching Model

The repository uses **three long-lived environment branches** and hierarchical short-lived feature branches:

```text
main (production/release)
└── staging
    └── dev
        ├── feat/base-features
        │   ├── task/base-features/oauth
        │   ├── task/base-features/client-management
        │   └── task/base-features/transactions
        │
        └── feat/fraud-detection
            ├── task/fraud-detection/testing
            ├── task/fraud-detection/model-integration
            └── task/fraud-detection/ui
```

Git branch names containing `/` are only a **naming convention**; Git does not create real nested branches. Git also rejects a branch whose full name is the directory-prefix of another branch, so `feat/base-features` and `feat/base-features/oauth` cannot coexist. The separate `feat/...` and `task/...` namespaces avoid that ref-name collision while preserving the intended ownership and merge direction.

The core rule is:

```text
task/<feature>/<subtask>
        ↓
feat/<feature>
        ↓
dev
        ↓
staging
        ↓
main
```

A subtask branch MUST NOT skip its parent feature branch. Likewise, a feature branch MUST NOT skip `dev` and merge directly into `staging` or `main`.

## 5.1 Long-lived branches

### `main` (production/release)

- Represents the current release/demo-ready production state.
- Protected branch.
- No direct pushes.
- Receives reviewed promotions only from `staging`.
- Production deployment occurs only after all release gates pass.

### `staging`

- Production-like pre-release environment.
- Used for end-to-end validation, integration testing, migration verification, security checks, smoke/load testing and demo rehearsal.
- Protected branch.
- Receives changes only from `dev` through PR/promotion.

### `dev`

- Main integration branch for completed features.
- Shared feature branches merge here only after feature-level CI and review pass.
- The only direct-commit exception is qualifying project-wide Git-workflow or coding-agent instruction maintenance under Section 5.4.
- Development environment/database must be isolated from staging and production.
- Cross-feature integration issues are resolved here before promotion.

## 5.2 Shared feature branches

Format:

```text
feat/<feature-name>
```

Examples:

```text
feat/base-features
feat/fraud-detection
feat/client-insights
```

A shared feature branch represents the complete integration area for one feature or major workstream. It is the parent branch for all subtasks belonging to that feature.

Each shared feature branch MUST have a corresponding plan:

```text
docs/feature-plans/<feature-name>_plan.md
```

The plan assigns each subtask to a named developer from the 8-person team. **Developer ownership is tracked in the feature plan/PR, not encoded into the branch name.** This keeps branch names focused on the product/domain work rather than the person performing it.

## 5.3 Feature subtask branches

Format:

```text
task/<feature-name>/<subtask>
```

Examples:

```text
task/base-features/oauth
task/base-features/account-crud
task/fraud-detection/testing
task/fraud-detection/model-integration
```

These are the actual working branches used by individual developers/agents for isolated pieces of a larger feature.

Rules:

- A subtask branch is created from its parent `feat/<feature-name>` branch.
- The relevant `<feature-name>_plan.md` MUST state the developer who owns that subtask before implementation starts.
- The plan should also record expected files/components and conflict zones so two developers do not unknowingly edit the same area.
- The subtask branch merges **back into its parent feature branch**, not directly into `dev`.
- Example:

```text
task/base-features/oauth
        ↓ PR + CI + review (default), or warned user-authorized direct integration
feat/base-features
```

- When all required subtasks are integrated and the feature-level acceptance criteria/tests pass, the parent feature branch merges into `dev`:

```text
feat/base-features
        ↓ PR + feature-level CI + review
dev
```

- After integration testing in `dev`, promotion continues:

```text
dev -> staging -> main
```

- Feature/subtask branches should be deleted after successful merge unless there is a concrete reason to retain them.
- No direct pushes to `dev`, `staging`, or `main`; use reviewed PRs, except for the narrow Git/agent-governance-to-`dev` rule in Section 5.4.
- Do not force-push shared `feat/<feature-name>` branches.
- Branch names identify the workstream/subtask, **not the developer**. Developer ownership is recorded in the corresponding feature plan and PR.
- Example: `task/base-features/oauth` may be owned by Azeez in `base-features_plan.md`; the developer name does not need to appear in the branch name.

## 5.4 PR confirmation and user-directed push policy

Pull requests are the default integration method because they preserve review discussion, make CI status visible, and provide a clear audit trail. The following policy applies when a human or coding agent is asked to push or integrate work:

1. A routine push that publishes or updates the **current working branch** does not require a separate question about opening a PR.
2. When integrating work into a **different unprotected `task/*` or `feat/*` branch**, ask whether a PR is required if the user's requested integration method is unspecified.
3. If the user explicitly requests a direct push to a named unprotected `task/*` or `feat/*` branch, first warn them that the action may skip branch-level review, PR discussion, and CI gates that run only on PRs. State the exact destination branch.
4. Before a user-authorized direct integration, fetch current remote refs, verify the source and destination ancestry, confirm the destination is not protected, inspect ownership and expected files, run the relevant tests/checks, and ensure the push is a non-forced fast-forward that will not overwrite another contributor's work.
5. Record the user's choice, destination branch, checks run, and any safeguards skipped in the completion report. A direct push does not waive feature-plan, ADR, testing, documentation, ownership, or branch-path requirements.
6. Azeez provides standing authorization for a direct commit/push to `dev` only when the entire change is project-wide Git-workflow or coding-agent instruction maintenance. A qualifying commit may change only `AGENTS.md`, `CLAUDE.md`, `SKILLS.md`, Git/agent-governance sections of `project_plan.md`, `docs/adr/README.md`, and the ADR that records that same governance decision. It must not include application code, CI workflows, infrastructure, deployment configuration, permissions, secrets, dependency changes, or unrelated documentation.
7. Before using this `dev` exception, warn that direct commit will skip PR discussion/review and possibly PR-only CI; fetch `origin/dev`; verify the qualifying file/content scope; run applicable documentation checks; confirm the push is a non-forced fast-forward; and report the commit, checks, and skipped safeguards. Azeez's standing authorization means no additional PR/direct-push question is required after the warning.
8. If a proposed `dev` commit mixes qualifying governance maintenance with any other change, split it or use the normal task-to-feature-to-`dev` PR path. Repository branch protection remains authoritative; do not bypass it if it rejects the push.
9. Except for this narrow `dev` exception, PRs remain mandatory for every integration or promotion into `dev`, `staging`, or `main`. Every promotion into `staging` or `main` requires a PR. Never bypass repository protection or required CI/review gates.
10. Force pushes to shared branches and pushes that overwrite others' commits remain prohibited.

This policy is established by `docs/adr/ADR-0001-user-directed-push-policy.md` and amended by `docs/adr/ADR-0002-direct-dev-governance-updates.md` and `docs/adr/ADR-0003-maintain-one-line-adr-index.md`. It applies to humans and coding agents.

## 5.5 Synchronization rule for parallel development

Before working on a subtask, and repeatedly while work is active, humans and agents MUST synchronize against the current repository state.

Typical flow:

```bash
git fetch --prune
git switch task/base-features/oauth
git rebase origin/feat/base-features
# work / test / commit
git fetch --prune
git rebase origin/feat/base-features
# push, then open/update a PR into feat/base-features by default
```

A merge-based sync strategy may be used instead of rebase if the team standardizes on it. The key requirement is **frequent synchronization**, not a specific history style.

Fetch/synchronize at minimum:

1. At the start of every work session.
2. Before editing a shared API contract, schema, infrastructure definition, dependency/build file, or cross-cutting component.
3. After a teammate merges related work into the parent feature branch.
4. Before opening or updating a PR.
5. Immediately before final merge.

Agents must not rely on stale chat context. They must inspect the latest branch state and the feature plan before editing. If ownership or upstream changes conflict with the planned task, stop and surface the conflict instead of silently overwriting another developer's work.

## 5.6 Repository branch migration and compatibility aliases

The repository's initial branches predate this operating model. Migrate them without rewriting or discarding commit history:

- Use the existing protected `main` branch as the production/release branch; `staging` is the pre-release branch.
- Create `dev` from the accepted shared foundation commit.
- Create each `feat/<feature>` integration branch from `dev` or its accepted feature baseline.
- Create replacement `task/<feature>/<subtask>` names at the exact commit IDs of existing work branches before changing any old name.
- Preserve an archive ref for any older integrated line that is not a clean ancestor of the new feature branch.
- Keep old branch names temporarily as compatibility aliases while contributors update local tracking branches and open PR targets.
- Delete an old remote name only after its replacement SHA is verified, its open PRs are retargeted, and the owning developer confirms the migration. Never force-push as part of the rename.
- The old `prod` ref is a legacy duplicate of `main`, not a promotion target. Keep it untouched during the no-deletion transition; retire it only after confirming no PR, automation, local tracking branch, or deployment workflow depends on it and the team explicitly approves removal. Do not create new work or PRs against `prod`.

Recommended verification for every renamed branch:

```bash
git rev-parse origin/<old-name>
git rev-parse origin/<new-name>
# The two full commit IDs must match before the old name is retired.
```

---

# 6. End-to-End Development Flow

For every feature, use the following workflow.

## Step 1 — Define the feature

Create:

```text
docs/feature-plans/<feature-name>_plan.md
```

The feature plan must include:

- Problem statement
- User/business value
- Scope
- Explicit non-scope
- Acceptance criteria
- Functional requirements
- Relevant non-functional requirements
- Proposed APIs/events/jobs
- Proposed data model/schema changes
- Authorization/access rules
- Audit logging requirements
- Error/failure behavior
- Observability requirements
- Cost impact
- Security/privacy impact
- Test plan
- Deployment/migration plan
- Rollback plan
- Open questions
- ADRs required before implementation
- Task breakdown with named developer owners/status
- Expected files/components per task
- Feature subtask branch per task
- Dependencies/conflict zones between developers
- Last synchronization/update timestamp per active task

Each feature plan should contain a task table similar to:

```md
| Task ID | Task | Primary Dev | Collaborators | Files/Components | Branch | Dependencies/Conflict Zones | Status | Last Sync |
|---|---|---|---|---|---|---|---|---|
| F2-T01 | Client validation API | Azeez | Joseph | backend/... | feat/base-features/client-validation | Shared OpenAPI/schema | Planned | YYYY-MM-DD HH:MM |
```

No substantial implementation should start until the feature is sufficiently planned for the team to understand what is being built **and ownership is explicit enough to avoid duplicate/conflicting work**.

## Step 2 — Identify decisions that require ADRs

Before or during implementation, create ADRs for any decision that meaningfully affects architecture, contracts, operations, security, data, cloud resources, cost, deployment or cross-feature behavior.

Examples:

- Modular monolith vs microservices
- Frontend framework
- API Gateway pattern
- REST API boundaries
- OAuth/OIDC provider and token validation approach
- MFA mechanism
- Database technology and topology
- RDS vs Aurora/serverless choice
- Cache usage
- Transaction ingestion format and mechanism
- Queue/event usage
- IaC tool
- CI/CD platform and deployment strategy
- AWS account/environment separation
- Secrets management
- Audit log storage/tamper-resistance
- Backup/restore design
- Observability stack
- AI model/Bedrock usage for Feature 4
- Cost-control architecture
- Legal-entity/data-segregation approach

## Step 3 — Assign ownership, synchronize, then implement on feature subtask branches

Before implementation begins, the feature plan must assign the task to one of the 8 named developers and identify expected files/components to reduce overlapping work.

A contributor creates a `task/<feature-name>/<subtask>` branch from the relevant parent feature branch `feat/<feature-name>`. Before editing code, the contributor/agent must `git fetch`, inspect the latest feature branch, and synchronize the feature subtask branch with it.

Before coding, the contributor/agent must read:

1. `project_plan.md`
2. `AGENTS.md`
3. `CLAUDE.md` if using Claude
4. `SKILLS.md`
5. The relevant `<feature-name>_plan.md`
6. All ADRs relevant to the feature
7. Relevant runbooks/API/schema docs

## Step 4 — Update ADRs while building

Architectural decisions are logged **when the decision is made**, not reconstructed at the end of the project.

If implementation reveals a new architectural/design choice, stop and add an ADR before the choice becomes hidden in code.

## Step 5 — Test locally and in the feature environment

At minimum, apply relevant:

- Static checks/linting
- Unit tests
- API/contract tests
- Integration tests
- Database migration tests
- Authorization tests
- Audit log tests
- Failure-path tests
- E2E tests where applicable
- Security scanning/checks
- Cost-impact review for new AWS resources

## Step 6 — Integrate the feature subtask branch into its parent feature branch

Example merge path:

```text
task/base-features/oauth
        ↓
feat/base-features
```

Requirements:

- Feature plan updated
- Required ADRs present
- Tests passing
- Review completed
- Documentation updated
- No secrets/PII committed
- Use a PR by default. If the user explicitly chooses direct integration into the unprotected parent feature branch, follow Section 5.4, including the warning, remote/ancestry checks, non-forced fast-forward requirement, and completion report.

## Step 7 — Merge the completed feature branch into `dev`

PR:

```text
feat/<feature-name> -> dev
```

`dev` gate should validate:

- Full feature test suite
- Cross-feature integration
- Database migration compatibility
- OpenAPI/API compatibility
- Audit logging
- Development environment deployment
- Smoke test
- Infrastructure plan/change review

## Step 8 — Promote `dev` to `staging`

Staging gate should validate:

- Deploy to production-like staging
- End-to-end workflows
- External transaction server integration
- IAM/security configuration
- Backup/restore readiness where applicable
- Load/performance checks for critical paths
- Availability/failure checks where practical
- Logging/metrics/alerts
- Migration + rollback rehearsal
- Cost review before production promotion
- Demo rehearsal

## Step 9 — Promote `staging` to `main`

Production gate should require:

- All CI/CD checks pass
- Team approval
- No unresolved critical defects
- Release notes
- Confirmed rollback procedure
- Confirmed migration plan
- Confirmed monitoring/alerts
- Budget/cost check
- No unexpected AWS resources

After deployment:

- Run production smoke checks
- Confirm logs/metrics
- Confirm costs/resources
- Record release/version

---

# 7. ADR Policy — Mandatory and Append-Only

## 7.1 Purpose

Architectural Decision Records preserve the reasoning and context behind important project choices so that other team members and AI agents do not have to infer why the system looks the way it does.

**This is non-negotiable. Every meaningful architectural/design decision must be written down.**

## 7.2 Location and naming

```text
docs/adr/ADR-<4-digit-number>-<short-title>.md
```

Example:

```text
docs/adr/ADR-0007-use-aurora-postgresql.md
```

`docs/adr/README.md` is the mandatory ADR index. It lists each ADR as a link followed by a single-sentence summary so humans and agents can identify relevant records without loading every ADR. The index is a routing aid only: an applicable ADR must still be read in full. Add or update exactly one index entry whenever an ADR is created, superseded, deprecated, or has its status changed; never remove historical decisions from the index.

## 7.3 Required ADR format

```md
# ADR-XXXX: <Decision Title>

- Status: Proposed | Accepted | Superseded | Deprecated
- Decision date/time: YYYY-MM-DD HH:MM <timezone>
- Decision owner(s): <human name(s)>
- Drafted/assisted by AI: Yes/No; <tool if applicable>
- Feature/branch: <feat/<feature-name> or task/<feature-name>/<subtask>>
- Related issue/PR: <link/id>
- Supersedes: <ADR id or N/A>
- Superseded by: <ADR id or N/A>

## Context
What problem or constraint requires a decision?

## Decision Drivers
- Security
- Cost
- Scalability
- Maintainability
- Course requirements
- Team capability
- Delivery time
- etc.

## Options Considered
### Option A
Pros / cons / estimated cost / risks.

### Option B
Pros / cons / estimated cost / risks.

## Decision
What was chosen?

## Rationale
Why was it chosen over the alternatives?

## Consequences
### Positive
...

### Negative / Trade-offs
...

## Security & Privacy Impact
...

## Cost Impact
...

## Operational Impact
Deployment, monitoring, backup, rollback, runbooks.

## Validation
How will we prove this decision works?

## References
Official AWS docs, course requirements, diagrams, benchmarks, etc.
```

## 7.4 ADR immutability rule

Once accepted and merged:

- Do not delete it.
- Do not rewrite history to make the original decision look different.
- Typo/format corrections may be made only if they do not alter decision meaning.
- A changed decision requires a new ADR with `Supersedes:`.
- The original ADR remains in the repository.

## 7.5 Merge gate

A PR that introduces an architectural/design choice without its required ADR is **not mergeable**.

---

# 8. AGENTS.md and CLAUDE.md

## 8.1 `AGENTS.md` — canonical agent operating instructions

`AGENTS.md` is the source of truth for coding-agent behavior. It should tell Codex and compatible agents how to enter the project safely and consistently.

It must include:

### Context loading order

Before making changes, an agent must read:

1. `project_plan.md`
2. Relevant feature plan
3. Relevant ADRs
4. `SKILLS.md`
5. API/schema/runbook docs relevant to the task

### Agent responsibilities

- Do not invent missing requirements; surface open questions.
- Before modifying code, fetch/inspect current Git state and confirm the task is assigned to the current developer in the relevant feature plan.
- Re-fetch/synchronize frequently so chat/agent context never silently drifts behind the repository.
- Respect task/file ownership recorded in the feature plan; if another developer owns the same area, coordinate instead of making a conflicting change.
- Do not create/alter architecture without an ADR.
- When a new decision is encountered, draft the ADR and request/record a human owner.
- Keep feature plan task/status sections updated.
- Add/update tests with code.
- Update docs when interfaces, setup, deployment, configuration or behavior changes.
- Preserve existing API compatibility unless an ADR/plan explicitly approves a breaking change.
- Never expose or commit secrets.
- Never use real client/customer data.
- Verify security, pricing, service limits and AWS-specific claims against official documentation before treating them as final.
- Prefer smallest safe change over unrelated refactors.
- Follow the PR confirmation and user-directed push policy in Section 5.4. Never bypass branch protection or required CI/CD controls.
- Help the user conserve their AI-tool allowance by following the usage-reminder policy below; do not silently spend substantial usage on avoidable retries, duplicate work, or unnecessary agent delegation.
- Include a concise completion report: changed files, tests run, ADRs added/updated, docs updated, remaining risks.

### Agent AI-usage allowance reminders

Coding agents MUST help users avoid unexpectedly exhausting their Codex/Claude/other AI allowance:

1. At the start of a substantial or potentially long-running task, remind the user that the task may consume meaningful AI usage and tell them to check the product's current usage/remaining-allowance indicator.
2. If the tool exposes reliable usage telemetry, report the consumed and remaining allowance at useful milestones and before starting an unusually expensive step, such as broad repository analysis, repeated test/debug loops, or parallel/sub-agent work.
3. If exact usage telemetry is unavailable, say so plainly and direct the user to the product's usage page or indicator. Never invent a token count, percentage remaining, reset time, or quota.
4. Warn the user before expanding the task in a way likely to materially increase usage. Obtain confirmation when that expansion is optional or outside the original request.
5. Conserve usage: inspect targeted files first, reuse verified context, avoid rerunning unchanged checks, avoid duplicate agents, stop failed retry loops, and summarize progress so a later session can resume efficiently.
6. In the completion report, briefly state whether usage telemetry was available and remind the user to check their remaining allowance before assigning the next large task.

These reminders are informational. They do not replace the product's official usage meter, and an agent must not claim that an estimate is an exact account balance.

### Agent completion checklist

Before declaring work complete:

- [ ] Feature plan reflects implementation status
- [ ] Task owner/branch/status/last-sync fields are current
- [ ] Latest upstream feature branch was fetched/synchronized before completion
- [ ] Required ADRs exist
- [ ] Tests added/updated and passing
- [ ] Docs updated
- [ ] API spec updated if required
- [ ] DB migration/docs updated if required
- [ ] Audit logging handled
- [ ] Security/privacy reviewed
- [ ] Cost impact reviewed
- [ ] User reminded to check remaining AI usage; exact figures reported only when reliable telemetry was available
- [ ] No secrets/real PII
- [ ] CI expected to pass

## 8.2 `CLAUDE.md` — Claude-specific entry point

`CLAUDE.md` must **not become a second conflicting rulebook**.

It should:

- Tell Claude to read `project_plan.md` and `AGENTS.md` first.
- Repeat only Claude-specific working conventions if needed.
- Point to `SKILLS.md` for project-approved/discovered skills.
- Require the same ADR, testing, documentation, security and branch rules.
- Require Claude to identify when it is proposing an architectural decision versus merely implementing an accepted ADR.

Recommended principle:

> `AGENTS.md` contains project rules; `CLAUDE.md` adapts Claude to those rules.

---

# 9. SKILLS.md

`SKILLS.md` is the project registry for reusable agent skills/workflows that help Codex/Claude perform recurring tasks consistently.

## 9.1 Required skill registry format

For every approved skill, record:

```md
## <Skill Name>
- Purpose:
- Useful for:
- Compatible agent(s): Codex | Claude | Both
- Source:
- Version/date reviewed:
- Why approved:
- Security considerations:
- Project constraints:
- Example trigger/tasks:
```

Possible categories include:

- AWS architecture review
- Terraform/IaC review
- IAM least-privilege review
- API/OpenAPI design
- Database migration safety
- Testing generation/review
- CI/CD review
- Threat modelling
- Cost review
- Accessibility/UI review
- ADR drafting/review
- Documentation/runbook generation

## 9.2 Finding new skills using `skills.sh`

When a new feature/task requires a capability that is not already covered:

1. Describe the task precisely (for example: `AWS API Gateway security`, `PostgreSQL migration testing`, `OpenAPI contract testing`, `Bedrock cost optimisation`).
2. Search **skills.sh** using task/technology keywords.
3. Review candidate skills for relevance, source, permissions, assumptions and security impact.
4. Do not adopt a skill simply because it exists. Confirm it matches the project stack and course constraints.
5. Follow the selected skill's current installation/usage instructions from its source rather than assuming a command.
6. Test it first in a feature subtask branch.
7. Add the approved skill to `SKILLS.md` with its source, purpose and review date.
8. If the skill changes architecture, AWS resources, security controls, data design or deployment behavior, create an ADR.
9. A skill can assist development but **cannot override `project_plan.md`, ADRs, security rules, budget controls or CI/CD gates**.

## 9.3 Skill discovery per feature

Each `<feature-name>_plan.md` should contain:

```md
## Agent Skills Needed
- Existing project skills:
- Skills to search for:
- Skills evaluated:
- Skills approved for this feature:
```

This keeps skill selection tied to the actual work instead of collecting unused tooling.

---

# 10. Documentation and Runbooks

The `docs/` directory must explain how to **run, test, deploy, verify, debug and recover** the system.

At minimum document:

- Local setup
- Required tool versions/prerequisites, including Java 25 as the default backend runtime
- Environment variables by *name* (never secret values)
- How to run frontend/backend locally
- How to run the dummy external transaction server
- How to seed synthetic data
- How to run unit/integration/e2e tests
- How to perform DB migrations
- How to deploy each environment
- How CI/CD works
- How to verify a deployment
- How to inspect logs/metrics
- How to rollback
- How to backup/restore
- Common failure modes and troubleshooting

A feature is not complete if only the author knows how to run it.

---

# 11. Core CRM Requirements

## Feature 1 — CRM User Management

### Roles

**Admin**

- Performs CRM user/account management.
- Root administrator cannot be deleted.
- Other admin users may be created/deleted/disabled according to implemented rules.
- Can view activities across agents where required by the project UI/access model.

**Agent**

- Creates/manages client profiles and accounts.
- Must only access the agent's own clients and related activities/data.

### Required operations/APIs

At minimum support:

- Create User
- Disable User
- Update User
- Authenticate User
- Reset Password

Example API family:

```text
POST /api/users
...
```

The exact REST contract must be documented in OpenAPI.

### Security

- OAuth 2.0-based authentication/authorization.
- Zero Trust approach: authenticate/authorize service requests and validate tokens.
- MFA.
- Secure password hashing/storage where passwords are managed by the application/provider.
- Least privilege authorization.

### Audit logging

Record relevant events including:

- Registration
- Login/logout
- Password change/reset
- User/account creation/update/deactivation
- Role change
- Actor + timestamp

---

# 12. Feature 2 — Client Profile and Bank Account Management

## 12.1 Client profile

Required operations:

```text
POST   /api/clients
POST   /api/clients/{clientId}/verify
PUT    /api/clients/{clientId}
GET    /api/clients/{clientId}
DELETE /api/clients/{clientId}
```

Core workflow:

1. Agent enters client information.
2. Server validates fields/business rules.
3. System assigns a unique Client ID.
4. System persists client data.
5. System creates audit record.

### Identity verification

The project describes a verification flow involving client identity details/documents. For implementation/demo/testing, use only **synthetic/dummy identification data**.

### Client fields

At minimum account for the supplied attributes:

- Client ID
- First Name
- Last Name
- Date of Birth
- Gender
- Email Address
- Phone Number
- Address
- City
- State
- Country
- Postal Code

Implement the validation/business rules defined by the project specification, including uniqueness and valid ranges/formats.

## 12.2 Bank accounts

Required operations include:

```text
POST   /api/accounts
DELETE /api/accounts/{accountId}
```

Account fields include:

- Account ID
- Client ID
- Account Type
- Account Status
- Opening Date
- Initial Deposit
- Currency
- Branch ID

### Data requirements

- Use a relational database for client/account data requiring ACID behavior.
- Enforce validation/business rules.
- Encrypt sensitive data at rest and in transit.
- Maintain audit trails for changes.
- For critical updates, retain appropriate before/after values in audit logs.

---

# 13. Feature 3 — Bank Account Transaction Management

The CRM does not act as the core banking system. The project requires a separate **dummy external server** that provides transaction information which the CRM receives/imports into its own Transaction table.

## Required work

1. Generate synthetic transaction data using the required transaction attributes.
2. Build/host an external transaction server.
3. Build CRM-side ingestion/retrieval processing.
4. Validate and persist accepted records.
5. Correctly handle bad/corrupted/unsupported input and connection failures.
6. Make transaction data available through the CRM UI/API as required.

### Transaction fields

- Transaction ID
- Client ID
- Type: Deposit / Withdrawal
- Amount
- Date
- Status: Completed / Pending / Failed

### Processing audit requirements

At minimum record:

- Processing start/end timestamps
- Processing status: Successful / Partially Successful / Failed
- Number processed / accepted / rejected
- Unparseable records + rejection reason
- Corrupted/invalid/unsupported formats
- External connection failures
- System exceptions/processing errors

The ingestion design (pull/push, batch/API/file, schedule, retries, idempotency, etc.) must be explicitly decided in the relevant feature plan/ADR rather than silently assumed.

---

# 14. Features 4, 5 and 6 — Innovation Features

**Status:** Feature 4 is confirmed as Dynamic Event-Driven Client Alerts. Features
5 and 6 remain unconfirmed. Do not prematurely hard-code architecture around the
unconfirmed features.

Canonical plans:

```text
docs/feature-plans/event-driven-alerts_plan.md
docs/feature-plans/innovation-b_plan.md
docs/feature-plans/innovation-c_plan.md
```

## Mandatory constraints

### Feature 4 — Innovation Feature A

- Confirmed implementation: Dynamic Event-Driven Client Alerts.
- Human feature owner: Azeez.
- Delivery owners: Azeez for Search/AI/Platform; Hayyun for CRM/Domain/Experience and final integration.
- Must integrate AI.
- Must solve a concrete CRM/banking/user/operational problem.
- Must justify why AI is appropriate and what business value it adds.

### Feature 5 — Innovation Feature B

- Must be clearly distinct from Feature 4.
- May use AI or non-AI technology.
- Must solve a different CRM problem/user need/operational challenge.

### Feature 6 — Innovation Feature C

- Must be clearly distinct from Features 4 and 5.
- May use AI or non-AI technology.
- Must solve a different CRM problem/user need/operational challenge.

### All three innovation features

- Must integrate into the overall CRM workflow.
- Must have their own plan, acceptance criteria, tests and ADRs.
- Major business actions/recommendations must be audit logged.
- Feature 4's AI design must include cost, privacy/security, model selection, failure behavior and explainability considerations.
- If Amazon Bedrock is used, its spend counts toward the course-wide USD 400 allocation **and must still fit inside the project's normal USD 300 hard spending limit unless the contingency buffer is explicitly approved**.

---

# 15. Audit Logging — Cross-Cutting Requirement

Audit logging is a first-class system capability, not an afterthought.

At minimum, audit records should make it possible to determine:

- **Who** performed the action
- **What** action was performed
- **When** it occurred
- **What data/entity** was affected
- Before/after values for critical updates where appropriate
- Processing result/error details for transaction ingestion

Audit logs must be:

- Access-controlled, with administrative access only where required
- Tamper-resistant by design
- Free of unnecessary sensitive/PII leakage
- Queryable enough to support demonstration and troubleshooting

Any cross-service audit design must be locked through an ADR.

---

# 16. Mandatory Non-Functional Requirements

The project must explicitly satisfy and demonstrate the following mandatory NFRs.

## 16.1 Scalability / Performance

- Application auto-scaling
- Database-layer scaling approach
- Load balancing
- Caching where justified
- Relevant performance/load tests

The exact AWS mechanisms must be chosen via ADR with cost implications included.

## 16.2 Availability

- Redundancy/failover design
- Multi-AZ where appropriate
- Backup and restore strategy
- Recovery procedures/runbook

## 16.3 Maintainability / Extensibility

- Modular design
- Clear domain/component boundaries
- APIs for integration
- Backward compatibility strategy for API changes
- Documentation and tests that make future features safe to add

---

# 17. Additional NFRs to Address Where Practical

The course specification also identifies:

- Consistency
- Resiliency (including graceful failure, retries/circuit breakers where appropriate)
- Usability
- Observability
- Security
- Durability
- Agility
- Architecture
- Data segregation by legal entity
- Automated testing

Important implementation rules include:

- All APIs should follow OpenAPI standards.
- PII must be encrypted or filtered from logs.
- Design should account for deletion obligations such as GDPR-style data destruction requests.
- CI/CD must be demonstrated.
- Application should be cloud-native.
- Presentation and domain responsibilities should be segregated/layered.
- Microservices/micro-frontends are optional, not automatic; use them only if an ADR justifies them.
- Data for different legal entities must not be mixed; for this project, document how the deployment/data boundary satisfies the chosen legal-entity scope.

---

# 18. Architecture Principles

The project should align with the course's digital principles:

- Cloud Native First
- Reuse existing components where appropriate
- Data-centric and client-focused
- Zero Trust security
- Open, modular and unbundled design
- Automated by design
- Multi-channel capable where relevant
- High availability
- Simple/reliable/accessibile UI/UX
- Ethical/explainable/sustainable design

These principles are **decision drivers**, not permission to over-engineer. The architecture must remain proportionate to the course scope and the **USD 300 project hard limit**, while preserving the separate USD 100 contingency buffer.

---

# 19. CI/CD Policy and Environment Separation

## 18.1 Minimum pipeline stages

A typical pipeline should include:

```text
validate
-> lint/static checks
-> unit tests
-> build
-> security/dependency checks
-> integration/contract tests
-> infrastructure validation/plan
-> deploy to target environment
-> smoke tests
-> post-deploy verification
```

The exact tools are an ADR.

## 18.2 Environment data

Use separate configuration/data boundaries for:

- `dev`
- `staging`
- `main` / production demo

Never run feature tests against the wrong database/environment.

Each environment must have:

- Explicit config
- Explicit AWS resource naming/tags
- Defined database endpoint/schema strategy
- Migration process
- Deployment/rollback process

## 18.3 Database migrations

Every schema change must:

- Be versioned
- Be testable in CI
- Be reviewed for backward compatibility
- Have rollback/forward-fix considerations
- Be applied first to lower environments

---

# 20. Testing Strategy

Maintain `docs/testing/test-strategy.md` and map tests to features/requirements.

Target layers:

1. **Unit tests** — domain logic, validators, helpers
2. **API/contract tests** — request/response schemas and authorization
3. **Integration tests** — DB, identity, external transaction server, AWS integrations where feasible
4. **E2E tests** — critical Admin/Agent workflows
5. **Security tests** — authorization boundaries, bad tokens, privilege checks, secret/PII handling
6. **Migration tests** — schema evolution
7. **Failure-path tests** — external server failure, malformed transaction data, retry behavior
8. **Performance/load tests** — critical flows/NFR evidence
9. **Backup/restore tests** — evidence that recovery instructions work

For every critical feature, test both the happy path and at least the important failure/authorization paths.

---

# 21. Security Baseline

At minimum:

- OAuth 2.0/OIDC-aligned authentication design
- MFA
- Least-privilege authorization
- Agent can access only own clients
- Admin visibility per requirements
- Encryption in transit
- Encryption at rest
- Secure secret storage
- No secret values in git
- Input validation
- Output/log PII filtering
- Dependency/security scanning
- Network/security-group rules that expose only required ports/services
- Audit logging
- Backup protection

Security decisions that affect system architecture require ADRs.

---

# 22. Observability

The system should provide sufficient evidence to understand application health and failures.

Define:

- Application logs
- Audit logs
- Metrics
- Dashboards
- Alerts
- Correlation/request IDs where useful
- Transaction-import processing metrics
- Deployment health checks

Do not log secret values or unnecessary PII.

---

# 23. AWS Cost and Resource Governance — KEY PROJECT RULE

> **COURSE CEILING: USD 400 TOTAL across Amazon Bedrock and all other AWS services.** Exceeding the course allocation can severely affect the project grade.
>
> **PROJECT HARD LIMIT: USD 300. RESERVED BUFFER: USD 100.** Normal project activity MUST stay at or below USD 300. The final USD 100 is emergency contingency only.

Cost must therefore be treated as an architecture constraint from day one. The team should design for the USD 300 limit, not for the USD 400 ceiling.

## 23.1 Required cost controls

Maintain:

```text
docs/cost/budget.md
docs/cost/resource-register.md
```

For every AWS resource, record:

- Resource/service
- Environment
- Owner
- Why it exists
- Expected usage pattern
- Estimated cost
- Auto-scaling/min/max settings where applicable
- Shutdown/cleanup procedure
- Whether it is required for demo only or ongoing

## 23.2 Budget alarms

Configure AWS budget/cost alerts early. Alerts should be tied to the **USD 300 operational limit**, not just the USD 400 course ceiling. Suggested alert bands should warn the team early enough to react (for example around 50%, 70%, 85%, 95%, and 100% of the USD 300 limit). A separate alert should exist if contingency usage ever begins.

## 23.3 Cost review in ADRs

Every ADR involving AWS resources must include a **Cost Impact** section.

## 23.4 Cost review in PRs

A PR that creates/changes cloud resources should answer:

- What new billable resources are created?
- What is the expected idle cost?
- What is the expected load-test/demo cost?
- Can the resource be stopped/deleted when not in use?
- Does this change threaten the USD 300 project hard limit or consume protected contingency?

## 23.5 Contingency-buffer approval

The reserved USD 100 may be used only when all of the following are true:

- The need is exceptional and cannot reasonably be met within the USD 300 plan.
- The team explicitly approves it.
- The decision is recorded in an ADR/project decision with owner, reason, expected amount, duration, and cleanup plan.
- Budget alarms/resource register are updated immediately.
- Non-essential resources are reviewed first for shutdown/removal.

## 23.6 Cleanup discipline

- Remove abandoned experiments.
- Do not leave subtask test stacks running indefinitely.
- Use tagging so resource ownership is obvious.
- Prefer reproducible IaC over manually forgotten resources.

---

# 24. AI Usage Governance

Maintain:

```text
docs/ai/AI_USAGE_LOG.md
docs/ai/representative-prompts.md
```

## Rules

- AI can assist with approved engineering/project tasks.
- Humans remain accountable for every generated design/code/configuration.
- Verify AWS security guidance, service limits and pricing against official AWS documentation.
- Do not provide AI tools with AWS credentials, keys, secrets, `.env` contents, real personal data or confidential data.
- Do not fabricate screenshots, logs, test results, deployment evidence or demo output.
- Do not depend on AI during any course component where AI use is prohibited.
- Track meaningful AI assistance so final acknowledgement is easy and accurate.
- Agents must follow Section 8.1's AI-usage allowance reminder policy so users receive timely warnings and do not unknowingly exhaust their available AI usage.

`AI_USAGE_LOG.md` should record at least:

```md
- Date
- Team member
- AI tool
- Task/purpose
- Files/components affected
- Whether output was modified/verified
- Key verification performed
- Prompt reference if it is a representative prompt candidate
```

---

# 25. Suggested Feature Ownership Breakdown

The project has 8 developers: **Azeez, Joseph, Jiawen, Hayyun, Yonghuey, Orvin, Garvit, and Swayam**.

The workstream table below is a planning template, but **actual task ownership must be recorded inside each feature plan before parallel implementation begins**. The feature plan is authoritative for who is doing what.

| Workstream | Primary artifacts |
|---|---|
| User/Auth | Feature 1 plan, auth ADRs, user APIs, auth tests |
| Client/Account | Feature 2 plan, schema/API ADRs, CRUD, validation, audit |
| Transaction Integration | Feature 3 plan, external server, ingestion ADRs, processing/audit tests |
| Innovation A | Feature 4 plan, AI ADR(s), cost/privacy/evaluation |
| Innovation B | Feature 5 plan, feature-specific ADRs/tests |
| Innovation C | Feature 6 plan, feature-specific ADRs/tests |
| Platform/IaC | Environment, networking, compute, DB, secrets, deployment ADRs |
| CI/CD | Pipelines, gates, migration/deployment checks |
| Observability/Security | logs, metrics, alarms, security validation |
| Documentation/Demo | runbooks, diagrams, test evidence, architecture defense |

---

# 26. Definition of Ready (DoR)

A feature is ready for implementation when:

- [ ] Feature plan exists
- [ ] Problem/value is clear
- [ ] Scope/non-scope is clear
- [ ] Acceptance criteria are clear
- [ ] API/data impacts are understood
- [ ] Required ADRs are identified
- [ ] Security/audit/cost impacts are considered
- [ ] Test strategy is defined
- [ ] Required dependencies/skills are identified
- [ ] Tasks are assigned to named developers
- [ ] Expected files/components and likely conflict zones are recorded
- [ ] Feature subtask branch names are recorded
- [ ] Team has synchronized/fetched latest relevant branches before implementation

---

# 27. Definition of Done (DoD)

A feature is done only when:

- [ ] Acceptance criteria satisfied
- [ ] Code reviewed
- [ ] Tests pass
- [ ] CI passes
- [ ] Required ADRs accepted and merged
- [ ] Feature plan updated to final state
- [ ] OpenAPI updated where applicable
- [ ] DB schema/migrations documented/tested where applicable
- [ ] Audit logging implemented and tested
- [ ] Security/access rules tested
- [ ] Observability added where needed
- [ ] Docs/runbooks updated
- [ ] Cost impact reviewed
- [ ] Deployed successfully to required environment
- [ ] Rollback/recovery path understood
- [ ] No secrets/real PII included
- [ ] Task ownership/status is updated in the feature plan
- [ ] Feature subtask branch was synchronized with latest feature branch before final PR/merge

---

# 28. Pull Request Template Expectations

Every PR should clearly state:

```md
## What changed

## Primary developer / task owner

## Source feature subtask branch

## Latest upstream sync

## Why

## Feature plan
- docs/feature-plans/...

## ADRs
- Added/updated:
- Not required because:

## Testing performed

## API/schema changes

## Security/privacy impact

## Audit logging impact

## AWS/cost impact

## Deployment/migration notes

## Rollback notes

## Documentation updated

## Checklist
- [ ] Tests pass
- [ ] ADR requirement satisfied
- [ ] Docs updated
- [ ] No secrets/PII
- [ ] Cost reviewed
```

---

# 29. Initial Project Execution Order

Codex should use this order when structuring the repo and creating the first implementation roadmap.

## Phase 0 — Governance/bootstrap

1. Add `project_plan.md`.
2. Create `AGENTS.md`.
3. Create `CLAUDE.md` referencing project rules.
4. Create `SKILLS.md`.
5. Create docs directories and ADR template.
6. Create feature-plan template.
7. Create PR template.
8. Create AI usage log template.
9. Create cost budget/resource register.
10. Configure branch protection rules/documentation for `main`, `staging`, `dev`, and the `task/<feature-name>/<subtask> -> feat/<feature-name> -> dev -> staging -> main` workflow.
11. Record the 8-person team roster and feature-plan ownership template.
12. Pin/document Java 25 as the default backend runtime baseline.
13. Configure cost governance around the USD 300 hard limit + USD 100 protected buffer.

## Phase 1 — Architecture baseline

Create ADRs for the minimum decisions required before feature implementation, such as:

- Repository/application structure
- Backend architecture style
- Frontend approach
- Authentication/identity approach
- Database choice/topology
- AWS compute/deployment approach
- Networking/environment isolation
- IaC choice
- CI/CD approach
- Audit logging architecture
- Observability baseline
- Cost-control baseline

Do **not** pre-decide these in chat and then code them without ADRs.

## Phase 2 — Core CRM vertical slices

Recommended implementation sequence:

1. Authentication + role model
2. User management
3. Client profile CRUD + validation + ownership authorization
4. Account management
5. Audit logging baseline integrated across Features 1/2
6. External transaction server
7. Transaction ingestion + transaction display/search
8. Harden NFRs and environment deployment

## Phase 3 — Innovation features

When confirmed, create a feature plan + required ADRs for each of Features 4/5/6 and implement them as separate vertical slices.

## Phase 4 — NFR validation and demo hardening

- Scalability/performance evidence
- Availability/failover/backup evidence
- Maintainability/extensibility evidence
- Automated testing evidence
- CI/CD demo
- Security verification
- Observability/dashboard evidence
- Cost review
- Runbook rehearsal
- Demo rehearsal

---

# 30. Instructions to Codex for Initial Repository Setup

When this file is first introduced to Codex, Codex should:

1. `git fetch` and inspect the current remote/local repository state before moving, deleting, or changing anything. Never assume the chat has the latest repository state.
2. Produce a short gap analysis against this `project_plan.md`.
3. Create missing governance/documentation files and templates first.
4. Do **not** make unapproved architectural changes just to match a preferred framework.
5. For any required structural decision, draft the corresponding ADR.
6. Preserve existing working code unless a planned/approved migration replaces it.
7. Create/normalize the branch workflow documentation, but do not fabricate remote branch protection if Codex cannot actually configure the hosting platform.
8. Establish the initial feature plan files for Features 1, 2 and 3, including task-owner tables using the canonical developer names: Azeez, Joseph, Jiawen, Hayyun, Yonghuey, Orvin, Garvit, and Swayam.
9. Create placeholder plans for Features 4, 5 and 6 marked `UNCONFIRMED`.
10. Create a clear TODO/roadmap showing what is blocked by pending ADRs/team decisions.
11. Run existing tests before and after repository restructuring where possible.
12. Confirm Java 25 is documented/pinned as the default backend baseline; do not introduce other deployed runtimes without a documented need/ADR.
13. Confirm cost controls target the USD 300 hard operational limit and preserve the USD 100 contingency buffer.
14. Before editing shared files, fetch/synchronize again if other developers may have merged changes during the session.
15. Finish with a report containing:
   - Files created/changed
   - ADRs drafted
   - Open decisions requiring human confirmation
   - Tests run/results
   - Risks
   - Recommended next task

---

# 31. One-Line Operating Principle

> **Plan the feature -> assign each subtask to a named developer -> create `task/<feature>/<subtask>` from `feat/<feature>` -> record every architectural/design decision in append-only ADRs -> implement and test -> merge subtask into its parent `feat/<feature>` -> merge the completed feature into `dev` -> promote `dev` to `staging` -> validate -> promote `staging` to `main` (production/release) -> keep docs, auditability, security, frequent Git synchronization, explicit developer ownership, and the USD 300 hard-limit / USD 100 contingency controls current throughout.**
