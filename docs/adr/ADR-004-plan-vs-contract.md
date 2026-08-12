# ADR-004: Plan vs contract

## Status

Accepted

## Context

Product managers need reusable commercial packages. Tenants need stable sold terms that do not silently drift when catalog plans are edited.

## Decision

- **Plans** (and `PlanFeature`) are reusable templates for proposing terms.
- **Contracts** / activated **ContractVersions** + **Entitlements** are the historical commercial truth for a tenant–product relationship.
- Initially: one logical `Contract` per tenant/product.
- Editing a plan never mutates existing activated contracts or their entitlements.
- A contract version may record which plan (if any) informed its terms as provenance only.

## Consequences

- Plan changes affect only future drafts / new activations that choose the updated template.
- Entitlement checks and usage reconciliation ignore live plan rows for already-activated versions.
- Catalog UX must not imply “push plan update to all customers” without an explicit re-contracting flow.
