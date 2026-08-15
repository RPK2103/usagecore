# Custom metrics catalogue (Phase 9A)

Logical Micrometer names are dotted. Prometheus adds suffixes (`_total`, `_seconds`) according to type.

Common tag on all meters: `application` = Spring application name (three bounded values).

**Never** use tenant/event/request identifiers as labels.

## Usage Pipeline

| Logical name | Type | Labels | Purpose |
| --- | --- | --- | --- |
| `usagecore.outbox.publish` | Counter | `result=success\|failure` | Outbox Kafka publish attempts |
| `usagecore.outbox.publish.duration` | Timer | (none) | Publish latency |
| `usagecore.outbox.pending` | Gauge | (none) | Current `PENDING` outbox rows (scrape-time SQL) |
| `usagecore.outbox.oldest.pending.age` | Gauge (seconds) | (none) | Age of oldest PENDING row |
| `usagecore.usage.events.processed` | Counter | `result=applied\|duplicate\|quarantined\|rejected_invalid` | Consumer processing outcomes |
| `usagecore.usage.process.duration` | Timer | (none) | Consumer listener processing time |
| `usagecore.usage.dlq` | Counter | `reason=non_retryable\|retry_exhausted\|recoverer_failure` | DLQ recoveries |
| `usagecore.aggregate.updates` | Counter | `aggregationType=SUM\|COUNT\|MAX` | Derived aggregate mutations (not tenant totals) |
| `usagecore.quota.decisions` | Counter | `decision`, `reason` (finite quota reason codes) | Synchronous quota admission |
| `usagecore.quota.consume.duration` | Timer | (none) | Quota consume duration |
| `usagecore.commercial.usage.exceptions` | Counter | `reason=PERIOD_RECONCILING\|PERIOD_FINALIZED` | Quarantine records |
| `usagecore.commercial.usage.exceptions.unresolved` | Gauge | (none) | Exceptions without an applied UsageAdjustment |
| `usagecore.reconciliation.runs` | Counter | `result=match\|mismatch\|failed` | Reconciliation run completions |
| `usagecore.reconciliation.duration` | Timer | (none) | Reconciliation duration |
| `usagecore.reconciliation.mismatches` | Counter | `type` (finite classification enum) | Item-level mismatch classes |
| `usagecore.usage.adjustments` | Counter | `result=applied\|replay\|conflict\|rejected` | Explicit UsageAdjustment outcomes |
| `usagecore.usage.adjustment.duration` | Timer | (none) | Adjustment duration |

Duplicates are represented as `usagecore.usage.events.processed{result="duplicate"}` (no separate duplicate counter).

## Entitlement Runtime

| Logical name | Type | Labels | Purpose |
| --- | --- | --- | --- |
| `usagecore.entitlement.decisions` | Counter | `decision`, `reason` | Entitlement check outcomes |
| `usagecore.entitlement.decision.duration` | Timer | (none) | Evaluation duration |

## Control Plane

| Logical name | Type | Labels | Purpose |
| --- | --- | --- | --- |
| `usagecore.commercial.period.transitions` | Counter | `from`, `to`, `result=success\|rejected` | Commercial period lifecycle transitions |

## Technical metrics (framework)

Exposed via Actuator/Micrometer, not reimplemented:

- HTTP: `http.server.requests` (count, duration, status, URI template)
- JVM: memory, GC, threads, CPU, process uptime
- Database: Hikari connection pool (`hikaricp.*`)

Inspect `/actuator/prometheus` for the exact exported names after a scrape.
