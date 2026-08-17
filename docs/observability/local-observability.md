# Local observability (Phase 9B)

Applications normally run on the host (IDE / Maven). Prometheus, Grafana, and the OpenTelemetry Collector run in Docker.

## Start infrastructure

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

| Service | URL / port |
| --- | --- |
| PostgreSQL | `localhost:5432` |
| Keycloak | `http://localhost:8081` |
| Kafka | `localhost:9092` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` (local/demo `admin` / `admin`) |
| OTel Collector OTLP HTTP | `http://localhost:4318` |
| OTel Collector OTLP gRPC | `localhost:4317` |

Do not treat listed Prometheus targets as healthy unless the three applications are actually running. Grafana datasource provisioning does not require creating Prometheus in the UI.

Provisioned dashboards (folder UsageCore):

- UsageCore — Platform Overview
- UsageCore — Usage Delivery & Commercial Enforcement
- UsageCore — Reconciliation & Data Correctness

Alerts: [alerts.md](alerts.md). Runbooks: [runbooks/](runbooks/). ADR: [ADR-018](../adr/ADR-018-operational-dashboards-and-alerting.md).

Prometheus `up` is scrape reachability. Actuator health is dependency health. Business metrics are domain behavior. They are not interchangeable.

## Run applications with JSON logs + OTLP export

```bash
# Windows
$env:SPRING_PROFILES_ACTIVE="local"
.\mvnw.cmd -pl applications/control-plane -am spring-boot:run
.\mvnw.cmd -pl applications/entitlement-runtime -am spring-boot:run
.\mvnw.cmd -pl applications/usage-pipeline -am spring-boot:run
```

`local` profile:

- structured Logstash JSON on stdout (MDC includes `correlationId`; Micrometer adds `traceId` / `spanId`)
- `management.tracing.export.otlp.enabled=true` unless `USAGECORE_OTLP_ENABLED=false`
- OTLP endpoint `USAGECORE_OTLP_ENDPOINT` (default `http://localhost:4318/v1/traces`)

Tests and the default profile keep OTLP export **disabled** so unit/integration tests do not require a collector.

Spring Boot also disables tracing and Prometheus metrics export in `@SpringBootTest` unless the test opts in with `@AutoConfigureObservability`. Observability integration tests do that so `/actuator/prometheus` and W3C Kafka headers can be asserted. Other tests keep export disabled so Kafka consumer-group context caching stays stable. Production/default profile still enables Prometheus scrape export (`management.prometheus.metrics.export.enabled=true`) and in-process tracing; OTLP export stays off until `USAGECORE_OTLP_ENABLED=true` or the `local` profile.

## Actuator (local)

| Path | Auth | Notes |
| --- | --- | --- |
| `/actuator/health` | public | Root health; Usage Pipeline does **not** include Kafka |
| `/actuator/health/liveness` | public | Process liveness |
| `/actuator/health/readiness` | public | PostgreSQL (all apps). Usage Pipeline HTTP acceptance does not require Kafka |
| `/actuator/info` | public | Build/app info |
| `/actuator/prometheus` | public (local scrape) | Restrict in real deployments |
| `/actuator/env` and other sensitive endpoints | not exposed | `denyAll` |

`/api/v1/**` still requires a valid JWT.

Prometheus scrapes host apps via `host.docker.internal:8080/8082/8083`. Compose sets `extra_hosts: host.docker.internal:host-gateway` for Linux Docker.

Production guidance: restrict management endpoint exposure by infrastructure/network policy. Do not expose `/env`, `/configprops`, `/beans`, `/heapdump`.

## Environment variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `USAGECORE_OTLP_ENABLED` | `false` (default profile); `true` on `local` | Enable OTLP trace export |
| `USAGECORE_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP HTTP traces endpoint |
| `USAGECORE_TRACING_SAMPLING_PROBABILITY` | `1.0` | Trace sampling (local/dev) |

If the collector is down, business processing continues. Tracing is best-effort.

## Validate Prometheus rules (from a running Prometheus container)

```bash
docker compose -f infrastructure/docker/docker-compose.yml exec prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose -f infrastructure/docker/docker-compose.yml exec prometheus promtool check rules /etc/prometheus/rules/recording.yml /etc/prometheus/rules/alerts.yml
```

## Readiness reminder (Usage Pipeline)

HTTP `POST /api/v1/usage/events` is durable after PostgreSQL commit of ingestion + outbox. Kafka unavailability must not mark the process unready for HTTP acceptance. Watch `usagecore_outbox_oldest_pending_age_seconds` (delivery lag) separately from HTTP 202 success.

## Known local limitations

- Demo alert thresholds are not production-tuned
- Sampling `1.0` is local/dev
- JSON logging is `local` profile specific
- `/actuator/prometheus` is intentionally open locally
- Metrics may over-count transactions that later roll back (Phase 9A)
- No production notification routing; no automatic remediation
