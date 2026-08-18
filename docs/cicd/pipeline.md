# Pipeline

## Flow

```text
Developer
  → GitHub pull request
    → CI (Maven verify, Terraform fmt/validate, Helm, Compose)
    → Security (CodeQL, dependency review, Trivy IaC)
    → Container build + scan (no ECR push)
  → merge to main
    → same correctness gates
    → immutable images tagged with git SHA
    → optional ECR publish via GitHub OIDC (if roles configured)
  → operator workflow_dispatch Deploy
    → GitHub environment `dev` gate
    → optional Terraform plan+apply
    → Secrets Manager → Kubernetes Secret
    → Helm upgrade (Control Plane first)
    → smoke
```

A green CI run is necessary and not sufficient for AWS mutation.

## Pull request gate

Jobs intended as future required checks:

- `Java / Verify`
- `Terraform / Validate`
- `Helm / Validate`
- `Compose / Validate`
- `Security / CodeQL`
- `Security / Dependency Review`
- `Security / Trivy IaC`
- `Container / Build`

PRs do **not** run `terraform apply`, production deploy, destructive database operations, or Gatling profiles.

## Main

Push to `main` rebuilds the same gates. Container publish runs only when `AWS_ROLE_ECR_PUBLISH` is set and the event is not a pull request. Images saved from the scanned build job are loaded and pushed (build once / promote the same local images).

## Terraform

| Stage | When | AWS? |
| --- | --- | --- |
| `fmt -check` + `init -backend=false` + `validate` | every PR/main | no |
| `plan` | `workflow_dispatch` with input `plan` | OIDC plan role |
| `apply` | Deploy workflow, `apply_infrastructure=true`, environment `dev` | OIDC apply role |

Validate is not cloud validation. Plan is not apply.

The apply job generates a plan for the current SHA and applies that file in the same job after environment approval. It does not reuse a previously reviewed artifact from another run.

## Application-only release

Deploy with `apply_infrastructure=false` (default) and `deploy_application=true` performs Helm upgrade against existing infrastructure. Most Java commits should not apply Terraform.

## Performance

Full Gatling profiles are not CI gates. Shared GitHub runners are not the Phase 11 laboratory. No p95 latency gate.

## Branch protection

Recommended for `main`: required PR, required checks named above, no force push. This is **documentation only** unless an operator enables it in GitHub.

```text
CONFIGURATION/DOCUMENTATION ONLY
```
