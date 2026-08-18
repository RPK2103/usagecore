# ADR-022: AWS deployment architecture and Terraform

## Status

Accepted — Phase 13 infrastructure design and Terraform configuration. Not a live AWS deployment.

## Context

Phases 1–12 proved UsageCore correctness, observability, resilience, local performance methodology, and Kubernetes packaging on kind. Phase 13 must map that system onto a credible minimal AWS topology without inventing a fourth service, replacing PostgreSQL or Kafka, or claiming production readiness.

## Decision

### Why EKS (not ECS)

Phase 12 already established Helm charts, probes, replica semantics, and operability drills. EKS reuses those artifacts. ECS would discard that evidence and restart packaging.

EKS costs more and is operationally heavier than ECS. That is an intentional portfolio trade-off, not a claim that EKS is universally cheaper or simpler.

### Why RDS PostgreSQL (not Aurora, not DynamoDB)

UsageCore depends on PostgreSQL constraints, `btree_gist` exclusion constraints, transactions, and `FOR UPDATE SKIP LOCKED`. RDS for PostgreSQL is the straightforward mapping. DynamoDB would change the correctness model. Aurora is not required.

Engine family stays 16 to match Testcontainers and Compose. Flyway remains the schema owner; Terraform does not create extensions or business rows.

Dev Multi-AZ is **off**. That is not HA. Production should enable Multi-AZ. Backups retain 7 days in the example; restore is not proven.

### Why MSK (not SQS)

Kafka is the usage transport (outbox → topic → consumer inbox). SQS would change the project story. Provisioned MSK is chosen over MSK Serverless because broker topology, auth, and cost are easier to reason about in a portfolio architecture.

Dev default is two `kafka.t3.small` brokers in two data subnets. That is cheaper than three brokers in three AZs and is still a multi-AZ Kafka cluster, not a production sizing recommendation. MSK is a primary cost driver and must not be left running accidentally.

Terraform does not create application topics.

### Why ECR

Three repositories matching the three workloads. Immutable tags, scan-on-push, lifecycle expiry. No fourth image.

### Network topology

VPC `10.20.0.0/16` (configurable), 2 or 3 AZs (default 3):

- Public subnets: ALB / NAT / Internet Gateway
- Private application subnets: EKS nodes (no public IPs)
- Private data subnets: RDS and MSK, no NAT, no Internet route

**NAT trade-off:** one NAT Gateway by default. Lower cost; all private-app egress depends on one AZ. `per_az` exists as a variable. Three NAT Gateways are not created silently.

S3 gateway endpoint is enabled. Interface endpoints are omitted in the example to limit hourly cost.

### IAM and pod identity

EKS Pod Identity (not IRSA) for the AWS Load Balancer Controller on a new EC2 managed-node cluster. Application pods receive no AWS API role. Node role pulls ECR images and can use SSM instead of SSH.

### Secrets

RDS uses `manage_master_user_password`. MSK SCRAM and optional OIDC client secrets are Secrets Manager *containers*; Terraform does not write secret versions. Kubernetes delivery is deferred to Phase 14. Helm `values-aws.yaml` does not create production placeholders as if they were real credentials.

Terraform state remains sensitive even when passwords are not outputs.

### Terraform vs Helm

Terraform owns AWS. Helm owns Kubernetes objects. No Terraform Helm provider install of UsageCore. No Flyway provisioners. No `local-exec`.

### Ingress

AWS Load Balancer Controller + ALB. Separate hostnames so `/api/v1` is unchanged. ACM certificate ARN is optional. No Route 53 zone. No NGINX ingress in front of ALB.

### Identity

External OIDC issuer. Keycloak stays the local reference IdP. Cognito is out of scope.

### Explicit non-goals (this phase)

Redis/ElastiCache, DynamoDB, API Gateway, CloudFront, WAF, Lambda, SQS/SNS, Karpenter, KEDA, HPA proof, GitHub Actions, LocalStack-as-AWS.

## Consequences

- A senior engineer can inspect Terraform and docs and see how UsageCore would run on AWS.
- Live AWS behavior remains unproven until apply and drills are authorized.
- Phase 14 may automate image push, apply, Helm, and secret sync.
- Phase 7B PostgreSQL RLS remains an optional deferred milestone; cloud hosting does not replace it.

## What Terraform validation proves

Formatted, schema-valid configuration. Not runtime security, not failover, not cost, not DR.

## What is not live-proven

EKS/RDS/MSK existence, ALB traffic, secret injection, Multi-AZ failover, backup restore, Kafka IAM/SCRAM from the Java process, CloudWatch ingestion.
