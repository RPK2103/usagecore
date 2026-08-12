# ADR-006: PostgreSQL Row Level Security (RLS) for tenancy

## Status

Accepted (deferred for v1 / Phase 2A)

## Context

ADR-002 established shared-schema tenancy with application-enforced `tenant_id` scoping.
Phase 2A adds JWT/OIDC authentication, tenant context from validated claims, RBAC, and
append-only security audit evidence. PostgreSQL RLS was re-evaluated as defense in depth.

## Decision

**Option B — intentionally defer / reject RLS for v1.**

Application-level isolation remains mandatory and is the primary control:

- Tenant authority comes only from validated JWT claims (`tenant_id`), never from
  `X-Tenant-ID`, query parameters, or request-body tenant selectors as authorization evidence.
- Tenant-owned lookups prefer `findByIdAndTenantId(...)`.
- Cross-tenant denials return 403 and create durable `security_audit_event` rows.
- PLATFORM_ADMIN uses explicit privileged paths; tenant-bound roles cannot escalate.

## Why RLS is deferred

1. **Pooled connections** — Spring/JDBC pools reuse connections across requests. Reliable RLS
   requires setting (and clearing) a session variable such as `app.tenant_id` on every
   checkout/checkin. Incorrect lifecycle handling risks tenant-session leakage across requests.
2. **Administrative bypass** — PLATFORM_ADMIN and Flyway/migrations need either a BYPASSRLS role
   or `SET ROLE` / `SET LOCAL` patterns that complicate least-privilege DB roles and tests.
3. **Migration and test complexity** — Force/enable policies on every tenant-owned table,
   including future entitlement-runtime and usage tables, with Testcontainers coverage for
   leakage scenarios. This is worthwhile later, not as a premature Phase 2A requirement.
4. **Current compensating controls** — JWT resource-server auth, centralized `TenantAccessGuard`,
   tenant-scoped repository methods, method security on `/api/v1`, and security audit evidence
   for cross-tenant and insufficient-role denials.

## Consequences

- Application bugs that omit tenant filters remain a residual risk until RLS (or equivalent)
  is adopted with proven session handling.
- Revisit RLS when entitlement-runtime and high-volume read paths are stable, with:
  - connection-pool session hooks and tests proving no cross-request leakage
  - documented admin/migration bypass
  - policies covering `contract`, `contract_version`, and future tenant-owned tables

## Related

- [ADR-002](ADR-002-postgresql-tenancy.md)
- Phase 2A Control Plane security implementation
