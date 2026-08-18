# Phase 14 evidence

Use only labels that match what actually ran.

| Claim | Label |
| --- | --- |
| Workflows exist, YAML inspected, local static commands run | CONFIGURATION VALIDATED ONLY |
| actionlint / workflow linter | VERIFIED BY WORKFLOW LINT |
| Maven `clean verify` | VERIFIED BY TEST |
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

Phase 12 remains the live Kubernetes evidence (kind). Phase 11 remains the performance evidence. Phase 13 remains configuration-only for AWS topology unless a later apply is authorized.
