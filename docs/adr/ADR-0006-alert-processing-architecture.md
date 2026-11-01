# ADR-0006: Alert processing architecture

- Status: Accepted (2026-11-01; accepted for the team by Neo Jia Wen)
- Decision date/time: 2026-09-27 01:54:06 +08:00 (Asia/Singapore)
- Human owner/decision-maker: Azeez
- Required reviewer: Hayyun
- AI-assisted draft: Yes — Codex
- Related branch: `task/event-driven-alerts/contracts`
- Supersedes: None

## Context

The NFR notes say no new broker while the existing auditor uses Kafka. The MVP
needs bounded scheduled discovery, idempotent replay, and audit delivery without
adding another messaging platform.

## Decision

EventBridge Scheduler in Singapore starts a bounded ECS task every four hours.
One run performs grouped public searches, persists minimal results, applies
deterministic pre-filters, and hands accepted events to the alert service within
the same deployment boundary. PostgreSQL watermarks, unique keys, and leases make
the workflow idempotent. No alert-specific SQS, SNS, MSK, or Kafka topic is added.

The service uses a transactional outbox only for the existing auditor integration.
Workers claim rows with bounded leases and retry counts; an expired lease is safe
to reclaim. Replays use the original rule/model versions and never resend an
already acknowledged notification or duplicate an alert.

## Consequences

The design is cheaper and simpler than a new broker but provides less independent
scaling between stages. Backpressure is database-backed and each run has hard
limits on searches, articles, model calls, retries, concurrency, and duration.

## Validation

Tests cover duplicate delivery, overlapping schedules, worker loss, expired
leases, partial batches, provider outage, and replay. A load test must demonstrate
backlog recovery without duplicate alerts.
