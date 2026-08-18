# Rollback

## Application (Helm)

```bash
helm history usagecore -n usagecore
helm rollback usagecore <revision> -n usagecore --wait --timeout 10m
```

Do not automatically rollback every failed release. After a failed upgrade: stop, inspect, then choose rollback.

`--force` and delete/recreate of Deployments are not routine recovery.

## Schema / Flyway

Helm rollback **does not** reverse Flyway migrations. Control Plane is the only migration owner. Application rollback and schema rollback are separate operations.

Forward-only schema is the default. Expand/contract is required before a release that cannot run mixed Control Plane / runtime versions. Restoring RDS from backup is an operations action and is **not** proven.

## Infrastructure (Terraform)

Do not automate `terraform destroy` as rollback. Bad infrastructure changes should be blocked by plan review and the `dev` environment gate.

Reverting a Terraform commit and applying a new plan is a forward change, not an undo button.

## Images

Redeploy a previous git SHA tag already in ECR. Do not retag `latest`. ECR tag mutability is immutable, so the previous SHA tag remains the previous image.
