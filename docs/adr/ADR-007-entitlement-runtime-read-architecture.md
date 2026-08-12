# ADR-007: Entitlement Runtime read architecture and shared PostgreSQL

## Status

Accepted

## Context

Phase 3 introduces `applications/entitlement-runtime` as a second independently
deployable UsageCore workload. It must answer authenticated commercial entitlement
checks with low latency without depending on the Control Plane application module
or copying its full domain model.

PostgreSQL remains the transactional source of truth for commercial state
(Contract / activated ContractVersion / entitlement snapshots).

## Decision

### Service boundary

- Entitlement Runtime has **no compile-time dependency** on Control Plane.
- It owns a **narrow runtime read model** (`CommercialEntitlementReader`) and
  JDBC adapter that resolve only:
  authenticated `tenantId` + `productKey` + `featureKey` + evaluation `Instant`
  → activated ContractVersion entitlement snapshot (half-open interval semantics).
- It does **not** read live `plan_feature` rows; activated snapshots are commercial truth.
- Decision outcomes are `ALLOW` / `DENY` / `ALLOW_WITH_LIMIT` with stable reason codes.
- Every successfully evaluated authenticated check appends an `entitlement_decision`
  evidence row (including commercial DENY).

### Shared schema (deliberate v1 trade-off)

| Property | Choice |
| --- | --- |
| Deployment | Independent processes / artifacts |
| Commercial authority | Shared authoritative PostgreSQL schema |
| Module coupling | No Control Plane compile-time dependency |
| Migrations | Shared resource-only `libraries/database-migrations` |
| Production Flyway owner | **Control Plane only** |
| Runtime Flyway | Disabled by default; tests may enable against Testcontainers |

Future dedicated read-models, replicas, or caches require **measured evidence** of
latency/load need — not premature Redis/Kafka introduction.

### Quota limitation (v1)

There is no usage consumption model yet. Responses expose `configuredLimit`
(contractual configuration) only. Do **not** return or calculate `remainingQuota`,
`consumedUnits`, or `reservedUnits` until the metering/quota phase.

## Consequences

- Runtime can deploy and scale separately while reading the same commercial SoT.
- Schema evolution must be coordinated; Control Plane remains migration owner.
- Application-level tenant scoping remains mandatory (RLS still deferred per ADR-006).
- Residual risk: shared DB coupling until a measured read-model split is justified.
