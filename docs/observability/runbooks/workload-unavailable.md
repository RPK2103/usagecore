# Workload unavailable

## Symptom

Alert `UsageCoreWorkloadDown` (`up{job=~"usagecore-.*"} == 0`) and/or `HighHttpServerErrorRate`, or Platform Overview scrape stats showing DOWN.

## Business impact

**Prometheus `up`** means scrape reachability of `/actuator/prometheus`, not commercial correctness and not necessarily Actuator health.

If Usage Pipeline is down:

- New HTTP ingestion cannot be accepted
- Outbox already in PostgreSQL is not published until the process returns

If only Prometheus cannot reach a healthy process (firewall, wrong port), business traffic may still work.

`HighHttpServerErrorRate` means 5xx ratio on **non-actuator** HTTP exceeded the **demo** threshold (5% for 5 minutes). Clients are failing; commercial data may or may not have committed.

## Evidence to check

| Signal | Meaning |
| --- | --- |
| Prometheus `up` | Scrape reachability |
| `/actuator/health` | Dependency health (PostgreSQL; Usage Pipeline does **not** include Kafka) |
| `/actuator/health/liveness` | Process liveness |
| `/actuator/health/readiness` | Ready for work (DB) |
| Business metrics | Domain behavior if the process is up |

## Likely causes

- Application not started on 8080 / 8082 / 8083
- Process crash or OOM
- Host/Docker `host.docker.internal` routing issue (scrape DOWN while the app is fine on the host)
- PostgreSQL down → readiness fail; HTTP APIs may 5xx
- Application bugs causing 5xx

## Safe investigation

1. `GET http://localhost:8080|8082|8083/actuator/health` from the host.
2. Prometheus targets API: `http://localhost:9090/api/v1/targets`.
3. Distinguish scrape DOWN (Prometheus cannot reach) from health DOWN (app reports DB failure).
4. For 5xx: logs + `correlationId` / `traceId`; do not group Prometheus by raw UUID paths.

## Do not

- Open `/actuator/env`, `/configprops`, `/beans`, or `/heapdump` (not exposed; do not enable for dashboards)
- Weaken JWT on `/api/v1/**` to “make monitoring easier”
- Restart blindly without capturing logs
- Treat Kafka down as Usage Pipeline HTTP-ingestion outage (see outbox runbook)

## Recovery / escalation

Restart the workload using the normal local `spring-boot:run` / process manager after capturing logs. Restore PostgreSQL if readiness is failed. Kafka down is **not** Usage Pipeline HTTP-ingestion outage (see outbox runbook). Full live JVM restart of PENDING outbox work is reasoned from durable `PENDING` rows but was **not** executed as a process-orchestration drill in Phase 10. No automatic remediator exists.
