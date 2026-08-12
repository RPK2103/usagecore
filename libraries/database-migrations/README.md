# UsageCore database migrations

Resource-only Maven module containing the authoritative Flyway SQL for the shared
UsageCore PostgreSQL schema.

## Ownership

| Environment | Owner |
| --- | --- |
| Production / shared local DB | **Control Plane** (`spring.flyway.enabled=true`) |
| Entitlement Runtime process | **Does not** migrate production schema by default (`spring.flyway.enabled=false`) |
| Runtime integration tests | May enable Flyway against a fresh Testcontainers database |

Both `control-plane` and `entitlement-runtime` depend on this artifact for schema
awareness and test migrations. Do not duplicate SQL files under application modules.

## Versions

| Version | Contents |
| --- | --- |
| V1–V4 | Foundation, catalogue, contract, security audit (unchanged from Control Plane history) |
| V5 | `entitlement_decision` append-oriented decision evidence |

## Trade-off (v1)

Independent deployment of Entitlement Runtime with a shared authoritative PostgreSQL
commercial schema and no compile-time dependency on Control Plane domain classes.
Future dedicated read-models or caches require measured evidence — see ADR-007.
