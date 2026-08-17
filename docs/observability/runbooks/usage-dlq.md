# Usage DLQ

## Symptom

Alert `UsageDlqDetected` or Grafana **Usage Delivery** DLQ panel: `usagecore_usage_dlq_total` increasing (`reason=non_retryable|retry_exhausted|recoverer_failure`).

## Business impact

A `UsageReceived` Kafka record could not be processed through the **bounded retry** path and was recovered to the dead-letter topic.

Canonical ledger / aggregates are **not** updated for that delivery. Durable ingestion (HTTP 202 + outbox) may already have succeeded earlier; DLQ is a **processing** failure for that consumer attempt.

## Evidence to check

| Source | What |
| --- | --- |
| Grafana | Usage Delivery: DLQ rate by `reason` |
| Metrics | `usagecore_usage_dlq_total` |
| Logs | Consumer recoverer logs; `eventId`, `correlationId`, `traceId` in MDC |
| Kafka | DLQ topic contents (local only). Identify `eventId` from the envelope |

## Likely causes

- Payload that fails domain validation after durable accept (should be rare if HTTP validated)
- Transient DB errors that exhausted retries
- Unexpected consumer exceptions classified as non-retryable
- Recoverer itself failing (`recoverer_failure`)

## Safe investigation

1. Note `reason` from the metric label (bounded enum).
2. Find matching log lines with `eventId` / `correlationId` (logs/traces — **not** Prometheus labels).
3. Confirm whether a `usage_ledger` row exists for that `eventId` (processing may have failed before write).
4. Inspect reconciliation later if derived state looks incomplete.

## Do not

- Blindly replay everything on the DLQ
- Delete DLQ records to clear the alert
- Insert ledger or aggregate rows by hand
- Disable idempotency / inbox uniqueness

## Recovery / escalation

Phase 10 / operational replay may extend DLQ handling. Today: diagnose with logs and canonical tables; do not automate replay. No Phase 9B remediation job exists.
