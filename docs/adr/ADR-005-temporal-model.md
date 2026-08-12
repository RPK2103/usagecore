# ADR-005: Temporal model

## Status

Accepted

## Context

Contract versions and entitlement evaluation need unambiguous “what was in force at time T” semantics across regions and daylight-saving changes.

## Decision

- Persist time in UTC-compatible timestamps.
- `ContractVersion` effective periods use half-open intervals: `[effectiveFrom, effectiveUntil)`.
- A timestamp `t` is covered iff `effectiveFrom <= t` and (`effectiveUntil` is null or `t < effectiveUntil`).
- Draft versions may omit or provisional-set intervals until activation rules lock them.
- Overlapping activated intervals for the same contract are not allowed; supersession closes the prior interval.

## Consequences

- Avoids inclusive-end off-by-one bugs at boundaries.
- Callers must agree on the evaluation clock (request time vs event time) at the use-case boundary.
- Null `effectiveUntil` means open-ended until a later version closes it.
