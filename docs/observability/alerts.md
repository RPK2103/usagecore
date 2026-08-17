# Operational alerts (Phase 9B)

Alerts are **engineering / demo defaults**. They are not production SLO commitments and are not routed to PagerDuty, Slack, or email. Alertmanager notification integrations are out of scope.

Prometheus evaluates rules locally. Grafana visualizes. Runbooks tell an operator what to inspect. **No automatic remediation.**

## Severity

| Label | Meaning |
| --- | --- |
| `critical` | Workload scrape is down (`UsageCoreWorkloadDown`) |
| `warning` | Degraded delivery, quality, or pressure; investigate using the mapped runbook |

## Demo thresholds

Production thresholds must be calibrated from observed baselines and business SLOs.

| Alert | Condition (demo) | `for` | Runbook |
| --- | --- | --- | --- |
| `UsageCoreWorkloadDown` | `up{job=~"usagecore-.*"} == 0` | 1m | [workload-unavailable.md](runbooks/workload-unavailable.md) |
| `HighHttpServerErrorRate` | `usagecore:http:server_error_ratio > 0.05` | 5m | [workload-unavailable.md](runbooks/workload-unavailable.md) |
| `OutboxDeliveryDelayed` | oldest PENDING age `> 120s` | 2m | [outbox-delivery-delay.md](runbooks/outbox-delivery-delay.md) |
| `OutboxPublishFailures` | `increase(usagecore_outbox_publish_total{result="failure"}[5m]) > 0` | 2m | [outbox-delivery-delay.md](runbooks/outbox-delivery-delay.md) |
| `UsageDlqDetected` | `increase(usagecore_usage_dlq_total[5m]) > 0` | 1m | [usage-dlq.md](runbooks/usage-dlq.md) |
| `ReconciliationMismatchDetected` | mismatch runs `increase` over 15m `> 0` | (none) | [reconciliation-mismatch.md](runbooks/reconciliation-mismatch.md) |
| `ReconciliationFailureDetected` | failed runs `increase` over 15m `> 0` | (none) | [reconciliation-mismatch.md](runbooks/reconciliation-mismatch.md) |
| `CommercialExceptionsAccumulating` | unresolved exceptions `> 0` | 10m | [commercial-exception-backlog.md](runbooks/commercial-exception-backlog.md) |
| `DatabaseConnectionPressure` | `hikaricp_connections_pending > 0` | 2m | [database-connection-pressure.md](runbooks/database-connection-pressure.md) |

Pending outbox **count** is not an alert by itself. Oldest pending **age** is the delivery-lag signal.

## Labels

Allowed: `severity`, `application` (from the time series), `alertname`, `job`.

Forbidden on alerts and dashboard grouping: `tenantId`, `eventId`, `correlationId`, `idempotencyKey`, `contractId`, `commercialPeriodId`, `reconciliationRunId`, `adjustmentId`, `principalId`, `meterKey`.

## Recording rules

Defined in `infrastructure/observability/prometheus/rules/recording.yml`:

- `usagecore:http:server_error_ratio`
- `usagecore:http:client_error_ratio`
- `usagecore:http:availability_ratio`
- `usagecore:http:p95_latency_seconds`
- `usagecore:outbox:pending_age_seconds`
- `usagecore:hikari:pool_utilization`

HTTP ratios exclude `uri=~/actuator.*`. Percentiles use `histogram_quantile` on buckets, not `_sum/_count`.

## SLO-style operating indicators

These are **operational indicators**, not guaranteed production SLOs:

| Indicator | Signal |
| --- | --- |
| Availability | HTTP successful-response ratio (`usagecore:http:availability_ratio`) |
| Ingestion delivery health | Oldest PENDING outbox age |
| Processing quality | DLQ increase |
| Commercial correctness | Reconciliation mismatch runs/items |
| Commercial backlog | Unresolved commercial usage exceptions |
| Database pressure | Hikari pending / pool utilization |
