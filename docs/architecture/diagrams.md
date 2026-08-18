# Architecture diagrams

Canonical diagrams for evaluators. Prose details live in [system overview](system-overview.md) and [source of truth](source-of-truth.md).

PostgreSQL remains correctness authority in every diagram. Kafka is transport.

## 1. Runtime architecture

```mermaid
flowchart TB
  Client[Client]
  CP[Control Plane :8080]
  ER[Entitlement Runtime :8082]
  UP[Usage Pipeline :8083]
  PG[(PostgreSQL source of truth)]
  KF[Kafka at-least-once transport]
  KC[Keycloak / OIDC JWKS]
  OBS[Prometheus / Grafana / OTel]

  Client --> CP
  Client --> ER
  Client --> UP
  CP --> PG
  ER --> PG
  UP --> PG
  UP --> KF
  KF --> UP
  CP --> KC
  ER --> KC
  UP --> KC
  CP --> OBS
  ER --> OBS
  UP --> OBS
```

Workloads:

- **Control Plane** — catalogue, contracts, commercial periods; production Flyway owner.
- **Entitlement Runtime** — authenticated read-only entitlement checks.
- **Usage Pipeline** — ingest, outbox, consumer, quota, reconciliation, adjustments.

Not services: `performance/` (lab), Terraform (infra code), shared libraries (SQL / envelopes).

## 2. Usage / outbox / inbox flow

```mermaid
sequenceDiagram
  participant C as Client
  participant HTTP as Usage Pipeline HTTP
  participant PG as PostgreSQL
  participant Pub as Outbox publisher
  participant K as Kafka
  participant Cons as Consumer

  C->>HTTP: POST /api/v1/usage/events
  HTTP->>PG: BEGIN usage_ingestion + outbox_event PENDING
  PG-->>HTTP: COMMIT
  HTTP-->>C: HTTP 202 ACCEPTED
  Pub->>PG: SKIP LOCKED claim PENDING
  Pub->>K: publish envelope
  Note over Pub,PG: Crash window 1: Kafka ACK then crash before PUBLISHED
  Pub->>PG: mark PUBLISHED
  K->>Cons: deliver UsageReceived
  Cons->>PG: BEGIN processed_event + usage_ledger + aggregates or exception
  PG-->>Cons: COMMIT
  Note over Cons,K: Crash window 2: DB commit then crash before offset progression
  Cons->>K: offset progress
```

Duplicates are possible at the **transport** layer in both crash windows. Business effects are deduplicated by inbox uniqueness (`processed_event`) and ingest idempotency keys.

HTTP 202 does not mean Kafka published, the consumer ran, or commercial totals changed.

## 3. Strict quota path

```mermaid
flowchart LR
  Req[POST /api/v1/usage/consume]
  JWT[JWT tenant_id + DEVELOPER]
  PG[(PostgreSQL)]
  QS[quota_state conditional UPDATE]
  QC[quota_consumption]
  OB[outbox_event PENDING]
  Dec{ACCEPTED or REJECTED}

  Req --> JWT --> PG
  PG --> QS
  QS --> Dec
  Dec -->|ACCEPTED| QC
  Dec -->|ACCEPTED| OB
  Dec -->|REJECTED| Resp[HTTP 200 commercial REJECTED]
  QC --> Resp2[HTTP 200 commercial ACCEPTED]
  OB --> Resp2
```

Reporting aggregates are not consulted for admission. A JVM lock cannot be the authority across Usage Pipeline replicas.

Flagship invariant (tested): limit 100, consumed 90, 20 concurrent quantity-1 requests → exactly 10 `ACCEPTED`, final consumed 100.

## 4. Commercial lifecycle and reconciliation

```mermaid
stateDiagram-v2
  [*] --> OPEN
  OPEN --> CLOSING
  CLOSING --> RECONCILING
  RECONCILING --> FINALIZED

  note right of RECONCILING
    Reconciliation: READ → REBUILD → COMPARE → REPORT
    MATCH does not auto-finalize
  end note

  note right of FINALIZED
    Delayed Kafka delivery:
    ledger + commercial_usage_exception
    aggregates not silently rewritten
  end note
```

Corrections after quarantine: explicit `usage_adjustment` (`APPLY_QUARANTINED_USAGE` only). Reconciliation does not auto-repair.

## 5. Deployment / evidence topology

```mermaid
flowchart TB
  subgraph local [Local developer]
    DC[Docker Compose dependencies]
    HOST[Host JVM apps]
  end
  subgraph tests [Correctness]
    TC[Testcontainers PostgreSQL + Kafka]
  end
  subgraph lab [Engineering lab]
    GAT[performance/ Gatling]
  end
  subgraph k8s [Live operability]
    KIND[kind + Helm]
  end
  subgraph aws [Target cloud]
    TF[Terraform EKS RDS MSK]
  end
  subgraph cicd [Delivery config]
    GH[GitHub Actions]
  end

  DC --> HOST
  HOST --> GAT
  TC --> MVN[Maven verify]
  KIND --> SMOKE[Authenticated smoke + failure drills]
  TF --> CFG[fmt / validate]
  GH --> CFG2[workflow YAML]
```

See [deployment matrix](deployment-matrix.md) for evidence labels.
