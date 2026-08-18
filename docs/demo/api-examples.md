# Flagship API examples

Tenant identity is never in the body, header, or (for usage reads) path as authorization evidence. It comes from the validated JWT `tenant_id` claim.

Unknown JSON fields are rejected (`fail-on-unknown-properties: true`).

IDs below are representative. Do not treat them as fixtures in a live database.

## POST `/api/v1/entitlements/check`

**Workload:** Entitlement Runtime (`:8082`)  
**Auth:** tenant-bound `DEVELOPER`, `TENANT_ADMIN`, or `CONTRACT_MANAGER`  
**Meaning:** read-only commercial decision against the activated snapshot. Does **not** consume quota.

### Request

```json
{
  "productKey": "datapilot-cloud",
  "featureKey": "scheduled_exports",
  "requestedUnits": 1
}
```

### Response (HTTP 200)

```json
{
  "decisionId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "decision": "ALLOW_WITH_LIMIT",
  "reason": "ENTITLEMENT_LIMITED",
  "productKey": "datapilot-cloud",
  "featureKey": "scheduled_exports",
  "requestedUnits": 1,
  "configuredLimit": 1000000,
  "contractVersion": 1,
  "evaluatedAt": "2026-08-18T14:30:00Z",
  "correlationId": "demo-check-1"
}
```

`decision` is `ALLOW`, `DENY`, or `ALLOW_WITH_LIMIT`. Commercial DENY is still HTTP 200.

`configuredLimit` is contractual configuration. There is no `remainingQuota` on this API.

A row is appended to `entitlement_decision`. That is audit evidence, not metering.

## POST `/api/v1/usage/events`

**Workload:** Usage Pipeline (`:8083`)  
**Auth:** tenant-bound `DEVELOPER`  
**Meaning:** asynchronous metering. HTTP **202** = durably accepted into PostgreSQL (`usage_ingestion` + `outbox_event` PENDING).

### Request

```json
{
  "productKey": "datapilot-cloud",
  "meterKey": "scheduled_export",
  "quantity": 1,
  "occurredAt": "2026-08-18T14:30:00Z",
  "idempotencyKey": "export-job-174"
}
```

### Response (HTTP 202)

```json
{
  "eventId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
  "status": "ACCEPTED",
  "correlationId": "demo-export-1",
  "idempotentReplay": false
}
```

**202 does not mean:** Kafka published, consumer processed, aggregates updated, quota changed, or billing occurred.

Same key + same payload → 202 with `idempotentReplay: true`.  
Same key + different payload → HTTP **409**.

## POST `/api/v1/usage/consume`

**Workload:** Usage Pipeline (`:8083`)  
**Auth:** tenant-bound `DEVELOPER`  
**Meaning:** synchronous strict quota admission. PostgreSQL `quota_state` is concurrency authority.

### Request

```json
{
  "productKey": "datapilot-cloud",
  "meterKey": "scheduled_export",
  "quantity": 1,
  "occurredAt": "2026-08-18T14:31:00Z",
  "idempotencyKey": "export-admit-174"
}
```

### Response (HTTP 200, accepted)

```json
{
  "consumptionId": "cccccccc-cccc-cccc-cccc-cccccccccccc",
  "eventId": "dddddddd-dddd-dddd-dddd-dddddddddddd",
  "decision": "ACCEPTED",
  "reason": "WITHIN_QUOTA",
  "productKey": "datapilot-cloud",
  "meterKey": "scheduled_export",
  "featureKey": "scheduled_exports",
  "quantity": 1,
  "configuredLimit": 1000000,
  "consumed": 1,
  "remaining": 999999,
  "contractVersionNumber": 1,
  "correlationId": "demo-consume-1",
  "idempotentReplay": false
}
```

Commercial `REJECTED` is also HTTP **200** (for example `QUOTA_EXHAUSTED`). That is a business outcome, not a transport failure.

This API **does** consume quota when `ACCEPTED`. `/usage/events` does not. `/entitlements/check` does not.

CLOSING / RECONCILING / FINALIZED periods reject consume. Async `/events` may still 202 and later quarantine.

## Related admin APIs (not flagship)

| API | Owner | Role |
| --- | --- | --- |
| Commercial period transitions | Control Plane | `PLATFORM_ADMIN` / `BILLING_OPERATOR` |
| `POST /api/v1/reconciliation/periods/{id}/runs` | Usage Pipeline | Rebuild/compare/report |
| `POST /api/v1/reconciliation/runs/{runId}/exceptions/{exceptionId}/adjustments` | Usage Pipeline | Explicit `APPLY_QUARANTINED_USAGE` |

There is no SpringDoc/OpenAPI dependency in this repository. These examples plus controllers are the API contract.
