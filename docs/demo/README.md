# Evaluator demo (10–15 minutes)

Reproducible local walkthrough against Docker Compose + host-run JARs. This is not a Kubernetes or AWS demo.

Commands assume **PowerShell** from the repository root on Windows. Maven/Docker concepts are the same on other OS (use `./mvnw` and `curl` there).

Do not paste live JWTs into git. Tokens below are obtained at runtime and discarded.

Commands were checked against the current Compose file, Keycloak realm, performance seeder, smoke script, and HTTP controllers. This pass did not re-execute the live walkthrough.

## Demo catalogue (stable)

The performance seeder aligns Acme with the local Keycloak placeholder tenant so JWT tenancy stays intact.

| Entity | Value |
| --- | --- |
| Product | `datapilot-cloud` (DataPilot Cloud) |
| Tenant | `acme` / `11111111-1111-1111-1111-111111111111` |
| Second tenant (Keycloak) | `globex` / `22222222-2222-2222-2222-222222222222` (isolation contrast; not required for the happy path) |
| Features | `api_access`, `scheduled_exports`, `quota_contention` |
| Meters | `api_requests` (SUM), `scheduled_export` (COUNT), `quota_contention` (COUNT) |
| Local user | `acme-developer` / `acme-developer` (`DEVELOPER`) |
| Client | `usagecore-control-plane` (public password-grant, local only) |

Test fixtures also use `workspace` / `workspace_size` (MAX). The live demo path uses the performance seeder, not those extra keys.

## Prerequisites

- Java 21 JDK
- Docker Desktop running
- Ports free: `5432`, `8080`–`8083`, `8081`, `9090`, `9092`, `3000`, `4318`

## 1. Start infrastructure

```powershell
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

Wait until Keycloak (`http://localhost:8081`) and PostgreSQL accept connections. First Keycloak import can take ~30–60s.

## 2. Build and start the three workloads

```powershell
.\mvnw.cmd -pl applications/control-plane,applications/entitlement-runtime,applications/usage-pipeline -am install -DskipTests
```

Three terminals:

```powershell
java -jar applications/control-plane/target/control-plane-0.1.0-SNAPSHOT.jar
java -jar applications/entitlement-runtime/target/entitlement-runtime-0.1.0-SNAPSHOT.jar
java -jar applications/usage-pipeline/target/usage-pipeline-0.1.0-SNAPSHOT.jar
```

Health checks:

```powershell
curl.exe -s http://localhost:8080/actuator/health
curl.exe -s http://localhost:8082/actuator/health
curl.exe -s http://localhost:8083/actuator/health
```

Control Plane is the Flyway owner. Start it first against an empty database.

Do not run `.\mvnw.cmd -pl applications/<app> -am spring-boot:run` from the repo root: `-am` includes the parent POM, which has no main class.

## 3. Seed

```powershell
.\performance\scripts\seed.ps1
```

Creates the Acme placeholder tenant, DataPilot catalogue, activated contract, OPEN commercial period `[2026-01-01, 2027-01-01)`, and filler tenants.

## 4. Authenticate

```powershell
$token = (Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/realms/usagecore/protocol/openid-connect/token" `
  -ContentType "application/x-www-form-urlencoded" `
  -Body @{
    client_id = "usagecore-control-plane"
    username = "acme-developer"
    password = "acme-developer"
    grant_type = "password"
  }).access_token
```

Equivalent `curl.exe` (parse `access_token` yourself; do not commit the result):

```text
curl.exe -s -X POST "http://localhost:8081/realms/usagecore/protocol/openid-connect/token" ^
  -H "Content-Type: application/x-www-form-urlencoded" ^
  -d "client_id=usagecore-control-plane" ^
  -d "username=acme-developer" ^
  -d "password=acme-developer" ^
  -d "grant_type=password"
```

## 5. Check entitlement (read-only)

```powershell
curl.exe -s -X POST http://localhost:8082/api/v1/entitlements/check `
  -H "Authorization: Bearer $token" `
  -H "Content-Type: application/json" `
  -H "X-Correlation-Id: demo-check-1" `
  -d "{\"productKey\":\"datapilot-cloud\",\"featureKey\":\"scheduled_exports\",\"requestedUnits\":1}"
```

Expect HTTP **200** with `decision` `ALLOW_WITH_LIMIT` (LIMITED feature). This does **not** consume quota.

## 6. Submit async usage (durable acceptance)

Use a unique `idempotencyKey` each new logical event.

