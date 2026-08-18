# Performance laboratory (Phase 11)

This is a **repeatable local measurement lab**, not a production capacity certification.

```text
MEASURE → LOCATE BOTTLENECK → FORM HYPOTHESIS → CHANGE ONE THING → MEASURE AGAIN → KEEP ONLY PROVEN IMPROVEMENTS
```

Headline numbers in this directory describe **one documented developer machine + local Docker**. They do not mean UsageCore “handles N TPS in production.”

## What is measured separately

| Workload | Endpoint | What a success means |
| --- | --- | --- |
| A — entitlement check | `POST /api/v1/entitlements/check` | HTTP 200 commercial decision. Appends `entitlement_decision`. Does not consume quota. |
| B — durable ingestion | `POST /api/v1/usage/events` | HTTP 202 = PostgreSQL accepted (`usage_ingestion` + `outbox_event` PENDING). Not Kafka/ledger completion. |
| C — strict consume | `POST /api/v1/usage/consume` | HTTP 200 commercial `ACCEPTED` or `REJECTED`. PostgreSQL is quota authority. |

Do not average A/B/C into one “UsageCore latency.”

HTTP acceptance (B) and asynchronous drain (outbox → Kafka → consumer → ledger) are **two measurements**.

## Layout

| Path | Role |
| --- | --- |
| [`performance/`](../../performance/) | Gatling module, seed/verify/EXPLAIN tools, scripts |
| [`methodology.md`](methodology.md) | Profiles, auth, coordinated omission, how to override knobs |
| [`summary.md`](summary.md) | Portfolio-friendly measured results + limitations |
| [`baseline-results.md`](baseline-results.md) | Recorded local runs with environment context |
| [`profiling-guide.md`](profiling-guide.md) | JFR / jcmd / Micrometer |
| [`database-analysis.md`](database-analysis.md) | EXPLAIN ANALYZE summaries |
| [ADR-020](../adr/ADR-020-performance-engineering-and-benchmark-methodology.md) | Why this lab is shaped this way |

Gatling 3.15 on this module overrides Spring Boot’s Netty 4.1 BOM with **Netty 4.2.14** (`netty.version` in `performance/pom.xml`) because Gatling requires `io.netty.channel.IoHandle`. That override is **performance-module only** and does not change application runtimes.


## Prerequisites

1. Java 21 JDK (`jps` / `jcmd` / `jfr` available)
2. Docker Compose stack from [`infrastructure/docker/docker-compose.yml`](../../infrastructure/docker/docker-compose.yml)
3. Control Plane (Flyway owner), Entitlement Runtime, Usage Pipeline started against that PostgreSQL/Kafka/Keycloak
4. Seeded performance dataset (below)

Do not disable Spring Security. Do not point this lab at H2.

## Start the stack

```powershell
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

Do **not** use `.\mvnw.cmd -pl applications/<app> -am spring-boot:run` from the repo root: `-am` includes the parent POM, which has no main class. Install once, then run the three JARs (three terminals):

```powershell
.\mvnw.cmd -pl applications/control-plane,applications/entitlement-runtime,applications/usage-pipeline -am install -DskipTests

java -jar applications/control-plane/target/control-plane-0.1.0-SNAPSHOT.jar
java -jar applications/entitlement-runtime/target/entitlement-runtime-0.1.0-SNAPSHOT.jar
java -jar applications/usage-pipeline/target/usage-pipeline-0.1.0-SNAPSHOT.jar
```

JFR launch (optional, Usage Pipeline example):

```powershell
java "-XX:StartFlightRecording=name=lab,filename=performance/target/jfr/usage-pipeline.jfr,dumponexit=true,settings=profile,maxsize=64m" -jar applications/usage-pipeline/target/usage-pipeline-0.1.0-SNAPSHOT.jar
```

## Seed

Creates tenant `11111111-1111-1111-1111-111111111111` (`acme`) to match the local Keycloak placeholder, DataPilot catalogue, activated contract, OPEN commercial period, and filler tenants for slightly less trivial plans.

```powershell
.\performance\scripts\seed.ps1
# or
.\mvnw.cmd -pl performance exec:java "-Dexec.mainClass=io.usagecore.performance.seed.PerformanceDatasetSeeder"
```

`--reset-quota` (script `-ResetQuota`) deletes `quota_state` for the lab tenant between consume runs. It does **not** rewrite activated entitlements.

## Authentication

One Keycloak password-grant for `acme-developer` is fetched **once per Gatling JVM** and reused as `Authorization: Bearer …`.

Included in application measurements: JWT signature/claims validation on the resource server (JWKS is cached after the first use).

**Not** included: Keycloak token endpoint time. Override with `-Dusagecore.perf.token=...` if you already hold a token.

## Run benchmarks

```powershell
.\performance\scripts\run-smoke.ps1

.\performance\scripts\run-benchmark.ps1 -Workload entitlement -Profile baseline
.\performance\scripts\run-benchmark.ps1 -Workload events -Profile baseline
.\performance\scripts\run-benchmark.ps1 -Workload consume -Profile baseline
.\performance\scripts\run-benchmark.ps1 -Workload burst -Profile burst

# Strict quota contention (closed model, meter quota_contention, limit 5000)
.\performance\scripts\seed.ps1 -ResetQuota
.\performance\scripts\run-benchmark.ps1 -Workload consume -Profile contention
.\performance\scripts\verify-correctness.ps1 -Mode quota
```

Equivalent Maven (no warmup unless you run it yourself):

```powershell
.\mvnw.cmd -pl performance gatling:test `
  -Dgatling.simulationClass=io.usagecore.performance.gatling.EntitlementCheckSimulation `
  -Dusagecore.perf.profile=smoke
```

HTML reports: `performance/target/gatling/` (gitignored). Summarize with:

```powershell
.\mvnw.cmd -pl performance exec:java "-Dexec.mainClass=io.usagecore.performance.report.GatlingReportSummarizer" "-Dexec.args=performance/target/gatling"
```

## After usage ingest / burst

```powershell
.\performance\scripts\verify-correctness.ps1 -Mode drain
.\performance\scripts\verify-correctness.ps1 -Mode ingestion
```

For tenant-scoped COUNT consume:

```powershell
.\performance\scripts\verify-correctness.ps1 -Mode quota
```

## Observability and plans

Reuse Phase 9B Grafana (Platform Overview + Usage Delivery). Do not treat dashboards as correctness.

```powershell
.\performance\scripts\capture-environment.ps1
.\performance\scripts\capture-metrics.ps1
.\performance\scripts\capture-metrics.ps1 -Url http://localhost:8082/actuator/prometheus
.\performance\scripts\explain.ps1
```

JFR: [`profiling-guide.md`](profiling-guide.md). Do not commit `.jfr` binaries.

## Configurable knobs

All `usagecore.perf.*` system properties (or `USAGECORE_PERF_*` env vars). See [`methodology.md`](methodology.md).

## What this lab will not do

- CI p95 gates
- Redis / in-memory commercial truth
- Weakened Kafka acks, fsync, or inbox dedupe
- Claiming production TPS from a laptop + Docker Desktop
