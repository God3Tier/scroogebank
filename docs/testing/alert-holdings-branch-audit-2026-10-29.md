# Alert holdings integration: all-remote source audit

Verified: 2026-10-29 22:41 +08:00. Owner: Azeez. AI assistance: Codex.
Scope: source-code verification and fix planning, not deployed-database inspection.

## Result

Confirmed missing capability: maintained investment holdings linked to current
CRM accounts and agent assignments. This is consistent with the documented
synthetic MVP boundary, rather than a failure of client/account persistence.

`git fetch --all --prune` succeeded for the configured remote, origin. The
inventory contains **44 actual remote branches and one symbolic default-branch
alias** (`refs/remotes/origin/HEAD`); earlier conversation called all 45 refs
branches. Searches covered every actual branch tip, including older branches
without event-alert-service, and inspected distinct provider/schema variants.

No provider that reads current database holdings or CRM-owned investment
positions was found. Of the 44 actual branches, 28 contain alert holdings
providers; both implementations found are named
`SyntheticHoldingsProvider` and `SyntheticHoldingsSearchUniverseProvider`.
Their versions differ in selection/fan-out logic, but all use
`SyntheticFixtureLoader.loadDefault()`.

## Direct evidence

| Finding | Inspected source |
|---|---|
| Alert holdings come from a bundled resource, with no database/client-directory dependency in the loader | [loader on feature tip](https://github.com/cs301-itsa/project-2026-27t1-g3-t6/blob/dedb306b0a97c496ff0793cee021ee0fb903af53/services/event-alert-service/src/main/java/bank/alert/fixture/SyntheticFixtureLoader.java) |
| Both downstream holdings and universe selection use that loader | `services/event-alert-service/src/main/java/bank/alert/holdings/` on every branch containing providers; the local universe/retrieval work changes selection, not the source |
| Historical snapshots do not constitute a maintained portfolio book | [alert schema](https://github.com/cs301-itsa/project-2026-27t1-g3-t6/blob/dedb306b0a97c496ff0793cee021ee0fb903af53/services/event-alert-service/src/main/resources/db/migration/V1__event_alert_foundation.sql): `synthetic_portfolio`, `holding_snapshot`, and alert references |
| CRM accounts lack security ID, quantity, security price or position valuation | [current-dev account schema](https://github.com/cs301-itsa/project-2026-27t1-g3-t6/blob/08c832b069aa874fb8c505e117ecd10b0734ed5c/services/account-service/data/V1_init.sql) and `src/internal/models/accounts.go`; checked both distinct account-schema variants across branches |
| Existing internal account lookup is insufficient for worker routing | [lookup handler](https://github.com/cs301-itsa/project-2026-27t1-g3-t6/blob/08c832b069aa874fb8c505e117ecd10b0734ed5c/services/account-service/src/internal/controller/account/lookup_accounts.go): returns account ID, client ID and currency; no authoritative current client assignment |
| The CRM now supports client reassignment, but explicitly excludes fixture alert assignments | [ADR-0060](https://github.com/cs301-itsa/project-2026-27t1-g3-t6/blob/08c832b069aa874fb8c505e117ecd10b0734ed5c/docs/adr/ADR-0060-client-reassignment.md); account `owner_agent_id` follows reassignment, client source becomes `assigned_agent_id` |
| Dashboard portfolio naming is not a securities holdings feed | [dashboard service](https://github.com/cs301-itsa/project-2026-27t1-g3-t6/blob/08c832b069aa874fb8c505e117ecd10b0734ed5c/services/dashboard-service/src/main/java/bank/dashboard/service/DashboardService.java): `portfolio()` aggregates clients, accounts and accounts-per-client |
| Synthetic scope is intentional in the original architecture | [ADR-0005](../adr/ADR-0005-alert-holdings-and-valuation-boundary.md): explicitly says CRM has no securities/positions/valuation source |

File-tree searches included holding, portfolio, position and instrument names;
code/schema searches included provider implementations, `holding`,
`instrument_id`, `security_id`, position quantity and positions. Remaining
non-alert hits were not holdings sources (for example cryptographic chain
positions and client reassignment logic).

## Additional correctness requirements found while tracing the path

1. `SyntheticHoldingsProvider.read()` filters returned positions to requested
   affected instrument IDs. `AlertGenerationService.generate()` passes those
   positions to `ExposureCalculator`, whose denominator is the supplied sum.
   Thus a matched value 10 in a full 100 account can appear as 10/10. This is a
   source/contract defect to reproduce and fix; no runtime experiment is claimed
   in this audit.
2. Current authorization filters `alert.assigned_agent_subject`, which records
   creation assignment. Connecting current CRM ownership requires list/detail,
   decision and feedback authorization to follow reassignment too, not just the
   next worker's fan-out.
3. `synthetic_portfolio` has client reference as its primary key and unique
   account reference. Simply inserting CRM clients there cannot represent
   multiple accounts per client or safely turn snapshots into current state.
4. Detail exposure currently reconstructs membership from client/source/time.
   New database portfolios need explicit immutable snapshot groups, including
   the complete account denominator.

These findings are included in the [proposed fix plan](../feature-plans/event-driven-alerts-current-holdings_plan.md).
Existing synthetic tests and earlier live news samples do not prove automatic
CRM onboarding.

## Branch and verification status

- Feature tip: `dedb306`; current dev: `08c832b`.
- Existing retrieval work remains separately committed locally at `2347a8b`.
  It is not silently merged into this planning branch.
- Planning branch was created from the fetched feature tip:
  `task/event-driven-alerts/current-holdings-plan`.
- No application/schema/API implementation, merge, push, paid provider call,
  model call, AWS call or deployed-database query was performed for this audit.
- No application suite was rerun because the deliverable is documentation.
  Planned runnable integration/security tests are specified in the fix plan.
- Documentation verification passed: local links in every new document resolve,
  the pinned inventory has exactly 44 actual branch rows, and
  `git diff --check` reports no whitespace errors.
- Unrelated bootstrap tfvars-example edit and root untracked `plan.md` were
  preserved. No secret file was read for this task.

## Pinned remote branch inventory

All SHAs below were obtained after the successful fetch. The default HEAD alias
points to `origin/main` and is excluded from the branch count.

| Remote branch | Commit |
|---|---|
| `origin/dev` | `08c832b069aa874fb8c505e117ecd10b0734ed5c` |
| `origin/event-driven-alerts/tavily` | `d2a731d7cc95e68d2bd9ffe63417228229e34fe3` |
| `origin/feat/auditor` | `f05f6e825517c28bc9de024c08d3cf04e78185ed` |
| `origin/feat/base-features` | `d60f6a3dcb43409d09399b4988cf63a5fcc0f796` |
| `origin/feat/client-health-monitor` | `9ec76d7544966468b88d7674d18a613e55f839ff` |
| `origin/feat/client-profile-management-go` | `1fdf6378d87ac6aef360561b0c726da909f50fdb` |
| `origin/feat/event-driven-alerts` | `dedb306b0a97c496ff0793cee021ee0fb903af53` |
| `origin/feat/frontend` | `ede3b419d3336aa979402e2dc31d82461007ec49` |
| `origin/feat/git-workflow-policy` | `c45b478c46a454c2c9d964aaf8e0e77281306e31` |
| `origin/feat/governance` | `95bb8be865bee69525c7a26c7fad8d5d50566199` |
| `origin/feat/infrastructure` | `94570870c40c1bb2762b7eef903893b586f9047b` |
| `origin/main` | `6c9bf12f374941aa856d2578535ef45048780c23` |
| `origin/prod` | `716b2e64c07aa540424f79b7b6e0e251c017fa11` |
| `origin/staging` | `bb52e70f956f81ec9c03831156c8d767d705edf8` |
| `origin/task/base-features/auditor` | `059bfa4721b090cf5927cf421f8cb255e0d60f1a` |
| `origin/task/base-features/auditor-revert` | `e0c7d79d5da32e26d2c4d9af955b03cdbbdcb99c` |
| `origin/task/business-dashboard/frontend-mockup` | `fc811f52919739f2e55c4d6d7b1411a8867ccf69` |
| `origin/task/client-health-monitor/activity-projection` | `2d89d6b5c8fa889bc98b972ae7c3aba3d3a0abb6` |
| `origin/task/client-health-monitor/analysis-orchestration` | `73f48cfbfe72e99ce7fe7d8cf2dd739610c7fdf6` |
| `origin/task/client-health-monitor/audit-outbox` | `5f45b632c3b5df03f0dd9afd89af194c63b884ea` |
| `origin/task/client-health-monitor/durable-metrics` | `8b609ee4c5d8f504ca93fcf83a15d38cdf2f422b` |
| `origin/task/client-health-monitor/explanations` | `5230bbfdbcbbf9d9c8ec56e9b48da36aaa4d6779` |
| `origin/task/client-health-monitor/integration-readiness` | `4c8b97d63f35f1f8224f8cadcf681b2334cb54ce` |
| `origin/task/client-health-monitor/metrics-health` | `2f5be672787cdaf92bb37e12ae7f5cada42611ae` |
| `origin/task/client-health-monitor/mock-ui` | `ede3b419d3336aa979402e2dc31d82461007ec49` |
| `origin/task/client-health-monitor/runtime-packaging` | `edc637dce090d5a5a77abaec83b965f55bb4826c` |
| `origin/task/client-health-monitor/sf00-foundation` | `82be33cc34030521de34f9efc5775901bcd0c26c` |
| `origin/task/client-health-monitor/signal-rules` | `271943951c799320ef7b3fa62fd53b9703213b1e` |
| `origin/task/client-health-monitor/sync-dev` | `6f606ec1c24f096eedc51b78cd70f65fb386985a` |
| `origin/task/client-health-monitor/task-api` | `806032aa8041a4162360cc16e69689bb472c3619` |
| `origin/task/client-health-monitor/task-domain` | `89d9890ea965ad295e958d36c6d2506c4936aa87` |
| `origin/task/client-health-monitor/task-persistence` | `829e1a7ed0b6eef464d3fcafd25c8db9a8c18282` |
| `origin/task/client-health-monitor/transaction-intake` | `eea7d52e3ee30f539253f0f5a05671cc8eebd0f4` |
| `origin/task/event-driven-alerts/search-infra` | `19cb3d6e1ac48e55f12188fc46c91dafe26da68e` |
| `origin/task/event-driven-alerts/search-universe-thresholds` | `70c494dd47959c4ee96c00c21233b02b3fdd0d52` |
| `origin/task/event-driven-alerts/testing-tavily` | `2de36434c5ba159beb30c340c34de0270fece37a` |
| `origin/task/frontend-building` | `851f737970cba14475dbaee8e11c7b4ec0e3bd1e` |
| `origin/task/git-workflow-policy/user-directed-push` | `bf74b24607e5b9de122c1f1504e68f7d7afeba7d` |
| `origin/task/governance/main-release` | `545dc5a5c05c5cc7cd79ee819bb5b817b7554620` |
| `origin/task/governance/repository-structure` | `13701f772403dc0e54714573d2da755904802dae` |
| `origin/task/historical_transaction` | `77d564a08ac83cf12e60e79242e4e78570da9930` |
| `origin/task/infrastructure/ci-security-scans` | `c8ee25e25387f76f279885b635c9e768cdcef568` |
| `origin/task/infrastructure/terraform` | `b3139e96b159f3aa7255d52b21593942e28ba29a` |
| `origin/transaction-integration` | `e215ca60ef426bc9ce5b3a8380e1fc8474b2025d` |
