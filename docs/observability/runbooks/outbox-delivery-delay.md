# Outbox delivery delay

## Symptom

Alert `OutboxDeliveryDelayed` and/or `OutboxPublishFailures`, or Grafana **Usage Delivery** panels:

- oldest PENDING outbox age rising
- PENDING count rising
- `usagecore_outbox_publish_total{result="failure"}` increasing

HTTP usage ingestion may still return **202**.

## Business impact

`POST /api/v1/usage/events` writes durable PostgreSQL ingestion + a `PENDING` outbox row, then returns HTTP **202**.

Kafka unavailability (or publisher failure) therefore often produces:

- HTTP still succeeding
- outbox pending age increasing

That is **delivery delay**, not **ingestion failure**.

Kafka outage is **not** equivalent to Usage Pipeline being unable to durably accept usage.

Quotas, window aggregates, and billing-derived state do not change until the consumer processes the event.

## Evidence to check

| Source | What |
| --- | --- |
| Grafana | Usage Delivery: PENDING count, oldest age, publish success/failure |
| Metrics | `usagecore_outbox_pending`, `usagecore_outbox_oldest_pending_age_seconds`, `usagecore_outbox_publish_total` |
| Logs | Usage Pipeline outbox publisher errors; `correlationId` / `traceId` on the original HTTP request |
| DB | `outbox_event` rows with `status = PENDING` (count and `min(created_at)`). Do not rewrite status. |
| Actuator | `/actuator/health/readiness` on Usage Pipeline depends on **PostgreSQL**, not Kafka |

## Likely causes

- Kafka broker down or unreachable from the host
- Publisher batch errors (serialization, topic missing)
- Application restart with a backlog (pending age continues until publish succeeds)
- Network partition between Usage Pipeline and Kafka

## Safe investigation

1. Confirm HTTP ingestion still returns 202 (ingestion path).
2. Query Prometheus: `usagecore_outbox_oldest_pending_age_seconds` vs `usagecore_outbox_pending`.
3. Inspect Usage Pipeline logs for publish failures.
4. Confirm Kafka listener/port `localhost:9092` in the local Compose topology.
5. Use `correlationId` from the HTTP request to find the outbox envelope; do not use tenant labels in Prometheus.

## Do not

- Manually `UPDATE outbox_event SET status = 'PUBLISHED'`
- Delete outbox or `usage_ingestion` rows
- Replay by inserting duplicate ledger rows
- Treat scrape `up` or HTTP 202 as proof that Kafka delivery completed

## Recovery / escalation

The existing publisher retries `PENDING` rows (`FOR UPDATE SKIP LOCKED`). Restore Kafka and allow the publisher to catch up.

Phase 10 may add failure drills and operational replay tooling. There is **no** automatic remediation in Phase 9B.
