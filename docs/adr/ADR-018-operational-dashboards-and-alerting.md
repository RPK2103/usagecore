# ADR-018: Operational dashboards and alerting

## Status

Accepted (Phase 9B)

## Context

Phase 9A exported metrics, traces, and structured logs but did not provide an operator loop: visualize, detect, investigate. Engineers still could not answer workload health, HTTP success/latency, outbox delivery lag, DLQ, quota rejections, commercial exceptions, or reconciliation mismatch from a single local environment.

Phase 9B must not become commercial correctness authority, must not add a new deployable application, and must not implement automated remediation or production notification routing.

## Decision

### Grafana

Grafana is the local visualization layer, started from the existing Docker Compose file. Prometheus is provisioned as the datasource (UID `prometheus`). Dashboards are version-controlled JSON under `infrastructure/observability/grafana/dashboards/` and loaded via Grafana dashboard provisioning. Local admin credentials are demo-only (`admin` / `admin`), matching other Compose services. No JWTs or application secrets are stored in Grafana config.

Three dashboards:

1. **UsageCore — Platform Overview** — scrape reachability, HTTP rate/errors/latency, JVM, Hikari
2. **UsageCore — Usage Delivery & Commercial Enforcement** — outbox, processing, DLQ, quota, period transitions, exceptions
3. **UsageCore — Reconciliation & Data Correctness** — run results, mismatch types, exceptions, adjustments

A fourth JVM-only dashboard was not added; JVM/Hikari already sit on Platform Overview.

### Prometheus query model

- Counters: `rate()` / `increase()` (tolerate process restart resets)
- Gauges: raw value (pending outbox, oldest age, unresolved exceptions, Hikari)
- Histograms: `histogram_quantile` on `_bucket`; never `_sum/_count` as p95
- HTTP error ratio uses bounded labels (`application`, `status`, `uri` templates). No grouping by UUID paths
- Recording rules exist only where they simplify alerts/dashboards

### Alert philosophy

Small set (~9) of actionable alerts with `warning` / `critical`, each with `runbook_url` pointing at `docs/observability/runbooks/`. Demo thresholds are documented as engineering defaults. No Alertmanager notification integrations (PagerDuty/Slack/email). No automatic restart, replay, quota mutation, or SQL repair.

### SLO-style indicators

Availability, outbox pending age, DLQ rate, reconciliation mismatch, unresolved exceptions, and Hikari pressure are **operational indicators**. They are not contractual SLOs and do not prove production monitoring readiness.

### Cardinality

Dashboard variables: `application` (and bounded result/status fields in queries). Never Prometheus variables or alert labels for tenant/event/request/run/adjustment ids.

### Runbooks

One concise runbook per operational symptom. They describe investigation and **do not** instruct operators to rewrite canonical evidence. Outbox runbook explicitly separates durable HTTP 202 ingestion from Kafka delivery delay.

### Security

Phase 9A actuator exposure is unchanged. Local `/actuator/prometheus` remains scrape-accessible. Production must restrict management endpoints by network policy. Sensitive actuators stay unexposed.

## Live evidence policy

Prometheus scrape of the three applications, Grafana datasource health, OTLP collector span receipt, and alert FIRING are reported only when actually exercised. Configuration validity (`promtool`, Compose, Maven tests) is not a substitute for live target UP.

## Alternatives considered

- **Vendor APM dashboards** — rejected; no Datadog/New Relic SDK, and Phase 9A already standardized on Prometheus/OTLP.
- **One dashboard per Java class / meter** — rejected; panels must support an operational decision, not decorate every metric.
- **Alertmanager + Slack/PagerDuty** — deferred; Phase 9B is detection and runbooks, not notification routing.
- **Tempo / Loki** — not required to visualize Prometheus metrics or to prove Collector receipt via the debug exporter.

## Trade-offs and failure semantics

- Demo alert thresholds will fire in a quiet local lab (especially `UsageCoreWorkloadDown` when host apps are not running). That is expected, not production calibration.
- Recording-rule HTTP ratios use `clamp_min(..., 1e-9)` to avoid divide-by-zero; with no traffic the availability indicator can read as ~1.0. Treat as an engineering indicator, not proof of uptime.
- Grafana/Prometheus/Collector failure must not affect PostgreSQL commercial transactions (unchanged from ADR-017).
- Alerts never remediate. Crash of Grafana or Prometheus loses visibility, not usage evidence.

## Known limitations

- Live scrape of the three host applications was not always exercised in the same session as Grafana provisioning.
- No production SLO commitments, no guaranteed alert delivery, no capacity planning.
- JVM GC panel names follow Micrometer’s usual `jvm_gc_pause_seconds_*` export; empty panels mean inspect `/actuator/prometheus`, not fabricate percentiles.

## Consequences

Operators can observe → visualize → detect → investigate in the local stack. Phase 10 remains the place for resilience drills, failure injection, and any future recovery tooling. Dashboards never override PostgreSQL commercial evidence.
