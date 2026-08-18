# AWS architecture (Phase 13)

Target cloud topology for UsageCore. PostgreSQL remains commercial and quota authority. Kafka remains at-least-once usage transport. JWT remains tenant authority.

Phase 12 kind/Helm is the strongest **live** deployment evidence. This directory describes the AWS mapping and Terraform configuration.

Evidence label for AWS runtime behavior unless a live apply is authorized and executed:

```text
CONFIGURATION VALIDATED ONLY
```

## Documents

| Doc | Contents |
| --- | --- |
| [architecture.md](architecture.md) | Topology, service mapping, sources of truth |
| [deployment-model.md](deployment-model.md) | Terraform vs Helm, rollout, migrations, replicas |
| [security.md](security.md) | IAM, network, secrets, TLS, identity |
| [cost-and-limitations.md](cost-and-limitations.md) | Cost drivers, destroyability, honest non-claims |
| [terraform-validation.md](terraform-validation.md) | What fmt/validate/plan prove |

ADR: [ADR-022](../adr/ADR-022-aws-deployment-architecture-and-terraform.md)

Terraform: [`infrastructure/terraform/`](../../infrastructure/terraform/README.md)
