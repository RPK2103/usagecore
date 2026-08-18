#!/usr/bin/env bash
# Static PR gates that do not require AWS credentials.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT}"

require_cmd terraform helm docker git

echo "== terraform fmt -check -recursive"
terraform fmt -check -recursive infrastructure/terraform

echo "== terraform init/validate (dev, no backend)"
terraform -chdir=infrastructure/terraform/environments/dev init -backend=false -input=false
terraform -chdir=infrastructure/terraform/environments/dev validate

echo "== terraform init/validate (bootstrap, no backend)"
terraform -chdir=infrastructure/terraform/bootstrap init -backend=false -input=false
terraform -chdir=infrastructure/terraform/bootstrap validate

echo "== helm lint"
helm lint infrastructure/kubernetes/helm/usagecore

echo "== helm template (default/local)"
helm template usagecore infrastructure/kubernetes/helm/usagecore \
  --namespace usagecore \
  --validate=false \
  >/dev/null

echo "== helm template (values-aws.yaml)"
helm template usagecore infrastructure/kubernetes/helm/usagecore \
  --namespace usagecore \
  -f infrastructure/kubernetes/helm/usagecore/values-aws.yaml \
  --validate=false \
  >/dev/null

echo "== docker compose config"
docker compose -f infrastructure/docker/docker-compose.yml config --quiet

echo "== git diff --check"
git diff --check

echo "Static validation passed."
