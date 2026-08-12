# Initial ER model (logical)

Logical model for Phase 1. Not a physical schema — no DDL in Phase 0.

## Entities and keys

| Entity | Internal ID | Business key (stable) | Notes |
| --- | --- | --- | --- |
| Tenant | UUID | tenant_key | Isolation root |
| Product | UUID | product_key | Catalog |
| Feature | UUID | feature_key | Scoped to product |
| Plan | UUID | plan_key | Template only |
| PlanFeature | UUID | (plan_id, feature_id) | Template membership/limits |
| Contract | UUID | contract_key | One logical contract per tenant/product initially |
| ContractVersion | UUID | (contract_id, version_no) | Half-open effective interval |
| Entitlement | UUID | (contract_version_id, feature_id) | Immutable after activation |

All tenant-owned rows carry `tenant_id` for shared-schema isolation.

## Cardinality

```
Tenant
  └── Contract (tenant_id, product_id) ── unique (tenant_id, product_id) initially
        └── ContractVersion
              ├── effective_from, effective_until  -- [from, until)
              ├── status: DRAFT | ACTIVATED | …
              └── Entitlement (feature_id, limits/flags)  -- frozen on activate

Product
  ├── Feature
  └── Plan
        └── PlanFeature → Feature
```

## Temporal fields

- Persist timestamps in UTC-compatible types.
- Interval semantics: `[effectiveFrom, effectiveUntil)`.
- `effectiveUntil` null means open-ended until superseded.

## Immutability

- Draft versions: mutable columns allowed.
- Activated versions + entitlements: no in-place commercial mutation; corrections via new version.

## Explicitly not included

Tables for Kafka offsets, usage facts, warehouses, Redis caches, or multi-DB routing.