```powershell
$idem = "demo-export-" + [guid]::NewGuid().ToString()
curl.exe -s -X POST http://localhost:8083/api/v1/usage/events `
  -H "Authorization: Bearer $token" `
  -H "Content-Type: application/json" `
  -H "X-Correlation-Id: demo-export-1" `
  -d "{\"productKey\":\"datapilot-cloud\",\"meterKey\":\"scheduled_export\",\"quantity\":1,\"occurredAt\":\"2026-08-18T14:30:00Z\",\"idempotencyKey\":\"$idem\"}"
```

Expect HTTP **202** and `"status":"ACCEPTED"`. That means `usage_ingestion` + PENDING `outbox_event` committed. It does **not** mean Kafka, ledger, or quota finished.

## 7. Observe durable acceptance → ledger

Wait a few seconds, then:

```powershell
docker exec usagecore-postgres psql -U usagecore -d usagecore -c "SELECT status, count(*) FROM outbox_event GROUP BY status;"
docker exec usagecore-postgres psql -U usagecore -d usagecore -c "SELECT count(*) AS ingestion FROM usage_ingestion;"
docker exec usagecore-postgres psql -U usagecore -d usagecore -c "SELECT count(*) AS processed FROM processed_event;"
docker exec usagecore-postgres psql -U usagecore -d usagecore -c "SELECT count(*) AS ledger FROM usage_ledger;"
```

HTTP aggregate (derived reporting, not quota authority):

```powershell
curl.exe -s http://localhost:8083/api/v1/usage/aggregates/datapilot-cloud/scheduled_export `
  -H "Authorization: Bearer $token"
```

Replay the same `idempotencyKey` to show HTTP ingest idempotency (202 + `idempotentReplay: true`, no second ledger row).

## 8. Strict quota consumption

```powershell
$cIdem = "demo-consume-" + [guid]::NewGuid().ToString()
curl.exe -s -X POST http://localhost:8083/api/v1/usage/consume `
  -H "Authorization: Bearer $token" `
  -H "Content-Type: application/json" `
  -H "X-Correlation-Id: demo-consume-1" `
  -d "{\"productKey\":\"datapilot-cloud\",\"meterKey\":\"scheduled_export\",\"quantity\":1,\"occurredAt\":\"2026-08-18T14:31:00Z\",\"idempotencyKey\":\"$cIdem\"}"
```

Expect HTTP **200** with commercial `decision` `ACCEPTED` or `REJECTED`. Both are success from an HTTP standpoint. Seeded `scheduled_export` limit is 1_000_000, so a single consume should be `ACCEPTED`.

## 9. Commercial period (inspect, do not destroy)

```powershell
docker exec usagecore-postgres psql -U usagecore -d usagecore -c "SELECT status, period_start, period_end FROM commercial_period;"
```

Expect `OPEN` for Acme/DataPilot. Lifecycle APIs are on Control Plane (`/api/v1/tenants/{tenantId}/products/{productId}/commercial-periods/...`) and require `PLATFORM_ADMIN` or `BILLING_OPERATOR`.

Do **not** finalize the only OPEN lab period unless you intend to re-seed. Delayed-delivery-after-FINALIZED behavior is **VERIFIED BY TEST** (`DelayedDeliveryFinalizationIntegrationTest`), not required live in this 15-minute path.

## 10. Reconciliation / adjustment (point to evidence)

Reconciliation is `POST /api/v1/reconciliation/periods/{commercialPeriodId}/runs` (Usage Pipeline; `PLATFORM_ADMIN` / `BILLING_OPERATOR`). It reports MATCH/MISMATCH. It does not repair.

Live adjustment needs a quarantined exception against a COMPLETED run — easier to inspect in tests than to manufacture in a short demo:

- `ReconciliationApiIntegrationTest`
- `UsageAdjustmentApiIntegrationTest`

## 11. Point to already-proven evidence (do not rerun)

| Topic | Where |
| --- | --- |
| Failure windows | [resilience/failure-matrix.md](../resilience/failure-matrix.md) |
| Local Gatling | [performance/summary.md](../performance/summary.md) |
| kind drills | [kubernetes/failure-matrix.md](../kubernetes/failure-matrix.md) |
| AWS | [aws/README.md](../aws/README.md) — configuration only |
| CI/CD | [cicd/evidence.md](../cicd/evidence.md) — configuration unless recorded |

## If a command cannot run here

Label it honestly. Typical blockers: Docker not running, Keycloak still importing, Control Plane not started (schema missing), unique `idempotencyKey` reused with a different body (409).

API field semantics: [api-examples.md](api-examples.md).
