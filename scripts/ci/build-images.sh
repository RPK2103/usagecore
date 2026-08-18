#!/usr/bin/env bash
# Build the three UsageCore workload images from Dockerfile.workload.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT}"

TAG="${1:-}"
validate_image_tag "${TAG}"
require_cmd docker

build_one() {
  local workload="$1"
  local port="$2"
  local image="usagecore/${workload}:${TAG}"
  echo "Building ${image}"
  docker build \
    -f infrastructure/docker/Dockerfile.workload \
    --build-arg "WORKLOAD=${workload}" \
    --build-arg "SERVER_PORT=${port}" \
    -t "${image}" \
    .
}

build_one control-plane 8080
build_one entitlement-runtime 8082
build_one usage-pipeline 8083

echo "Built usagecore/control-plane:${TAG}"
echo "Built usagecore/entitlement-runtime:${TAG}"
echo "Built usagecore/usage-pipeline:${TAG}"
