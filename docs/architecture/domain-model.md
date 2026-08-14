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
| **MeterDefinition** | Product-scoped meter configuration (`SUM`/`COUNT`/`MAX`, `DAILY`/`MONTHLY`) with explicit Feature binding for quota. |
| **CommercialPeriod** | Tenant+product commercial accounting window `[period_start, period_end)` with lifecycle `OPEN`→`CLOSING`→`RECONCILING`→`FINALIZED`. Separate from event-time usage windows and from ContractVersion activation. |
| **ReconciliationRun / ReconciliationItem** | Immutable Phase 8A rebuild/compare/report evidence for a commercial period (MATCH/MISMATCH). Never repairs derived state. |
| **UsageAdjustment** | Append-oriented Phase 8B commercial correction: apply one quarantined canonical ledger event to derived aggregates with operator evidence. |

## Lifecycle rules

- **Draft** `ContractVersion`: mutable; may be edited or discarded.
- **Activated** `ContractVersion`: immutable historical commercial evidence, including entitlement snapshots. ACTIVATED is distinct from temporally effective — effectiveness is derived from `[effectiveFrom, effectiveUntil)`.
- Changing a **Plan** does not mutate existing activated contracts or their entitlements.
- **CommercialPeriod** `FINALIZED` is terminal for ordinary usage/quota mutation of that range; Phase 7 finalization is administrative and does not prove reconciliation correctness.
- Phase 8A reconciliation rebuilds expected commercial aggregates from canonical `usage_ledger` and reports MATCH/MISMATCH without mutating derived state ([ADR-015](../adr/ADR-015-reconciliation-and-deterministic-rebuild.md)).
- Phase 8B may change derived commercial aggregates for `RECONCILING`/`FINALIZED` periods only through an explicit `UsageAdjustment` referencing quarantined canonical usage ([ADR-016](../adr/ADR-016-explicit-usage-adjustments.md)). The period is not unfinalized.
- Tenant isolation is mandatory on every association.

## Relationships (conceptual)

```
Tenant 1──* Contract *──1 Product
Product 1──* Feature
Product 1──* MeterDefinition *──1 Feature
Product 1──* CommercialPeriod *──1 Tenant
Plan 1──* PlanFeature *──1 Feature
Contract 1──* ContractVersion
ContractVersion (activated) 1──* Entitlement *──1 Feature
Plan ──(template reference only)──▶ ContractVersion (at creation/activation time)
```

Plan linkage on a version is informational / provenance for how terms were derived. Runtime and audit use activated contract + entitlements, not live plan rows.

Usage windows (`usage_window_aggregate`) remain event-time derived state under meters; commercial period status governs whether ordinary processing may mutate that commercial history ([ADR-014](../adr/ADR-014-commercial-period-lifecycle.md)). Reconciliation compares rebuilt expected window state to persisted derived rows ([ADR-015](../adr/ADR-015-reconciliation-and-deterministic-rebuild.md)). Explicit `UsageAdjustment` is the only Phase 8B repair path ([ADR-016](../adr/ADR-016-explicit-usage-adjustments.md)).

## Deferred

Compensating adjustment / automatic exception application / quota repair / Kafka historical replay / billing exports.
