# ADR-005: Temporal model

## Status

Accepted

## Context

Contract versions and entitlement evaluation need unambiguous “what was in force at time T” semantics across regions and daylight-saving changes.

## Decision

- Persist time in UTC-compatible timestamps.
- `ContractVersion` effective periods use half-open intervals: `[effectiveFrom, effectiveUntil)`.
- A timestamp `t` is covered iff `effectiveFrom <= t` and (`effectiveUntil` is null or `t < effectiveUntil`).
- Only **ACTIVATED** versions participate in temporal resolution; DRAFT versions are excluded regardless of their interval.
- **ACTIVATED** does not imply currently effective — a version may be activated for a future interval.
- DRAFT versions may omit or provisional-set intervals until activation rules lock them.
- Overlapping ACTIVATED intervals for the same contract are not allowed; adjacent half-open intervals (where one version's `effectiveUntil` equals the next version's `effectiveFrom`) are permitted.
- Non-overlap of ACTIVATED intervals is enforced at the PostgreSQL level via a partial exclusion constraint on `tstzrange` (requires `btree_gist`), in addition to domain and application checks.

## Consequences

- Avoids inclusive-end off-by-one bugs at boundaries.
- Callers must agree on the evaluation clock (request time vs event time) at the use-case boundary.
- Null `effectiveUntil` means open-ended until a later version closes it.
- Concurrent activation attempts that would create overlapping ACTIVATED intervals fail at commit time via the database exclusion constraint.
