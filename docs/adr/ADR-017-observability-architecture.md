# ADR-017: Observability architecture

## Status

Accepted (Phase 9A)

## Context

Phases 0–8 established commercial correctness in PostgreSQL (constraints, transactions, idempotency, locks, canonical evidence) and at-least-once Kafka transport via transactional outbox + consumer inbox.

Operators still could not answer:

- Is the system healthy?
- Where is latency occurring?
- Is the outbox accumulating?
- Is Kafka processing succeeding, including duplicates?
- Are quota requests being rejected?
- Are commercial-period exceptions and reconciliation mismatches accumulating?
- Can one HTTP request be correlated with its outbox row and downstream Kafka processing?

Observability must describe existing behavior. It must never become commercial correctness authority, and exporter failure must not roll back business transactions.

## Decision

### Stack

| Concern | Choice |
| --- | --- |
| Metrics | Micrometer + Prometheus registry + Spring Boot Actuator |
| Tracing | Micrometer Tracing bridge to OpenTelemetry, W3C Trace Context |
| Export | OTLP HTTP to a local OpenTelemetry Collector (best-effort) |
| Logs | Console pattern with MDC; JSON (Logstash) on `local` profile |
| Local scrape | Prometheus in Docker Compose |
| Dashboards | Deferred to Phase 9B (Grafana) |

No vendor APM SDK (Datadog, New Relic, Splunk, Elastic APM, Jaeger/Zipkin application SDKs).

### correlationId vs traceId

`correlationId` is application/business request correlation (`X-Correlation-Id`). Missing incoming values are generated. It is diagnostic context — not tenant identity, authentication, or idempotency.

`traceId` is OpenTelemetry distributed-trace identity. They may differ. Logs expose both when present.

`EventEnvelope.traceId` stores W3C `traceparent` when captured at durable acceptance (otherwise a hex trace id). Kafka records additionally carry standard W3C `traceparent` via Spring Kafka observation.

### HTTP → outbox → Kafka → consumer

1. Incoming HTTP creates a server span (framework instrumentation).
2. Durable acceptance writes `correlationId` + trace evidence into the outbox envelope in the same PostgreSQL transaction.
3. The HTTP span ends when the request returns. The outbox publisher later starts `usage.outbox.publish` by extracting the stored W3C context. This is an asynchronous continuation (span parent/link across a delay), not one synchronous span stretched across hours.
4. Spring Kafka observation injects W3C headers on produce and restores context on consume.
5. The consumer listener restores MDC `correlationId` / `eventId` / `tenantId` for the processing thread and clears them afterwards.

### Metric cardinality

Custom Prometheus labels are bounded enums only (`result`, `reason`, `decision`, `aggregationType`, `from`, `to`, `type`, plus common tag `application`).

Never label by `tenantId`, `eventId`, `correlationId`, `idempotencyKey`, `contractId`, `principalId`, `commercialPeriodId`, `reconciliationRunId`, `adjustmentId`, or `meterKey`. Those belong in logs/traces.

### Actuator exposure (local portfolio)

Exposed: `health`, `info`, `prometheus` (`management.prometheus.metrics.export.enabled=true`).

Not exposed: `env`, `configprops`, `beans`, `heapdump`.

`/api/v1/**` remains JWT-authenticated. Local Prometheus scrapes `/actuator/prometheus` without JWT; production would restrict that path by network policy. `/error` is permitted so Spring Boot error dispatch is not turned into a 401 by `denyAll`.

Spring Boot `@SpringBootTest` disables tracing and metrics export unless the test uses `@AutoConfigureObservability`. Observability integration tests opt in so Prometheus scrape and Kafka W3C headers can be asserted without requiring an OTLP collector. Other tests keep Spring Boot's default (no export) so they do not change Kafka consumer-group context caching.

### Readiness

| Application | Readiness depends on |
| --- | --- |
| Control Plane | PostgreSQL |
| Entitlement Runtime | PostgreSQL |
| Usage Pipeline | PostgreSQL only |

Usage Pipeline HTTP acceptance is durable via PostgreSQL/outbox (Phase 5A). Kafka down is degraded async delivery, not HTTP-ingestion unavailability. The Kafka health indicator is disabled so it cannot flip readiness or root `/actuator/health`. Kafka health is observed via outbox pending gauges and processing counters.

PostgreSQL failure does make readiness fail.

### Failure isolation

- Prometheus unavailable: no effect on business processing.
- OTel Collector unavailable: traces may drop; `management.tracing.export.otlp.enabled` defaults to `false` outside `local`.
- Metric/log failures are swallowed at instrumentation boundaries and must not roll back commercial transactions.
- Gauge queries run on scrape, not on every business operation, and return `NaN` on query failure.

### Known limitations

- Grafana dashboards, alert rules, and SLO-style views: [ADR-018](ADR-018-operational-dashboards-and-alerting.md) (Phase 9B).
- No claim of zero trace loss, complete outage detection, or production observability.
- JDBC SQL spans are not added; Hikari pool metrics are exposed.
- Live Kafka-down / PostgreSQL-down chaos is Phase 10.

## Consequences

All three workloads share Actuator + Micrometer + OTel tracing configuration. Custom business metrics live in application-layer facades; domain packages stay free of Micrometer/OTel/MDC. Phase 9B adds Grafana against the same Prometheus/OTLP pipeline without changing commercial semantics ([ADR-018](ADR-018-operational-dashboards-and-alerting.md)).
