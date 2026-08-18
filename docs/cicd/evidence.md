# Phase 14–15 CI/CD evidence

Use only labels that match what actually ran.

## Label catalogue

| Claim | Label |
| --- | --- |
| Workflows exist, YAML inspected, local static commands run | CONFIGURATION VALIDATED ONLY |
| actionlint / workflow linter | VERIFIED BY WORKFLOW LINT |
| Maven `clean verify` (local) | VERIFIED BY TEST |
| Terraform fmt | VERIFIED BY TERRAFORM FMT |
| Terraform validate | VERIFIED BY TERRAFORM VALIDATE |
| Helm lint / template | VERIFIED BY HELM LINT / VERIFIED BY HELM TEMPLATE |
| Local image build | VERIFIED BY CONTAINER BUILD |
| GitHub-hosted workflow run | VERIFIED BY GITHUB ACTIONS |
| `terraform plan` against AWS | VERIFIED BY TERRAFORM PLAN |
| `terraform apply` | NOT EXECUTED — LIVE COST-BEARING AWS DEPLOYMENT REQUIRES EXPLICIT APPROVAL |
| ECR push | VERIFIED BY ECR PUSH or configured but not executed |
| OIDC assume-role | VERIFIED BY AWS OIDC or REASONED BUT NOT EXECUTED |
| Live EKS Helm + smoke | VERIFIED BY LIVE AWS DEPLOYMENT / SMOKE or REASONED BUT NOT EXECUTED |
| CodeQL/Trivy in GitHub | VERIFIED BY SECURITY SCAN or CONFIGURATION VALIDATED ONLY |
| SBOM artifact produced in GitHub | VERIFIED BY SBOM GENERATION |
| Attestation produced in GitHub | VERIFIED BY ARTIFACT ATTESTATION |

## GitHub-hosted runs (commit `24975ee`)

Inspected 2026-08-18 against `https://github.com/RPK2103/usagecore`. SHA `24975ee48f07c477d5e3240357773c2759ba1c9a` (`feat: add github actions ci-cd and supply-chain delivery gates`).

| Workflow | Result | Notes |
| --- | --- | --- |
| Terraform | **success** | fmt/validate. Plan skipped (no dispatch). **VERIFIED BY GITHUB ACTIONS** for validate only. |
| Container | **success** | Image build + scan. ECR publish skipped (no OIDC/ECR role). **VERIFIED BY GITHUB ACTIONS** for build. |
| CI | **failure** | Helm/Terraform/Compose jobs succeeded. **Java / Verify failed**: `./mvnw: Permission denied` (wrapper not executable in git). Not a test failure. |
| Security | **failure** | CodeQL succeeded. Dependency Review skipped (push, not PR). **Trivy IaC failed** on documented example-topology findings (EKS public API CIDR, node egress, public subnets, AES256 vs CMK, MSK at-rest attribute, EKS secrets CMK). |
| Deploy | not run | Requires `workflow_dispatch` + environment `dev`. |
| Terraform plan / apply, ECR push, OIDC, EKS Helm | **not executed** | REASONED BUT NOT EXECUTED / NOT EXECUTED |

Phase 15 records this honestly and applies bounded fixes (executable `mvnw`; explicit MSK at-rest KMS alias; documented `.trivyignore` for accepted example risks). Those fixes are **not** proven green on GitHub until a later hosted run.

Do **not** add README status badges that would look green while CI/Security were red on first execution.

Phase 12 remains the live Kubernetes evidence (kind). Phase 11 remains the performance evidence. Phase 13 remains configuration-only for AWS topology unless a later apply is authorized.
