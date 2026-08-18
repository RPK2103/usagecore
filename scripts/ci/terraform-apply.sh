#!/usr/bin/env bash
# Apply a previously generated Terraform plan for the same commit/environment.
# Do not run without explicit AWS spend approval.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_DIR="${ROOT}/infrastructure/terraform/environments/dev"
PLAN_FILE="${1:-${ENV_DIR}/tfplan}"

require_cmd terraform
validate_environment "dev"

if [[ ! -f "${PLAN_FILE}" ]]; then
  echo "plan file not found: ${PLAN_FILE}" >&2
  exit 1
fi

cd "${ENV_DIR}"
terraform apply -input=false "${PLAN_FILE}"
