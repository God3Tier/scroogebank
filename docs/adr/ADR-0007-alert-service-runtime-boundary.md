# ADR-0007: Alert service and runtime boundary

- Status: Proposed
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None

## Context

The repository contains Java and Go services. The feature needs one clear owner,
runtime, build path, and deployment boundary without modifying the existing Go
client-profile service or introducing an unapproved deployed language.

## Decision

Create `services/event-alert-service` as a Java 25 Spring Boot Maven module in the
root reactor. One codebase and container image provide two explicit entry modes:
`api` for authenticated CRM operations and `worker` for bounded scheduled jobs.
They share domain code but cannot enable both modes accidentally in production.

The service owns alert-domain persistence and communicates through versioned
contracts. It does not read other services' tables. Provider adapters sit behind
interfaces; deterministic tests use fakes and require no AWS access.

## Consequences

The module fits existing CI and avoids another runtime, but the API and worker
share a release cadence. Independent services may be reconsidered only after
measured scaling or security evidence.

## Validation

The root Maven reactor builds the module on Java 25. Unit and contract tests run
without AWS; integration tests verify each mode starts with the other disabled.
