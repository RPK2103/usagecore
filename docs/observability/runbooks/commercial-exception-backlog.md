# Commercial usage exception backlog

## Symptom

Alert `CommercialExceptionsAccumulating` or Grafana gauges `usagecore_commercial_usage_exceptions_unresolved` remaining above zero, and/or `usagecore_commercial_usage_exceptions_total` increasing (`PERIOD_RECONCILING` / `PERIOD_FINALIZED`).

## Business impact

`commercial_usage_exception` is **evidence** that ordinary consumer processing **intentionally did not** mutate derived commercial state while the period is RECONCILING or FINALIZED.

It is **not** automatically corruption. Canonical `usage_ledger` may still contain the event.

## Evidence to check

| Source | What |
| --- | --- |
| Grafana | Usage Delivery + Reconciliation: unresolved gauge, exception create rate, adjustments |
| Metrics | `usagecore_commercial_usage_exceptions_unresolved`, `usagecore_commercial_usage_exceptions_total` |
| API / DB | Exception rows; whether an applied `usage_adjustment` exists |
| Reconciliation | Whether derived state is expected given quarantine |

## Likely causes

- Usage arrived while the CommercialPeriod was RECONCILING or FINALIZED (by design)
- Adjustments not yet applied (or not applicable)
- Adjustment conflict/rejection (`usagecore_usage_adjustments_total{result}`)

## Safe investigation

1. Confirm period lifecycle (Control Plane) for the affected product/period via API — not Prometheus tenant labels.
2. Inspect reconciliation reports for related mismatch classifications.
3. Determine whether the exception is expected.
4. If correction is required, apply UsageAdjustment **only** through the authorized API.

## Do not

- `DELETE` exception rows to make the metric disappear
- Rewrite `usage_ledger` or aggregates
- Mutate `quota_state`
- Apply adjustments outside the documented API

## Recovery / escalation

Unresolved exceptions remain until a valid UsageAdjustment is applied (or they are accepted as historical quarantine evidence). No automatic drain job exists in Phase 9B.
