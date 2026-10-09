# ADR-0026: Data segregation: one stack per legal entity

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

NFR 12 (Data Segregation) says each country or legal entity has its own data protection policy, data must never be shared across legal entities, and each application instance is hosted separately per legal entity. [`nfr-notes.md`](../architecture/nfr-notes.md) assumes a single legal entity and does not build segregation, noting that a per-entity stack would be the natural extension. The project demo deploys only the Singapore entity.

## Decision Drivers

- Compliance with NFR 12
- Strong isolation of personal data between entities
- Low build effort for the project (only one entity is deployed)

## Options Considered

### Option A: single tenant, no segregation (the `nfr-notes.md` stance)

- Pros: nothing to build.
- Cons: does not meet NFR 12; adding segregation later means retrofitting tenant boundaries.

### Option B: multi-tenant in one stack (tenant ID columns, row-level security, per-tenant schemas)

- Pros: one deployment.
- Cons: segregation depends on every query and policy being right; a single bug can leak data across entities; shared databases, keys and queues.

### Option C: one full stack per legal entity (chosen)

- Pros: physical separation of networks, databases, identity pools, keys and buckets; no code changes per entity; no cross-entity data path exists.
- Cons: per-entity infrastructure cost; per-entity operations.

## Decision

Deploy **one Terraform stack per legal entity**, selected by a variable (`legal_entity = "sg"`). Each stack has its own VPC, RDS databases, DynamoDB client-keys table, Cognito user pool, KMS key, SQS queues and S3 buckets, all tagged `LegalEntity`. No data is shared between stacks. The project deploys only the Singapore entity (`sg`) in ap-southeast-1.

## Rationale

Separate stacks make "data never shared across legal entities" a property of the infrastructure rather than of application code, and need no application changes. Because only one entity is deployed for the project, the cost is the same as Option A today.

## Consequences

### Positive

- Meets NFR 12 by construction.
- Each entity can follow its own data protection policy, region and retention settings.

### Negative / Trade-offs

- Each additional entity costs roughly another full stack.
- Bank-wide reporting across entities would need an explicit, policy-approved export; nothing does that today.
- Terraform must be written so the whole stack is parameterised by entity from the start.

## Security & Privacy Impact

No shared database, key, queue or identity pool between entities, so a compromise or bug in one entity's stack cannot expose another's data.

## Cost Impact

None for the project (one entity). Each further entity adds roughly the cost in [environments and cost](../cost/budget.md).

## Operational Impact

Terraform workspaces or variable files per entity; deployments and alarms per entity; resource tags for cost allocation.

## Validation

A second stack (for example a short-lived `test` entity in staging) deploys from the same code with no shared resources, verified by listing resources by tag.

## References

- [NFR mapping: NFR 12](../architecture/nfr-mapping.md)
- [How to read the diagram](../architecture/system-overview.md#how-to-read-the-diagram) (the Region box is the `sg` stack)
- [`nfr-notes.md`](../architecture/nfr-notes.md): "NFR 12 (Data Segregation): single-tenant assumption, not built" (differs from this ADR)
