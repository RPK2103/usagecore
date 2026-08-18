# Benchmark methodology

## Workloads

### A — Entitlement check

Read-oriented commercial evaluation against an activated `ContractVersion` snapshot, plus **append-only decision evidence** (`entitlement_decision`). JWT required. Quota is not mutated.

Primary metrics: request rate, p50/p95/p99, HTTP errors, Hikari, JVM CPU/heap/GC.

### B — Durable usage ingestion

`POST /api/v1/usage/events` commits `usage_ingestion` + `outbox_event` PENDING, then returns **202**. Kafka send is asynchronous (outbox publisher batch 50 / 1s delay; Kafka ACK currently happens while the publisher transaction is open — Phase 10).

Measure HTTP acceptance separately from:

- PENDING backlog
- publisher drain
- consumer `processed_event` / `usage_ledger`

### C — Strict consume

Synchronous admission: entitlement + period `FOR SHARE` + `quota_state` conditional UPDATE + ingestion/outbox on accept. HTTP **200** for both `ACCEPTED` and `REJECTED`. Gatling KO is reserved for non-2xx / timeout.

Contention uses meter `quota_contention` (COUNT, limit 5000 on the seeded snapshot) with a **closed** injection model.

## Load profiles

| Profile | Intent | Default shape |
| --- | --- | --- |
| `warmup` | JIT, pools, JWKS cache | ~2 req/s for ≤10s — **discard** |
| `smoke` | Harness + live stack wiring | 1 user, 1 request |
| `baseline` | Unsaturated latency | `constantUsersPerSec(rps)` for `durationSeconds` |
| `ramp` | Saturation knee | ramp 1 → `3*rps` over `rampSeconds` |
| `sustained` | Leak / GC / backlog | same rate, duration at least 45s |
| `burst` | Queue/backpressure snapshot | `burstRps` for `burstSeconds` |
| `contention` | Quota lock behavior | `constantConcurrentUsers` (closed) |

Scripts run `warmup` before measurement unless `-SkipWarmup` or profile is `smoke`/`warmup`.

Open-model injections (`constantUsersPerSec` / `rampUsersPerSec`) are used for A/B so arrival rate is independent of response time (**reduces coordinated omission** vs a closed “N users looping as fast as possible”). Closed model is used when the question is **concurrency/lock contention** (quota).

Gatling still measures from request start to response; it does not reconstruct an arrival-time histogram the way [Coordinated Omission](https://www.youtube.com/watch?v=lJ8ydIuPFeU) essays describe for closed loops. Treat p99 from a saturated closed test as **optimistic** relative to a true open arrival process.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `usagecore.perf.profile` | `smoke` | Profile name |
| `usagecore.perf.baseUrl.entitlement` | `http://localhost:8082` | Runtime |
| `usagecore.perf.baseUrl.usage` | `http://localhost:8083` | Pipeline |
| `usagecore.perf.rps` | `5` | Open-model rate |
| `usagecore.perf.durationSeconds` | `20` | Hold time |
| `usagecore.perf.rampSeconds` | `15` | Ramp |
| `usagecore.perf.users` | `4` | Closed concurrency |
| `usagecore.perf.burstRps` | `30` | Burst rate |
| `usagecore.perf.burstSeconds` | `8` | Burst hold |
| `usagecore.perf.token` | (empty) | Skip Keycloak if set |
| `usagecore.perf.runId` | `local` | Idempotency key prefix |
| `usagecore.perf.fillerTenants` | `50` | Extra catalogue rows |
| `usagecore.perf.quotaLimit` | `1000000` | Snapshot limit for `scheduled_export` |
| `usagecore.perf.contentionQuotaLimit` | `5000` | Snapshot limit for `quota_contention` |

Example:

```powershell
.\mvnw.cmd -pl performance gatling:test `
  -Dgatling.simulationClass=io.usagecore.performance.gatling.UsageEventsSimulation `
  -Dusagecore.perf.profile=baseline `
  -Dusagecore.perf.rps=8 `
  -Dusagecore.perf.durationSeconds=30
```

## Result fields

Copy Gatling summary + actuator/JDBC observations into [`baseline-results.md`](baseline-results.md) using:

```text
Workload / Environment / Dataset / Profile / Duration / Rate
Requests / Success / Business rejected / Technical failed
Throughput / p50 / p95 / p99 / max
CPU / Heap-GC / DB pool / PostgreSQL / Kafka-outbox
Bottleneck / Confidence / Limitations
```

Missing signals are `NOT MEASURED`, never guessed.

## Repeatability

One local run is not a statistical capacity study. Prefer **three repeats** and report range/median when comparing an optimization. Warm-up must not be folded into those numbers.

## Error classes

| Class | How counted |
| --- | --- |
| 2xx success | Gatling OK |
| Consume `REJECTED` | HTTP 200; printed `businessRejected` counter |
| 4xx unexpected / 5xx / timeout | Gatling KO (technical) |

## Correctness after load

Ingest: tenant `usage_ingestion` count = `processed_event` = `usage_ledger` after PENDING=0 (no poison by design in this lab).

Quota: `quota_state.consumed_quantity <= configured_limit`; for COUNT meter `scheduled_export` / `quota_contention`, accepted rows should equal consumed.

Do not relax locking or skip ledger/inbox to improve TPS.
