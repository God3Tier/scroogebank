# ADR-0087: Root Admin is an application role and alone creates Admin accounts

- Status: Accepted
- Decision date/time: 2026-11-07 15:21 +08:00 (Asia/Singapore)
- Decision owner: Joseph Yau (Feature 2 primary developer)
- Drafted/assisted by AI: Yes; Codex
- Feature/branch: Feature 2; `task/base-features/root-admin-authorization`
- Related issue/PR: pending
- Supersedes: N/A; refines Feature 1's root bootstrap and user-management authorization

## Context

The existing bootstrap account is an ordinary Cognito `ADMIN` group member and a
local `ADMIN` row protected by `deletable = false`. That representation cannot
distinguish Root Admin from other admins when the dashboard decides whether to
offer Admin creation. The API currently permits every admin to create another
admin and permits role changes that promote an Agent.

## Decision

1. Add `ROOT_ADMIN` as a distinct user-service and API application role. It is
   not a Cognito group. The root account remains in Cognito's `ADMIN` group, so
   it keeps the existing authorization in every service.
2. Flyway V2 expands the role constraint, migrates the existing non-deletable
   `ADMIN` bootstrap row to `ROOT_ADMIN`, and adds a partial unique index that
   allows only one such row. A fresh bootstrap writes `ROOT_ADMIN` locally and
   adds Cognito membership in `ADMIN`.
3. Admin creation resolves the caller's verified Cognito `sub` to an enabled
   local user record. Only a record with role `ROOT_ADMIN` may create an
   `ADMIN`; any admin may create an `AGENT`. User-management endpoints reject
   requests to create `ROOT_ADMIN`.
4. Role changes reject `ADMIN` and `ROOT_ADMIN`, so no API promotes an account
   to either role or transfers root status. The root row cannot be demoted or
   disabled, guarded by both its application role and the existing
   `deletable = false` invariant.
5. Admin-only `GET /api/users/me` returns the signed-in user resolved from the
   token subject. The dashboard offers Admin creation only when that response
   identifies `ROOT_ADMIN`; regular admins can create Agents. Admin lists show
   the seeded account as “Root Admin”.
6. Root Admin is not an Agent and gains no client or account ownership or write
   permissions. Existing admin read access and read-only restrictions on
   client data remain unchanged (ADR-0059).

## Rationale

The Cognito group continues to serve cross-service authorization while the
database record provides the narrower root-only user-management privilege.
Looking up the verified `sub` prevents a caller from selecting a privileged
identity in request data. Enforcing policy in user-service preserves it for
clients other than the dashboard.

## Consequences

- Existing user IDs, Cognito subjects, emails and Cognito group membership are
  preserved by the migration.
- The Admin list filter includes both `ADMIN` and `ROOT_ADMIN` rows.
- Creating an Admin remains available to the bootstrap Root Admin only.
- Role changes can still demote an Admin to Agent; they cannot grant either
  admin role.

## Security and privacy impact

Authorization uses the signature-verified access token's `sub` plus the
matching enabled database record. `GET /api/users/me` remains behind the
Cognito `ADMIN` authority check. The API never trusts a caller-supplied role or
subject. No client/account service permissions change; the root remains in the
same read-only admin path defined by ADR-0059.

## Cost impact

None. The change uses existing user-service, Flyway, Cognito and dashboard
resources.

## Operational impact

Deploy the migration and user-service before the dashboard. The V2 migration
must be applied before new application code starts. The initializer handles a
fresh database after migrations; an existing deployment retains Cognito
`ADMIN` membership.

## Validation

- Migration test: an existing `ADMIN`, `deletable=false` row becomes
  `ROOT_ADMIN` while retaining its ID, subject and protection flag.
- Bootstrap test: local role is `ROOT_ADMIN`; Cognito group assignment remains
  `ADMIN`.
- Service tests: Root Admin can create Admin and Agent; a regular Admin can
  create Agent but receives 403 for Admin; neither API can create or transfer
  Root Admin; role changes cannot promote to Admin or Root Admin.
- `/api/users/me` contract and authorization tests distinguish Root Admin from
  regular Admin using the verified subject.
- Frontend tests cover each admin's creation choices and the Root Admin label.
- Existing client/account services remain admin read-only and do not assign
  clients to the root account.

## References

- [ADR-0005: Cognito security across Features 1 and 2](ADR-0005-cognito-security-across-features-1-and-2.md)
- [ADR-0059: Admins have read-only access to client data](ADR-0059-admins-read-only-on-client-data.md)
- [Feature 2 plan](../feature-plans/feature-2_plan.md)
- Requirements UM06, UM09–UM14
