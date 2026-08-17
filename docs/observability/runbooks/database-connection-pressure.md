# Database connection pressure

## Symptom

Alert `DatabaseConnectionPressure` or Grafana **Platform Overview** Hikari panels: `hikaricp_connections_pending > 0` sustained, or pool utilization near 1.

## Business impact

Threads are waiting for a connection from the pool. Latency rises; readiness may still be up until connections fail outright.

PostgreSQL is the transactional source of truth. Exhausted pools can delay **durable** HTTP acceptance (Usage Pipeline 202 depends on PostgreSQL commit), entitlement checks, and Control Plane writes.

This is **not** the same as Prometheus scrape failure (`up`) or Kafka delivery delay.

## Evidence to check

| Source | What |
| --- | --- |
| Grafana | Platform Overview: active / idle / pending / utilization |
| Metrics | `hikaricp_connections_pending`, `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_max` |
| Logs | Connection timeouts, Hikari warnings |
| Actuator | `/actuator/health/readiness` (DB) — may still be UP while pending > 0 |
| Postgres | Local `pg_stat_activity` (read-only) |

## Likely causes

- Slow queries holding connections
- Pool `maximum-pool-size` too small for concurrent HTTP + Kafka consumer + outbox publisher
- PostgreSQL unavailable or saturated
- Connection leak (less common; watch active never returning)

## Safe investigation

1. Identify which `application` label is pending (bounded).
2. Compare active vs max vs pending over time.
3. Check readiness and application logs for SQL timeouts.
4. Inspect long-running statements in PostgreSQL if you have local access.

## Do not

- Increase pool size blindly in production without measuring
- Restart PostgreSQL to “clear” metrics in a shared local volume without understanding in-flight transactions
- Expose `/actuator/heapdump` or `/env` to debug this
- Disable transactions or unique constraints

## Recovery / escalation

Reduce load, fix the slow query, or restore PostgreSQL. Phase 10 may include connection-exhaustion drills. No automatic pool reset or query killer is provided.
