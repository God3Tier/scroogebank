# Coding agent entry point

Read [project_plan.md](project_plan.md) before planning, coding, reviewing, or merging work. It is the project-wide operating model; this file is the entry point that directs coding agents to it. If project instructions conflict with the official CS301 specification or AI Usage Policy, follow the official course documents.

For each task:

1. Inspect `git status`, the current branch, and relevant recent commits. Fetch current remote refs when access is available; if it is unavailable, say that the branch and merge assessment is based on cached refs.
2. Read the relevant plan in `docs/feature-plans/`, accepted ADRs in `docs/adr/`, and applicable API, schema, and runbook documentation. Check task ownership and expected files before changing shared code. If a required plan or decision record is missing, surface the gap and follow `project_plan.md` before substantial implementation.
3. Follow the branch path in `project_plan.md`: `task/<feature>/<subtask>` → `feat/<feature>` → `dev` → `staging` → `main`. Check the actual ancestry and CI/review gates before recommending or performing a merge. PRs are the default integration method. If the user names an unprotected `task/*` or `feat/*` destination but does not specify whether to use a PR, ask them. If they explicitly choose a direct push, warn which review/CI safeguards may be skipped, re-check the remote branch, and proceed only when the push is non-forced and does not overwrite others' work. Never push directly to `dev`, `staging`, or `main`, bypass a protected branch, or force-push a shared branch.
4. Record significant design decisions in an ADR with a human owner. Keep the feature plan, tests, API/schema docs, and runbooks in step with code changes. Use synthetic data and keep secrets out of the repository and AI prompts.
5. Report changes, tests, merge status, and any limits caused by unavailable remote access. Follow the AI-usage reminder policy in `project_plan.md` §8.1 for substantial work.

The repository may still be missing some files named in `project_plan.md`. Do not assume a file exists merely because the plan describes its intended location.
