# Database analysis

Indexes are added only with a named query, a demonstrated deficiency, EXPLAIN evidence, and a write/storage trade-off.

**Phase 11 conclusion: no new index.** Catalogue and hot-path tables in this lab are tiny (tens to low thousands of rows). Seq scans of 51 `contract` rows are cheaper than introducing another write-amplifying index.

Control Plane remains Flyway owner. No migration was added.

## How captured

```powershell
docker exec usagecore-postgres psql -U usagecore -d usagecore -c "EXPLAIN (ANALYZE, BUFFERS) ..."
```

Also `performance` `ExplainAnalyzeCapture` for the same SQL. Date: 2026-08-18. Dataset: performance seed + load described in [baseline-results.md](baseline-results.md).

Label: `VERIFIED BY EXPLAIN ANALYZE`.

## entitlement-effective-lookup

Predicate: `tenant_id` + `product_key` + `feature_key` + `ACTIVATED` + effective interval.

- Nested loops / hash join; **Index Scan** `uq_product_product_key`; **Seq Scan** `contract` (51 rows, 50 removed by tenant filter); **Seq Scan** `entitlement` (153 rows); **Seq Scan** `contract_version` (51 rows)
- Buffers: shared hit only (12 during execution)
- Execution Time: **0.256 ms** (planning 5.4 ms)

Deficiency: none at this cardinality. A composite “hot entitlement” index would trade writes on activate for a lookup that already finishes sub-millisecond.

## meter-definition-lookup

- Seq Scan `meter_definition` (3 rows, 2 filtered); Index Scan product unique key
- Execution Time: **0.052 ms**

## usage-ingestion-idempotency-lookup

- **Index Scan** `uq_usage_ingestion_tenant_idempotency`
- Execution Time: **0.037 ms**

## outbox-pending-claim

- **Index Scan** `idx_outbox_event_pending_created` (partial `WHERE status = PENDING`)
- After drain: 0 rows; Execution Time: **0.036 ms**

The existing partial index is used. No change.

## commercial-period-covering

- **Index Scan** `ex_commercial_period_no_overlap` (exclusion gist) + product unique key initplan
- Execution Time: **0.673 ms**

## quota-state-lookup (lab SQL without window predicates)

- Seq Scan 2 rows for the tenant; Execution Time: **0.058 ms**
- Production access uses unique `(tenant_id, meter_definition_id, window_start, window_end)` — not slow here

## processed_event

PK on `event_id` — not re-measured beyond schema inspection (`VERIFIED BY CODE INSPECTION`).

## Trade-off reminder

New btree indexes would cost ingest/outbox/quota writes, vacuum, and cache. Nothing in these plans showed random I/O or multi-million-row seq scans.
