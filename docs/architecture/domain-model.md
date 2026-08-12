# Domain model (Phase 1 scope)

Identifiers: UUID internal IDs; stable business keys for Tenant, Product, Feature, Plan, Contract.

## Aggregates / entities

| Concept | Role |
| --- | --- |
| **Tenant** | Isolation boundary. All commercial and usage data is tenant-scoped. |
| **Product** | Sellable product surface under which features and contracts live. |
| **Feature** | Meterable or gateable capability within a product. |
| **Plan** | Reusable commercial template (limits, included features). Not historical truth. |
| **PlanFeature** | Feature membership / limits on a plan template. |
| **Contract** | Logical commercial relationship: one per tenant/product initially. |
| **ContractVersion** | Versioned commercial terms with lifecycle (DRAFT → ACTIVATED) and effective interval. ACTIVATED is immutable; temporal effectiveness is derived from the half-open interval. |
| **Entitlement** | Snapshot of granted feature rights/limits bound to a contract version (frozen on activate). |

## Lifecycle rules

- **Draft** `ContractVersion`: mutable; may be edited or discarded.
- **Activated** `ContractVersion`: immutable historical commercial evidence, including entitlement snapshots. ACTIVATED is distinct from temporally effective — effectiveness is derived from `[effectiveFrom, effectiveUntil)`.
- Changing a **Plan** does not mutate existing activated contracts or their entitlements.
- Tenant isolation is mandatory on every association.

## Relationships (conceptual)

```
Tenant 1──* Contract *──1 Product
Product 1──* Feature
Plan 1──* PlanFeature *──1 Feature
Contract 1──* ContractVersion
ContractVersion (activated) 1──* Entitlement *──1 Feature
Plan ──(template reference only)──▶ ContractVersion (at creation/activation time)
```

Plan linkage on a version is informational / provenance for how terms were derived. Runtime and audit use activated contract + entitlements, not live plan rows.

## Deferred

Usage events, aggregation windows, reconciliation records, billing exports — later phases. Not modeled here.
