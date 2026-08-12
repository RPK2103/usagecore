# ADR-003: Contract historical state

## Status

Accepted

## Context

Commercial disputes and entitlement audits require a durable record of what was sold and when. Mutating activated terms in place destroys that evidence.

## Decision

- `ContractVersion` has a draft phase that is mutable.
- Once activated, a `ContractVersion` and its entitlement snapshots are immutable historical commercial evidence.
- Corrections and commercial changes create a new `ContractVersion` with a new effective interval — not in-place edits to activated rows.

## Consequences

- Activation is a hard boundary: validation must complete before activate.
- Storage grows with version history; that is accepted for auditability.
- Runtime and reconciliation must bind to the version effective at the evaluation time, not “latest plan.”
