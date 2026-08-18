# Kubernetes failure matrix (Phase 12)

Complements [Phase 10 resilience matrix](../resilience/failure-matrix.md). Local kind topology only.

| Experiment | Topology | Expected behavior | Observed behavior | Business invariant | Evidence | Limitation |
| --- | --- | --- | --- | --- | --- | --- |
| Pod restart (usage-pipeline) | 2 replicas | Remaining replica serves; replaced pod reaches Ready | Pod delete during kafka-down; replacement Ready; PENDING row survived | At-least-once + inbox dedupe | **VERIFIED BY LIVE KUBERNETES FAILURE DRILL** | Single-node scheduling may colocate pods |
| Usage Pipeline scale 1→2→3→2 | Shared consumer group + outbox SKIP LOCKED | No duplicate ledger rows per eventId | Rollouts completed; final DB: ingestion=3, processed=3, ledger=3 | One business effect per eventId | **VERIFIED BY LIVE KUBERNETES FAILURE DRILL** + **VERIFIED BY DATABASE STATE** | Scale ended at 2 replicas in drill script |
| Kafka outage | PG up, kafka scaled to 0 | HTTP 202; readiness UP (DB); PENDING grows | HTTP 202 during outage; readiness True; PENDING=1; after restore eventually PUBLISHED | Durable acceptance before transport | **VERIFIED BY LIVE KUBERNETES FAILURE DRILL** | Single broker; drain latency ~30s |
| PostgreSQL outage | PG scaled to 0 | Readiness DOWN; liveness UP; not false 202 | Readiness False; container still running | No durable lie | **VERIFIED BY LIVE KUBERNETES FAILURE DRILL** | Not volume destroy |
| Rolling deployment | entitlement-runtime env marker | Pod churn; bounded technical errors reported honestly | `kubectl set env` rollout completed (not traffic-measured) | Read path stays correct | **VERIFIED BY KUBERNETES CONFIGURATION** | Gatling rollout traffic not executed in bounded drill |
| Pending outbox + pod delete | Kafka down, events, delete pod | PENDING survives; after Kafka restore → PUBLISHED → ledger once | PENDING=1 survived pod delete; after kafka restore all 3 PUBLISHED/processed/ledger | Outbox durability boundary | **VERIFIED BY LIVE KUBERNETES FAILURE DRILL** + **VERIFIED BY DATABASE STATE** | Phase 10 gap closed |
| Authenticated smoke | port-forward | entitlement 200, usage 202, async processing | entitlement HTTP 200; usage HTTP 202; outbox→ledger after topics init | End-to-end local path | **VERIFIED BY LIVE KUBERNETES SMOKE** | `/usage/consume` needs `occurredAt` (fixed in script) |
| Consumer interruption | Delete pod mid-processing | Kafka redelivery; inbox no-op | Not executed under load | Duplicate transport OK | **REASONED BUT NOT EXECUTED** | Optional |
| Quota across 2 replicas | Concurrent `/usage/consume` | PostgreSQL authority; consumed ≤ limit | Not executed | Quota concurrency in PG | **REASONED BUT NOT EXECUTED** | Optional |
| PostgreSQL pod + PVC | Delete postgres pod | Data survives on same PVC | Not executed | Pod replacement only | **REASONED BUT NOT EXECUTED** | Optional |
| Helm rollback | `helm rollback usagecore` | Prior revision restored | Documented; not live-executed (requires explicit approval) | App rollback ≠ schema rollback | **CONFIGURATION VALIDATED ONLY** | |
| Worker node failure | kind worker stop | Pods reschedule | **DEFERRED** | n/a | Optional | Often brittle locally |
| NetworkPolicy enforcement | default-deny | Only allowed paths | **CONFIGURATION VALIDATED ONLY** | n/a | Not deployed | CNI varies on kind |
| Cluster destruction | `kind delete cluster` | All state gone | **REASONED BUT NOT EXECUTED** | n/a | Expected | Not DR |

## Phase 10 gap

| Scenario | Phase 10 | Phase 12 |
| --- | --- | --- |
| JVM/pod restart with PENDING outbox | REASONED BUT NOT EXECUTED | **VERIFIED BY LIVE KUBERNETES FAILURE DRILL** — PENDING survived pod delete; drained after Kafka restore |

## Notes

- Initial deploy required kafka topic init Job + usage-pipeline restart because Kafka probes were slow on first boot.
- Kafka probe fix: TCP socket probes with longer timeouts (exec probes timed out on constrained kind nodes).
