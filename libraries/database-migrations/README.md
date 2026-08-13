# UsageCore database migrations

Resource-only Maven module containing the authoritative Flyway SQL for the shared
UsageCore PostgreSQL schema.

## Ownership

| Environment | Owner |
| --- | --- |
| Production / shared local DB | **Control Plane** (`spring.flyway.enabled=true`) |
| Entitlement Runtime process | **Does not** migrate production schema by default (`spring.flyway.enabled=false`) |
| Usage Pipeline process | **Does not** migrate production schema by default (`spring.flyway.enabled=false`) |
| Runtime / pipeline integration tests | May enable Flyway against a fresh Testcontainers database |

`control-plane`, `entitlement-runtime`, and `usage-pipeline` depend on this artifact for
schema awareness and test migrations. Do not duplicate SQL files under application modules.

## Versions

| Version | Contents |
| --- | --- |
| V1–V4 | Foundation, catalogue, contract, security audit (unchanged from Control Plane history) |
| V5 | `entitlement_decision` append-oriented decision evidence |
| V6 | `usage_ingestion` + `outbox_event` (Phase 5A durable ingestion / transactional outbox) |
| V7 | `processed_event` + `usage_ledger` (Phase 5B consumer inbox / canonical usage ledger) |
| V8 | `meter_definition` + `usage_aggregate` (Phase 6A metering / deterministic aggregation) |
| V9 | `aggregation_window` on meters + `usage_window_aggregate` + `usage_ledger.is_late` (Phase 6B event-time windows) |

## Trade-off (v1)

Independent deployment of Entitlement Runtime with a shared authoritative PostgreSQL
commercial schema and no compile-time dependency on Control Plane domain classes.
Future dedicated read-models or caches require measured evidence — see ADR-007.
