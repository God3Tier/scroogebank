# ADR-0018: ECS Fargate as the compute platform

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

The CRM runs eight containerised services ([ADR-0017](ADR-0017-microservices-per-business-area.md)) plus scheduled tasks (chain-verify, the alert worker, the health analysis task) and one-off migration tasks. The spec asks for a cloud-native, containerised and orchestrated design (NFR 11), auto scaling (NFR 1) and multi-AZ availability (NFR 2). Kubernetes is mentioned in the spec as an example, not a requirement. The team must be able to explain and operate the whole platform, and spend must stay under USD 300.

## Decision Drivers

- Cost (no fixed control-plane charge)
- Team capability and delivery time
- Scalability and availability (NFR 1, NFR 2)
- Cloud-native orchestration (NFR 11)

## Options Considered

### Option A: Amazon EKS

- Pros: Kubernetes is the industry standard and portable; rich ecosystem.
- Cons: about USD 73/month for the control plane alone, before any worker nodes; node groups, upgrades and add-ons to manage; a steep learning curve for the course timeline.

### Option B: ECS on EC2

- Pros: no control-plane charge; can use cheap instances.
- Cons: the team manages, patches and scales the EC2 hosts and their capacity.

### Option C: ECS on Fargate (chosen)

- Pros: no control-plane charge; no servers to manage; per-task billing; works directly with the ALB, Service Connect, EventBridge Scheduler and target-tracking auto scaling.
- Cons: higher per-vCPU price than EC2; less control over the host.

## Decision

Run every service on **Amazon ECS with the Fargate launch type**, at least two tasks per service across two Availability Zones, with target-tracking auto scaling on CPU and ALB requests per target (minimum 2, maximum 6 tasks). The auditor scales on SQS backlog. Scheduled work (chain-verify, alert worker, health analysis) runs as standalone ECS tasks started by EventBridge Scheduler, and each service's migrations run as a one-off ECS task. Staging may use Fargate Spot.

## Rationale

- EKS's control plane alone would use about a quarter of the monthly budget.
- Fargate removes node management, so the team can explain and operate the platform within the timeline.
- ECS meets the spec's containerisation and orchestration principle.

## Consequences

### Positive

- No hosts to patch; scaling is configuration.
- Native integration with Service Connect for service-to-service calls and with EventBridge Scheduler for scheduled tasks.
- Services scale in to their minimum, and the demo environment can scale to 0 outside working hours.

### Negative / Trade-offs

- Less portable than Kubernetes.
- Standalone scheduled tasks cannot use Service Connect, so they call services through the internal ALB with a service token.
- Fargate costs more per vCPU than EC2 when running continuously.

## Security & Privacy Impact

Each task has its own IAM task role (least privilege) and security group. Tasks run in private subnets with no public IP. Images come from ECR with scanning on push.

## Cost Impact

Roughly USD 325–365/month for 8 services × 2–2.25 tasks (0.25–0.5 vCPU, 1 GB each) plus scheduled jobs if left on 24/7. The planned lifecycle uses about 60 running hours in total (about USD 37 of compute) under [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md). Saves about USD 73/month against EKS.

## Operational Impact

ECS deployment circuit breaker with automatic rollback on failed deployments. Task logs go to CloudWatch Logs ([ADR-0027](ADR-0027-observability-baseline.md)). Deployments are driven by GitHub Actions ([ADR-0028](ADR-0028-cicd-migrations-terraform.md)).

## Validation

k6 load tests in staging show services scaling out and back in; a failover drill stops one AZ's tasks and the CRM keeps serving requests ([test strategy](../testing/test-strategy.md)).

## References

- [Why ECS Fargate (and not EKS)](../architecture/system-overview.md#why-ecs-fargate-and-not-eks)
- [Compute services](../architecture/system-overview.md#compute)
- AWS documentation: Amazon ECS on AWS Fargate; Amazon EKS pricing
