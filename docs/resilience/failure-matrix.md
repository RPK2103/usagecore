# Failure matrix (Phase 10)

UsageCore is designed for **at-least-once** delivery and proves duplicate-safe recovery across selected failure windows. This matrix is not a production HA or disaster-recovery claim.

Evidence classes: **VERIFIED BY TEST**, **VERIFIED BY CODE INSPECTION**, **VERIFIED BY DATABASE CONSTRAINT**, **REASONED BUT NOT EXECUTED**, **DEFERRED**.

| Failure | Expected externally visible behavior | Persisted state | Recovery | Duplicate risk | Evidence | Limitation |
| --- | --- | --- | --- | --- | --- | --- |
| Kafka unavailable during `POST /usage/events` | HTTP **202**; readiness stays **UP** (DB only); JWT still required | `usage_ingestion` 1; `outbox_event` PENDING 1; ledger 0 | Restore broker; publisher sends stored envelope | None at ingest | **VERIFIED BY TEST** `KafkaBrokerOutageIntegrationTest` (Docker pause) | Not a multi-broker Kafka cluster outage |
| Kafka recovers after PENDING backlog | Eventually PENDING → 0; HTTP not retried by server | All intended rows PUBLISHED; ledger count = logical events; COUNT aggregate = N | `publishBatch` / scheduler | Transport duplicates possible if ACK/PUBLISHED window also hits | **VERIFIED BY TEST** backlog of 50 | Publication order across concurrent publishers not guaranteed |
| Outbox fail before Kafka send | Publisher throws; no HTTP impact | Row remains PENDING; no ledger | Next `publishBatch` | None | **VERIFIED BY TEST** `OutboxCrashWindowIntegrationTest` | Test-only publisher gate before real send |
| Kafka ACK then crash before `PUBLISHED` | No HTTP change | Outbox still PENDING; Kafka may already contain the record | Retry publishes **same eventId**; consumer no-op on extra copies | **Expected** transport duplicate | **VERIFIED BY TEST** flagship ACK/crash | Metrics may record publish failure even though Kafka ACK succeeded |
| Consumer fail before DB commit | Listener error; offset not advanced | `processed_event` 0; ledger 0; aggregate unchanged | Bounded retry / redelivery | None if rollback holds | **VERIFIED BY TEST** latch + real Kafka/Postgres | Intermediate 0 observed while transaction is open (READ COMMITTED) |
| DB commit then offset ack fails | First attempt applied; listener throws | Inbox+ledger committed | Redelivery; inbox duplicate no-op | **Expected** redelivery | **VERIFIED BY TEST** fail-once after `process()` | Test-only seam after transactional proxy return; not a JVM kill |
| Duplicate storm (100 replays) | Listener success | inbox 1; ledger 1; aggregate once | Inbox uniqueness | Harmless | **VERIFIED BY TEST** | Unique constraint is concurrency authority |
| PostgreSQL unavailable (Usage Pipeline ingest) | Not 202; typically **503** `SERVICE_UNAVAILABLE`; readiness **not UP**; liveness still up | No new ingestion/outbox rows | Unpause DB; pool reconnect | None | **VERIFIED BY TEST** `PostgreSqlOutageIntegrationTest` | Container pause, not volume destroy; reconnect depends on Hikari/socket timeouts |
| PostgreSQL unavailable (Control Plane mutate) | Not 201; readiness down | No tenant row from failed call | Restore DB | None | **VERIFIED BY TEST** | Same pause semantics |
| PostgreSQL unavailable (Entitlement check) | Check does not return ALLOW; readiness down | No truthful success | Restore DB | None | **VERIFIED BY TEST** | Evaluation cannot succeed without SoT |
| Poison event among healthy records | A and C applied; B on DLQ | No ledger/inbox for B | Bounded retry skipped (non-retryable); DLQ | None for B | **VERIFIED BY TEST** same partition key | DLQ destination outage **DEFERRED**; no second DLQ |
| Delayed delivery after period FINALIZED | Event processed as quarantine | Ledger 1; commercial exception; aggregates not mutated | Application consumer path | None | **VERIFIED BY TEST** | Evaluates **current** period, not accept time |
| Outbox publish failure metric / pending gauge | Prometheus/Micrometer show pending and failure | PENDING rows | Restore Kafka | n/a | **VERIFIED BY TEST** | Counters can over-count rolled-back attempts |
| Duplicate processed metric | `result=duplicate` increases | Still one ledger row | n/a | n/a | **VERIFIED BY TEST** | Observability ≠ correctness |
| Process JVM restart with PENDING outbox | Publisher claims stored rows after restart | PENDING durable | Restart process / replace pod | ACK/PUBLISHED window | Phase 10: **REASONED BUT NOT EXECUTED** as a JVM kill. Phase 12 kind: **VERIFIED BY LIVE KUBERNETES FAILURE DRILL** (pending-outbox pod delete) | Kind is not EKS; see [kubernetes/failure-matrix.md](../kubernetes/failure-matrix.md) |
| Live Compose Kafka stop/start smoke | Same as Kafka pause drill | Same | Operator restores Compose Kafka | Same | **DEFERRED** to deployment phase | Testcontainers is primary evidence |
| DLQ publish failure | Recoverer may throw; `recoverer_failure` | Original offset handling per Spring Kafka | Restore DLQ topic/broker | Poison may block partition if recoverer cannot complete | **VERIFIED BY CODE INSPECTION** | **DEFERRED** experiment |
| Stale reconciliation `RUNNING` after crash | Admin APIs may block FINALIZE | `reconciliation_run` stuck RUNNING | Manual operational handling; no reaper | n/a | **VERIFIED BY CODE INSPECTION** | No lease/reaper in Phase 10 |
| PostgreSQL data destruction (volume wipe) | Missing commercial data | Gone | Backup/restore | n/a | **DEFERRED** | Not a resilience bug |

## Manual repair

Normal recovery must **not** use SQL such as `UPDATE outbox_event SET status='PUBLISHED'`, deleting `processed_event`, or rewriting aggregates. Phase 10 tests recover through application publisher/consumer paths only.

## Retry storm trade-off

Bounded consumer retries and a 1s outbox scheduler are accepted for v1. Dependency recovery can produce a burst of publish/consume attempts. No exponential-backoff framework was introduced.
