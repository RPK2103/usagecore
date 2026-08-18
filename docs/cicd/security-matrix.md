# Workflow security matrix

| Workflow | Trigger | Untrusted code possible? | GITHUB_TOKEN permissions | AWS OIDC? | AWS role | Writes artifacts? | Can deploy? | Environment gate? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ci.yml` | `pull_request`, `push` to `main` | Yes on fork PRs (tests only) | `contents: read` | No | none | Surefire on failure | No | No |
| `security.yml` | PR, push, weekly cron, dispatch | Yes on fork PRs | Default read; CodeQL `security-events: write` | No | none | Trivy report | No | No |
| `container.yml` build | PR, push, tag, dispatch | Yes on fork PRs | `contents: read` | No | none | SBOM | No | No |
| `container.yml` publish | push `main`/`v*`, dispatch | No (trusted ref) | `id-token: write`, `attestations: write` | Yes | ECR publish | No | ECR only | No |
| `terraform.yml` validate | PR, push | Yes on fork PRs | `contents: read` | No | none | No | No | No |
| `terraform.yml` plan | dispatch + input `plan` | No | `id-token: write` | Yes | Terraform plan | Plan (3 days) | No | No |
| `deploy.yml` infrastructure | dispatch `apply_infrastructure` | No | `id-token: write` | Yes | Terraform apply | No | Terraform apply | `dev` |
| `deploy.yml` application | dispatch `deploy_application` | No | `id-token: write` | Yes | Helm deploy | No | Helm + secrets | `dev` |

## Trigger trust notes

- `pull_request` runs the PR HEAD. Fork PRs can execute tests and static checks. They cannot receive AWS credentials.
- `pull_request_target` is not used.
- `push` to `main` is trusted for artifact publish if OIDC roles exist.
- `workflow_dispatch` is trusted to the selected ref; deploy jobs additionally require the `dev` environment.
- `schedule` (Security weekly) runs on default branch contents.

## Recommended GitHub environment `dev`

- Required reviewers
- Deployment branch: `main` only
- Environment variables listed in [README.md](README.md)

If those rules are not enabled in the GitHub UI:

```text
CONFIGURATION VALIDATED ONLY
```
