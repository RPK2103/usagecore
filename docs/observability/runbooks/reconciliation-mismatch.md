# Reconciliation mismatch (and run failure)

## Symptom

Alerts `ReconciliationMismatchDetected` or `ReconciliationFailureDetected`, or Grafana **Reconciliation & Data Correctness** panels showing `result=mismatch` or `result=failed`.

## Business impact

**MISMATCH does not automatically imply lost data.**

Reconciliation rebuilds derived window aggregates from canonical `usage_ledger` (plus applied UsageAdjustments) and **compares**. Divergence is reported; it is not silently repaired.

`FAILED` means the run did not complete comparison (infrastructure/application error), which is distinct from a completed MISMATCH.

## Evidence to check

| Source | What |
| --- | --- |
| Grafana | Reconciliation dashboard: run results, mismatch `type` |
| Metrics | `usagecore_reconciliation_runs_total{result}`, `usagecore_reconciliation_mismatches_total{type}` |
| API | Reconciliation run + items (classifications). Do **not** expect Prometheus to list `runId` |
| DB | `reconciliation_run` / `reconciliation_item` (read-only) |
| Related | Unresolved `commercial_usage_exception`; quarantined processing |

Classifications include: `AGGREGATE_VALUE_MISMATCH`, `EVENT_COUNT_MISMATCH`, `MISSING_AGGREGATE`, `UNEXPECTED_AGGREGATE`, `QUOTA_REPORTING_DIVERGENCE`.

Quarantined lifecycle usage can contribute to expected divergence vs “live” derived rows.

## Likely causes

- Quarantined usage against RECONCILING/FINALIZED periods
- Aggregate or event-count divergence
- Missing or unexpected derived aggregate row
- Quota reporting divergence (`quota_state` is not rewritten by adjustments)
- Run failure: DB timeout, bug, or unavailable dependency during the job

## Safe investigation

1. Fetch the reconciliation report via the API (items + classification).
2. Compare canonical ledger vs window aggregates for the classified keys.
3. If a quarantined event needs correction, use the Phase 8B **UsageAdjustment** API (`APPLY_QUARANTINED_USAGE`) against a COMPLETED run.
4. Start a **new** reconciliation run to verify; old runs remain immutable.

## Do not

- `UPDATE` window aggregates or lifetime totals directly
- Delete `reconciliation_run` / `reconciliation_item` rows
- Rewrite `quota_state` to force MATCH
- Unfinalize a CommercialPeriod by SQL
- Treat dashboards as correctness authority

## Recovery / escalation

Authorized UsageAdjustment is the supported correction path for quarantined usage. Broader rebuild/repair automation is not in Phase 9B. Phase 10 covers failure experiments, not silent commercial repair.
