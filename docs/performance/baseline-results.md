# Baseline results

Local-lab measurements only. **Not production capacity.**

Phrase used below: “On the documented local environment, profile X produced Y.”

Warm-up rows are recorded for completeness and **must not** be used as headline latency.

Evidence labels: `VERIFIED BY LIVE PERFORMANCE RUN` unless noted.

## Environment

```text
runId: 2026-08-18 local Phase 11 lab
date: 2026-08-18
OS: Windows 11 (10.0), amd64
CPU: AMD Ryzen 7 7735HS with Radeon Graphics; 8 cores / 16 logical processors
RAM: 16369414144 bytes (~15.2 GiB)
JDK: Eclipse Temurin 21.0.12+8-LTS (java.home=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot)
Docker Engine: 29.6.2
PostgreSQL: postgres:16-alpine (container usagecore-postgres; HostConfig.Memory=0 NanoCPUs=0)
Kafka: apache/kafka:3.8.1 (container usagecore-kafka; HostConfig.Memory=0 NanoCPUs=0)
Keycloak / Prometheus / Grafana / OTel: Compose defaults
Compose resource limits: none configured on postgres/kafka (Memory=0)
notes: shared developer laptop; Docker Desktop; three Spring Boot JARs on host; noisy neighbors possible
```

Dataset: performance seeder — tenant `11111111-1111-1111-1111-111111111111` (`acme`), product `datapilot-cloud`, 50 filler tenants, OPEN commercial period 2026–2027, `scheduled_export` LIMITED 1_000_000, `quota_contention` LIMITED 5000.

Auth: one cached Keycloak password-grant for `acme-developer` per Gatling JVM. Token endpoint **not** in the measured request loop. JWT validation on the resource server **is** included.

## Workload A — Entitlement check

### Smoke (discard as headline; wiring only)

```text
Workload: A entitlement check
Profile: smoke
Duration: 1 request
Rate/concurrency: atOnceUsers(1)

Requests: 1
Success: 1
Business rejected: 0
Technical failed: 0

Throughput: 1 req/s (single sample)
p50: 661 ms
p95: 661 ms
p99: 661 ms
max: 661 ms
```

Cold JVM + first JWKS. Not a latency baseline.

### Warm-up (excluded from headlines)

```text
Profile: warmup (~2 req/s, ~10 s)
Requests: 20  Success: 20  Technical failed: 0
p50: 18 ms  p95: 28 ms  p99: 28 ms  max: 28 ms
Throughput: 2 req/s
```

### Baseline

```text
Workload: A entitlement check
Environment: as above
Dataset: scheduled_exports LIMITED snapshot
Profile: baseline
Duration: 20 s
Rate/concurrency: constantUsersPerSec(5) open model

Requests: 100
Success: 100
Business rejected: 0
Technical failed: 0

Throughput: 5 req/s
p50: 12 ms
p95: 20 ms
p99: 24 ms
max: 24 ms
min: 9 ms

CPU: process_cpu_usage ~6e-4 after the run (idle scrape)
Heap/GC: G1 young pauses 2–8 ms (Micrometer); entitlement heap Eden ~44 MB used at scrape
DB pool: Hikari max=10 idle=10 active=0 pending=0 timeouts=0 (after run)
Relevant PostgreSQL observations: EXPLAIN execution 0.256 ms; seq scans on 51-row catalogue tables; all buffers shared hit
Kafka/outbox observations: N/A

Bottleneck: not reached at 5 req/s. Path includes entitlement_decision INSERT.
Confidence: single local run
Limitations: unsaturated; decision writes grow the table; 50 filler tenants only
```

### Ramp

```text
Profile: ramp 1 → 15 req/s over 15 s (open)
Requests: 120  Success: 120  Technical failed: 0
Throughput mean: 8 req/s
p50: 9 ms  p95: 12 ms  p99: 17 ms  max: 38 ms
```

No saturation knee in this window. Latency stayed similar to (or better than) baseline.

## Workload B — Durable `/usage/events` HTTP 202

### Warm-up (excluded)

```text
Requests: 20  Success: 20  Technical failed: 0
p50: 18 ms  p95: 439 ms  p99: 439 ms  max: 439 ms
Throughput: 2 req/s
```

p95/p99 dominated by the first JIT/JWKS sample.

### Baseline (HTTP acceptance only)

