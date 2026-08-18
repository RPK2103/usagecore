# Security model

UsageCore is an OAuth2 resource-server system. Local identity is Keycloak. Tenant isolation is an application-layer control on a shared PostgreSQL schema.

## Authentication

- Spring Security OAuth2 Resource Server (JWT) on `/api/v1` for all three workloads.
- JWKS from `USAGECORE_JWK_SET_URI` (local default: Keycloak realm `usagecore`).
- Automated tests mint JWTs; they do not require a live IdP.
- AWS target architecture consumes an **externally managed** OIDC issuer. Keycloak is not the EKS production IdP. Cognito is a possible later choice and is **not configured**.

## Tenant authority

- `tenant_id` comes from the validated JWT claim.
- Request body, `X-Tenant-ID`, and query parameters are **not** authorization evidence.
- Usage ingest/consume DTOs omit `tenantId`; unknown properties fail deserialization.
- Application filtering and tenant-scoped queries are **not** PostgreSQL Row Level Security.

## RBAC (local realm)

| Role | Typical use |
| --- | --- |
| `PLATFORM_ADMIN` | Cross-tenant platform operations |
| `TENANT_ADMIN` | Tenant administration |
| `CONTRACT_MANAGER` | Contracts / entitlement check |
| `DEVELOPER` | Entitlement check, `/usage/events`, `/usage/consume` |
| `AUDITOR` | Read aggregates / reconciliation items |
| `BILLING_OPERATOR` | Commercial periods, reconciliation, adjustments |

Method security (`@PreAuthorize`) enforces roles. Cross-tenant denials are 403 and can produce `security_audit_event` rows on Control Plane.

## Secrets

- Compose and Helm local values use **placeholder** credentials (`usagecore` / Keycloak `admin`). Development only.
- No cloud secrets, JWTs, or Terraform state belong in git.
- AWS design: Secrets Manager containers; deploy-time sync into a Kubernetes Secret ([docs/cicd/deployment.md](../cicd/deployment.md)). **Not live-proven.**
- GitHub OIDC to AWS: short-lived roles, no long-lived access keys in workflows. **Architecture only unless executed.**

## Containers and Kubernetes

- Non-root application images.
- Pod security / probes documented in Phase 12.
- Kubernetes NetworkPolicy is **not proven**.
- In-cluster Secret is base64 local placeholders — not production secret management.

## PostgreSQL RLS

Intentionally **deferred** ([ADR-006](../adr/ADR-006-postgresql-rls.md), roadmap Phase 7B). Compensating controls: JWT, `TenantAccessGuard`, tenant-scoped repositories, method security, security audit evidence.

Do not present those controls as “database tenant isolation.”

## Remaining security limitations

Still true:

- PostgreSQL RLS deferred
- Kubernetes NetworkPolicy not proven
- AWS IAM apply role is broad in places relative to a locked-down production account ([docs/aws/security.md](../aws/security.md))
- Live GitHub OIDC exchange not executed (unless recorded later)
- Secrets Manager runtime sync not live-proven
- GitHub environment protection / branch protection recommended in docs, not verified as enabled on the remote
- Local Keycloak `tenant_id` placeholders must be aligned with Tenant UUIDs for a live demo (performance seeder does this for Acme)

See also [limitations.md](../limitations.md).
