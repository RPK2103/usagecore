# AWS security

## Network

```text
Internet
  → ALB :80 and/or :443 (configurable)
    → EKS node/pod SG :8080/:8082/:8083
      → RDS :5432 (EKS node SG only)
      → MSK :9096 SASL/SCRAM TLS (EKS node SG only)
```

Hard rules in Terraform:

- RDS `publicly_accessible = false` (not a toggle)
- MSK has no public access configuration
- No `0.0.0.0/0` to PostgreSQL or Kafka
- No SSH key pairs; node access model is SSM if ever required
- EKS public API CIDRs default to RFC 5737 TEST-NET-3, not `0.0.0.0/0`
- Data subnets have no NAT route

Security groups enforce **ENI-level** rules. They are not Kubernetes NetworkPolicy and not security groups for pods. Pod-level least privilege is **not** claimed.

NAT: single gateway in the example environment. Private app subnets share that availability dependency. This is a cost choice, not a production HA recommendation.

VPC endpoints: S3 gateway endpoint only (no hourly charge). Interface endpoints for ECR API/DKR, Secrets Manager, CloudWatch Logs, and STS are documented as a production cost/security optimization, not implemented in the dev stack.

## EKS API

Public + private endpoint is the dev convenience trade-off. Fully private control-plane access needs extra operator paths (bastion, VPN, or SSM). Neither choice is universal.

## IAM

| Role | Purpose | Notes |
| --- | --- | --- |
| EKS cluster | AWS-managed control plane | `AmazonEKSClusterPolicy` |
| EKS node | kubelet, CNI, ECR pull, SSM | No SSH |
| ALB controller | Recommended ELB/EC2/ACM actions | Pod Identity; WAF/Shield/Cognito omitted |
| Application pods | none | Apps talk to RDS/MSK/OIDC; they do not call AWS APIs in this design |

No `AdministratorAccess`, `PowerUserAccess`, or workload `*` admin policies.

EKS Pod Identity is used (not IRSA) because this is a new EC2 managed-node cluster. The two mechanisms are not configured together.

Describe-style `Resource = "*"` on the ALB controller policy matches the upstream recommended policy for those list/describe APIs. That is not an admin wildcard.

## Secrets

| Secret | How it is created | Value in Terraform? | Kubernetes |
| --- | --- | --- | --- |
| RDS master password | `manage_master_user_password = true` | No plaintext output | Sync deferred to Phase 14 |
| MSK SCRAM | Secrets Manager container `AmazonMSK_*` | No version written | Same |
| OIDC client (optional) | Secrets Manager container | No version written | Same |

`values-aws.yaml` sets `secrets.create: false` so Helm does not embed placeholder production passwords.

Terraform **state** may still store secret ARNs and resource metadata. Treat state as sensitive.

## TLS

HTTPS termination is planned at the ALB using an ACM certificate ARN variable. No fake certificates. No Route 53 domain is provisioned. HTTP-only ALB listeners are a documented gap versus production HTTPS.

## Encryption

| Store | At rest | In transit |
| --- | --- | --- |
| RDS | AWS-managed storage encryption | TLS to RDS (client config at deploy time) |
| MSK | AWS-managed | TLS client-broker; TLS in-cluster |
| ECR | AES256 | TLS to ECR |
| Secrets Manager | AWS-managed | TLS |
| EKS node volumes | gp3 encrypted | n/a |
| Kubernetes secrets etcd | AWS-managed EKS default; customer CMK envelope encryption not enabled | n/a |

One customer-managed KMS key is **not** created. AWS-managed keys keep cost/operability lower for this example. Production may introduce a small CMK set with rotation and key-policy review.

## Authentication to Kafka

MSK enables SASL/SCRAM and disables unauthenticated access. IAM auth is not enabled, to avoid forcing a Java client-library change in this phase. Spring Kafka SCRAM configuration remains a deploy-time integration (Phase 14).

## Authorization of the product

JWT still supplies tenant, roles, and principal. ALB, IAM, and headers are not tenant authority.

## WAF / CloudFront / API Gateway

Not deployed. WAF is optional production perimeter hardening. CloudFront is not justified for this API backend. One ingress layer (ALB) is enough.