```text
Workload: B usage events 202
Profile: baseline
Duration: 20 s
Rate/concurrency: constantUsersPerSec(5) open

Requests: 100
Success: 100
Business rejected: 0
Technical failed: 0

Throughput: 5 req/s
p50: 11 ms
p95: 17 ms
p99: 25 ms
max: 25 ms

CPU: NOT MEASURED during the interval (post-run scrape only)
Heap/GC: see JFR on usage-pipeline
DB pool: Hikari max=10 pending=0 timeouts=0 after later combined load
Kafka/outbox observations: HTTP 202 does not wait for publish; see burst
Bottleneck: not reached at 5 accepted req/s
Confidence: single local run
Limitations: measures PostgreSQL ingest+outbox insert, not ledger completion
```

### Burst + drain

```text
Workload: B burst HTTP + async drain
Profile: burst (~30 req/s, 8 s)
Requests: 240
Success: 240
Technical failed: 0
p50/p95/p99: NOT MEASURED (Gatling HTML aborted when after() raced the consumer; HTTP counters were 240/240)
Throughput: ~30 accepted req/s during the 8 s inject

peak PENDING observed (200 ms sampler): 38
Outbox PENDING=0 after: 522 ms
Immediately after PENDING=0: usage_ingestion=360 processed_event=350 usage_ledger=350
  (includes prior warmup+baseline 120 events; burst added 240)
A few seconds later (SQL): ingestion=processed=ledger=360, PENDING=0, PUBLISHED=360
```

Finding: publisher drained quickly locally; **consumer lag is a second clock**. PENDING=0 is not ledger completion. Verifier was tightened to wait for `ingestion = processed = ledger`.

## Workload C — Strict `/usage/consume`

COUNT meter contribution = 1. HTTP 200 + `REJECTED` would be business rejection (Gatling OK).

### Warm-up (excluded)

```text
Requests: 20  Success: 20  businessAccepted: 20  businessRejected: 0  technical: 0
p50: 19 ms  p95: 82 ms  max: 82 ms  throughput: 2 req/s
```

### Baseline (high remaining quota, `scheduled_export` limit 1_000_000)

```text
Workload: C usage consume
Profile: baseline
Duration: 20 s
Rate/concurrency: constantUsersPerSec(5) open

Requests: 100
Success: 100
Business rejected: 0
Technical failed: 0

Throughput: 5 req/s
p50: 16 ms
p95: 21 ms
p99: 51 ms
max: 51 ms

Correctness (SQL): scheduled_export consumed=120 (warmup 20 + baseline 100) = ACCEPTED rows; limit=1000000; consumed <= limit
```

### Contention (closed, `quota_contention` limit 5000)

```text
Workload: C quota contention
Profile: contention
Duration: 15 s
Rate/concurrency: constantConcurrentUsers(8) closed

Requests: 2709
Success: 2709
Business rejected: 0
Technical failed: 0

Throughput: 169.31 req/s
p50: 39 ms
p95: 75 ms
p99: 99 ms
max: 161 ms

CPU: process_cpu_usage ~0.004 at idle scrape after the run (not peak)
Heap/GC: JFR GCPhasePause samples ~2–17 ms (not a full pause distribution study)
DB pool: still pending=0 timeouts=0 after scrape; acquire max ~2 ms
Lock: JFR JavaMonitorEnter count=2 over 730 s — Java monitors were not the story; PostgreSQL row updates serialize quota

Correctness (SQL): quota_contention consumed=2709 = ACCEPTED 2709; configured_limit=5000; consumed <= limit
```

Limit was not exhausted (2709 < 5000). This measured **concurrency latency**, not rejection-at-cap. p95 rose vs open 5 req/s (21 ms → 75 ms).

## Combined usage-pipeline Micrometer (after all usage runs)

```text
hikaricp_connections_max=10 idle=10 active=0 pending=0 timeout_total=0
usagecore_outbox_publish_total{result=success}=3189
usagecore_usage_events_processed_total{result=applied}=3189
usagecore_quota_decisions_total{decision=ACCEPTED,reason=WITHIN_QUOTA}=2829
usagecore_usage_events_processed_total{result=rejected_invalid}=1
```

3189 applied = 360 event ingestions + 120 consume(`scheduled_export`) + 2709 consume(`quota_contention`). The single `rejected_invalid` was not mapped to a lab fixture event in this session.

## Saturation

At documented local rates (5–15 open rps; 8 closed consume users ≈ 169 rps) the knee was **not** a Hikari timeout, 5xx wave, or GC collapse. Consume closed-model p95 increased (serialization on `quota_state`). Entitlement/events open 5 rps stayed ~10–25 ms p99.

A higher ingest rate than this laptop+Docker lab was **not** pushed to publisher/consumer imbalance beyond peak PENDING=38 at ~30 rps.

## Optimizations

No production code/index/Hikari change. See [database-analysis.md](database-analysis.md).
