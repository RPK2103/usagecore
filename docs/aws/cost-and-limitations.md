# Cost, destroyability, and limitations

## Cost drivers

Do not treat the following as a monthly invoice. Numbers are not invented.

| Resource | Why it costs | Dev default |
| --- | --- | --- |
| EKS control plane | Hourly cluster charge | One cluster |
| Managed nodes | EC2 for `t3.large` × 2–3 | Small range, not production sizing |
| NAT Gateway | Hourly + data processing | **One** NAT, not one per AZ |
| RDS | Instance + storage + backup | `db.t4g.medium`, single-AZ, 20 GiB, 7-day backup |
| MSK | Brokers + storage | `kafka.t3.small` × **2** (not 3) |
| ALB | Hourly + LCU after controller install | Created by Kubernetes Ingress, not Terraform |
| VPC interface endpoints | Hourly per AZ per endpoint | **Not** created (S3 gateway only) |
| CloudWatch | Ingestion + storage | EKS api/audit logs, 14-day retention |
| ECR | Storage | Lifecycle expires old images |

Resources that should not be left running in a personal account: EKS, NAT, RDS, MSK, extra nodes.

## Dev vs production trade-offs

| Topic | Dev / example default | Production-oriented setting |
| --- | --- | --- |
| NAT | single | per AZ |
| RDS Multi-AZ | false | true |
| RDS deletion protection | false | true |
| skip_final_snapshot | true | false |
| MSK brokers | 2 | 3+ in 3 AZs, larger instances |
| Node size | t3.large × 2 | sized from cloud load tests, not laptop Gatling |
| EKS API | public + CIDR allowlist | often private-only with operator network |
| ALB TLS | optional certificate ARN | required HTTPS + real domain |
| Interface VPC endpoints | omitted | ECR/Logs/STS/Secrets as needed |
| Terraform backend | local | encrypted S3 + `use_lockfile` |

Single-AZ RDS is **not** HA. One NAT is an availability dependency.

## Destroyability

```powershell
cd infrastructure/terraform/environments/dev
terraform destroy
```

`terraform destroy` deletes example data. It is not for persistent environments.

Settings that can block or complicate destroy:

- `rds_deletion_protection = true`
- `rds_skip_final_snapshot = false` (must supply a final snapshot name)
- ECR images if `ecr_force_delete = false`
- Load balancer ENIs created by the AWS Load Balancer Controller after Ingress exists
- MSK/EKS eventual consistency during teardown

The example environment prefers destroyable defaults. That is **not** a recommendation to weaken production protection.

## Limitations (honest)

Unless a live apply and drill are actually performed:

```text
no live AWS deployment
no RDS failover drill
no MSK broker-failure drill
no AZ-failure test
no AWS load test
no real production secret injection
no remote Terraform state deployment
no CI/CD
no DR proof
no backup restoration proof
```

Phase 12 remains the live Kubernetes operability evidence.

## What must not be claimed

- “Verified by AWS” from `terraform validate` alone
- Production-ready
- Multi-AZ failover proven
- Phase 11 local TPS as EC2 sizing
- Secrets Manager as a complete answer to Terraform state sensitivity
