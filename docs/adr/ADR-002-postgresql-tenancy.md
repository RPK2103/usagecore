# ADR-002: PostgreSQL tenancy

## Status

Accepted

## Context

UsageCore is multi-tenant. Isolation must be mandatory without premature operational complexity (schema-per-tenant, DB-per-tenant).

## Decision

- Single shared PostgreSQL schema for transactional data.
- Every tenant-owned row includes `tenant_id`; all queries and writes are tenant-scoped in application code.
- Internal IDs are UUIDs; tenants also have a stable business key.
- PostgreSQL Row Level Security (RLS) will be evaluated in the security phase as defense in depth — not implemented in Phase 0/1 foundation.

## Consequences

- Simpler migrations and connection management early on.
- Application bugs that omit tenant filters are a real risk until RLS (or equivalent) is assessed.
- Cross-tenant analytics must be explicit and controlled, not accidental joins.
