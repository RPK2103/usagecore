# ADR-020: Performance engineering and benchmark methodology

## Status

Accepted (Phase 11)

## Context

Phases 1–10 established commercial correctness, durable ingestion, at-least-once Kafka, quota authority in PostgreSQL, observability, and selected failure-recovery proofs. None of that measured **how the system behaves under load** or which resource saturates first.

Phase 11 needs a laboratory another engineer can rerun. Local Docker on a developer workstation is not a production topology: one PostgreSQL, one Kafka, no LB, noisy neighbors, JIT, and shared CPU with Compose.

## Decision

### Gatling

Gatling 3.15 (Java DSL) lives in the `performance` Maven module. It is **not** bound to `verify`. Ordinary `clean verify` must not start long load tests. A JUnit harness smoke test only checks configuration/class loading.

Gatling is used because it is open source, Maven-native, records p50/p95/p99, and supports **open** (arrival-rate) and **closed** (concurrency) models. Locust/k6 were not added; one load tool is enough.

### Local results are not production capacity

Reports must name hardware, JDK, Compose images, profile, duration, and dataset. Forbidden phrasing: production TPS, “handles millions,” hardware-independent SLOs. CI must not gate on `p95 < N ms` without a controlled runner.

### HTTP acceptance vs async completion

`POST /api/v1/usage/events` HTTP 202 means PostgreSQL durability of ingestion + outbox PENDING. Load tests of that path must not wait for Kafka/ledger inside the HTTP sample. Drain, PENDING peak, publisher/consumer rates, and ledger counts are a **second** experiment (burst simulation + SQL verifier).

### Workloads

Measure separately:

1. Entitlement check (decision evidence write included)
2. Durable usage ingest (202)
3. Strict `/usage/consume` (business REJECTED ≠ technical failure)

### Warm-up

JIT, connection pools, and JWKS cache distort first seconds. Warm-up is a distinct profile; scripts run it before measurement and do not mix it into headline percentiles.

### Authentication

Spring Security stays enabled. The lab caches **one** Keycloak access token per Gatling JVM (or `usagecore.perf.token`). Per-request token fetch would measure Keycloak. Tenant id is the documented Acme placeholder, aligned by a **performance-only SQL seed** (same shape as test fixtures). Seed does not rewrite activated entitlements to chase a quota number.

### Profiling

JDK 21 JFR + `jcmd`/`jfr`. Micrometer/Prometheus already exported by Phase 9. No paid APM. No `.jfr` binaries in git.

### Optimization evidence

Change at most one production variable after a measured bottleneck. Keep only improvements that repeat. No Redis, in-memory commercial truth, eventual quota, skipped ledger/inbox, weaker isolation, H2, or durability cheats.

Indexes require EXPLAIN evidence. No index is a valid Phase 11 outcome.

### Cache

No cache in this phase. If immutable catalogue reads later dominate CPU/DB with evidence, document an in-process cache with explicit staleness — still not commercial authority. Redis remains out of scope until a measured requirement exists.

### Outbox / Kafka

Publisher still sends Kafka while holding `FOR UPDATE SKIP LOCKED` rows (Phase 10). Treat long broker RTT as a **documented bottleneck** unless a bounded, correctness-preserving change is measured. No outbox redesign solely for charts.

## Consequences

- Engineers can reproduce smoke/baseline/ramp/sustained/burst/contention against local Compose.
- Interviewers can challenge numbers because each headline traces to a run folder + this ADR’s rules.
- Production sizing, Kubernetes, and CI performance gates remain later phases.
