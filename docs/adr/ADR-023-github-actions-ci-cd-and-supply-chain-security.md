# ADR-023: GitHub Actions CI/CD and supply-chain security

## Status

Accepted — Phase 14 delivery workflows and AWS OIDC identity. Not a live AWS deployment.

## Context

Phases 1–13 proved domain correctness, observability, resilience, local performance methodology, kind/Helm packaging, and AWS topology as Terraform. Delivery was still a manual engineering workflow. Phase 14 must add a credible, auditable pipeline without weakening invariants or creating chargeable AWS infrastructure from pull requests.

## Decision

### Why GitHub Actions

The repository is already on GitHub. Adding Jenkins, GitLab CI, Argo Workflows, or Tekton would introduce a second control plane with no measured need.

### Workflow separation

Five workflows: CI (correctness), Security (CodeQL + dependency review + Trivy IaC), Container (build/scan/optional ECR), Terraform (fmt/validate vs plan), Deploy (gated apply + Helm). Validate is not plan. Plan is not apply. Application Helm is not Terraform.

### OIDC vs long-lived AWS keys

GitHub assumes IAM roles through `token.actions.githubusercontent.com`. Trust is bound to `repo:<owner>/<repo>` plus ref or `environment:dev`. Pull requests do not receive AWS credentials. `pull_request_target` is forbidden for executing PR code.

### Action pinning

Third-party actions use full commit SHAs with version comments. Dependabot may open weekly GitHub Actions PRs; comments must stay synchronized.

### Least-privilege GitHub tokens

Default `contents: read`. Write permissions are job-scoped and documented (`security-events`, `id-token`, `attestations`).

### Artifact identity

Images are tagged with git SHA, never `latest` as deploy authority. Git SHA identifies source. Container digest identifies image bytes. Publish loads the scanned images from the build job rather than rebuilding a different Dockerfile.

### Attestation / SBOM

CycloneDX SBOMs are artifacts. GitHub provenance attestations are generated on publish. SLSA Level X is not claimed.

### Terraform vs Helm

Unchanged from ADR-022. Terraform apply is optional per deploy. Most application releases only Helm-upgrade.

### Secret injection

A deploy job retrieves RDS and MSK secrets with short-lived OIDC credentials and applies Kubernetes Secret `usagecore-secrets`. Operators and CSI are not introduced. Keycloak local secrets are not synced to AWS.

### MSK AWS runtime configuration

Usage Pipeline `application-aws.yml` maps SASL_SSL + SCRAM-SHA-512. JAAS comes from the Secret. Local PLAINTEXT is unchanged. MSK topics are created by an in-cluster Helm hook, not from GitHub-hosted runners.

### Migration sequencing

Deploy script applies Control Plane first, waits, then remaining workloads. Helm rollback does not undo Flyway.

### Rollback

Manual Helm rollback. No automatic rollback. No `terraform destroy` as rollback.

### Performance gates

Not in shared-runner CI. Phase 11 remains the laboratory.

### What is live-proven vs configuration-only

Workflows, scripts, Terraform OIDC modules, and application AWS Kafka mapping are implemented. GitHub-hosted runs, AWS OIDC assume-role, terraform plan/apply, ECR push, EKS Helm, and AWS smoke are unexecuted until operators configure GitHub and authorize spend.

## Consequences

- A senior engineer can inspect how UsageCore would be delivered without surprise AWS bills from PRs.
- First Terraform apply remains a human action with operator AWS credentials (OIDC roles live in the same stack).
- GitHub environment protection and branch protection must be enabled in the GitHub UI; YAML cannot prove they are on.
