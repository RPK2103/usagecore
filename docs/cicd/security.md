# CI/CD security

## Long-lived AWS keys

None. Workflows assume IAM roles through GitHub Actions OIDC (`id-token: write` on those jobs only). Do not add `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` repository secrets.

## OIDC trust

Terraform module `infrastructure/terraform/modules/github-oidc` creates:

| Role | Subject | Purpose |
| --- | --- | --- |
| `*-gha-ecr-publish` | `repo:<owner>/<repo>:ref:refs/heads/main` and `refs/tags/v*` | Push three ECR repositories |
| `*-gha-tf-plan` | `repo:<owner>/<repo>:ref:refs/heads/main` | Terraform plan + state lock |
| `*-gha-tf-apply` | `repo:<owner>/<repo>:environment:dev` | Terraform apply |
| `*-gha-helm-deploy` | `repo:<owner>/<repo>:environment:dev` | Secrets sync, Helm, smoke |

Audience is `sts.amazonaws.com`. The repository is a Terraform variable (`github_repository`), not a username hard-coded in workflow YAML.

Untrusted pull requests cannot assume these roles: `pull_request` subjects are not in the trust policy. Workflows do not use `pull_request_target`.

## GitHub token permissions

Default `permissions: contents: read`. Extra job permissions:

| Job | Extra | Why |
| --- | --- | --- |
| Security / CodeQL | `security-events: write`, `actions: read` | Upload CodeQL SARIF |
| Security / Dependency Review | `pull-requests: read` | Compare API for the PR head/base |
| Container / Publish ECR | `id-token: write`, `attestations: write` | AWS OIDC + GitHub provenance |
| Terraform / Plan | `id-token: write` | AWS OIDC |
| Deploy jobs | `id-token: write` | AWS OIDC |

No `write-all`.

## Action pinning

Third-party actions are pinned to full commit SHAs with version comments. See [`.github/PINNED-ACTIONS.md`](../../.github/PINNED-ACTIONS.md). Dependabot updates the GitHub Actions ecosystem weekly; humans must keep the version comment aligned.

`aquasecurity/trivy-action` is pinned to v0.36.0 (2026-04-22), after the March 2026 Trivy distribution incident. Do not float `@master`.

## Scanners

| Tool | Scope | Gate |
| --- | --- | --- |
| CodeQL Java | Source | Blocking when the GitHub plan allows CodeQL |
| Dependency review | PR dependency diffs | Fail on high when GitHub Dependency graph compare succeeds. If the compare API returns 403/404 (graph not enabled / unsupported for the token), the job records a notice and does not fail — that path is not a vulnerability clearance |
| Trivy config | Terraform | Fail on CRITICAL/HIGH |
| Trivy image | Three workload images | Fail on CRITICAL |

OWASP Dependency-Check is not used (NVD token/false-positive cost). No silent suppressions. Every future suppression must name identifier, reason, scope, and review rationale.

### Finding policy

- **Critical:** block unless a documented false positive / accepted risk exists
- **High:** investigate; IaC HIGH blocks; container HIGH is reported (OS JRE findings are common) and does not auto-rewrite the Temurin base image
- **Medium/Low:** report and track

Do not claim zero vulnerabilities.

Accepted IaC findings (example topology, not silent): see repository `.trivyignore`. Identifiers: AVD-AWS-0132, AVD-AWS-0039, AVD-AWS-0040, AVD-AWS-0041, AVD-AWS-0104, AVD-AWS-0164. Each line in that file names reason and documentation pointer. MSK at-rest encryption is declared in Terraform (`alias/aws/kafka`) rather than ignored.

Enable Dependency graph under repository Settings → Code security so PR dependency review can run. Until that compare API returns 200 or 409, the workflow skips the action after a notice rather than failing every PR. Real high-severity findings still fail when the API is available.

GitHub-hosted execution of these scanners is recorded in [evidence.md](evidence.md). A failed run is evidence too — do not imply the Security workflow is green unless it is.

## SBOM and attestation

CycloneDX SBOMs are workflow artifacts (not committed). GitHub build provenance attestations are generated for published images when the publish job runs. This is not a SLSA Level 3 claim.

## Secrets and logs

- No `set -x` in secret-bearing scripts
- Workflow `workflow_dispatch` inputs are passed through `env:` then quoted shell variables, never interpolated into the script body
- Terraform plans are artifacts with 3-day retention, not PR comments
- Kubernetes secrets are applied from temp files that are deleted
- Kafka JAAS is a Secret key, not Helm `--set`
- Do not upload `helm get values --all` or environment dumps

## Terraform apply IAM wildcards

The apply role is not `AdministratorAccess`. It still uses broad `ec2:*`, `eks:*`, `rds:*`, `kafka:*`, `secretsmanager:*`, `ecr:*`, and IAM role/policy management because first-class least privilege for this module set is too fragile for the milestone. Audit those wildcards before a production account.

The plan role uses `ReadOnlyAccess` plus optional S3 state access so `terraform plan` can refresh.

## ECR scope

Publish is limited to `usagecore-control-plane`, `usagecore-entitlement-runtime`, `usagecore-usage-pipeline`. No `DeleteRepository`.
