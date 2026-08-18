# Performance summary (portfolio)

Full numbers and environment block: [baseline-results.md](baseline-results.md).

This is a **local laboratory** on a documented Windows 11 laptop (AMD Ryzen 7 7735HS, ~15.2 GiB RAM, Temurin 21, Docker Desktop) dated **2026-08-18**. It is **not** production, cloud, or hardware-independent capacity.

## What was measured (separately)

| Workload | Endpoint | Headline (baseline, warm-up excluded) |
| --- | --- | --- |
| A entitlement check | `POST /entitlements/check` | 5 req/s open, p50 12 ms / p95 20 ms / p99 24 ms, 100/100 success |
| B durable ingest | `POST /usage/events` HTTP 202 | 5 req/s open, p50 11 ms / p95 17 ms / p99 25 ms — **acceptance only**, not ledger drain |
| C strict consume | `POST /usage/consume` | 5 req/s open, p50 16 ms / p95 21 ms / p99 51 ms; SQL consumed matched ACCEPTED |

Burst ingest (~30 req/s, 8 s, 240/240 HTTP 202): peak PENDING 38; outbox drained in ~522 ms; **consumer lagged** until `ingestion = processed = ledger`. PENDING=0 is not completion.

Quota contention (closed, 8 users, limit 5000): 2709 ACCEPTED, 169 req/s, p95 75 ms. Limit was **not** exhausted; this measured contention latency, not rejection-at-cap. JFR monitors were not the story; PostgreSQL row updates serialize quota.

## Saturation

At documented local rates there was no Hikari timeout wave, 5xx collapse, or GC collapse. Consume p95 increased under closed-model contention. Ingest was not pushed past a publisher/consumer imbalance beyond the burst observation above.

## Optimization outcome

**No production code, index, or pool change was justified.** That is an engineering result, not a missing step.

Do not cherry-pick 169 req/s as “UsageCore throughput.” Always pair a number with workload, profile, and this laptop+Docker environment.
