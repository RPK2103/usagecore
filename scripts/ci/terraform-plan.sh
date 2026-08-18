#!/usr/bin/env bash
# Terraform plan against AWS. Writes a binary plan artifact; does not apply.
# Do not print the full plan to stdout (may include secret-adjacent values).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_DIR="${ROOT}/infrastructure/terraform/environments/dev"
PLAN_FILE="${1:-${ENV_DIR}/tfplan}"

require_cmd terraform
validate_environment "dev"

cd "${ENV_DIR}"
terraform init -input=false
set +e
terraform plan -input=false -out="${PLAN_FILE}" -detailed-exitcode
rc=$?
set -e
if [[ "${rc}" -eq 1 ]]; then
  echo "terraform plan failed" >&2
  exit 1
fi
echo "Plan written to ${PLAN_FILE} (exit ${rc}; 0=no changes, 2=changes)"
