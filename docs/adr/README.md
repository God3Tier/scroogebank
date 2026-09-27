# Architecture Decision Record Index

Use this index to identify which ADRs are relevant before loading their full text. A one-line summary is a routing aid, not a replacement for an applicable ADR; humans and agents must read the complete record before making or reviewing a decision it governs.

## Accepted decisions

- [ADR-0001](ADR-0001-user-directed-push-policy.md) — PRs are the default, while warned and explicitly authorized direct integration is allowed only for unprotected task and feature branches.
- [ADR-0002](ADR-0002-direct-dev-governance-updates.md) — Azeez authorizes narrowly scoped Git-workflow and agent-instruction updates directly to `dev`, subject to file allowlisting, warning, synchronization, and non-forced-push checks.
- [ADR-0003](ADR-0003-maintain-one-line-adr-index.md) — Every ADR must have a one-line entry in the ADR index, which agents scan for relevance before reading applicable decisions in full.
- [ADR-0005](ADR-0005-cognito-security-across-features-1-and-2.md) — Features 1 and 2 share Cognito identity, with service-side JWT validation and Agent-owned client/account authorization.

## Superseded decisions

- [ADR-0004](ADR-0004-local-user-service-crud-profile.md) — Superseded by ADR-0005; records the historical local user-service profile that bypassed Cognito and authorization.

## Maintenance rule

Add or update exactly one concise entry whenever an ADR is added, superseded, or has its status changed. Keep historical entries and identify the superseding ADR rather than deleting an old decision from this index.
