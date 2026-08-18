#!/usr/bin/env bash
# Push locally built SHA-tagged images to the three ECR repositories.
# Does not rebuild. Requires docker login to ECR already.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

TAG="${1:-}"
REGISTRY="${2:-}"
validate_image_tag "${TAG}"
if [[ -z "${REGISTRY}" ]]; then
  echo "ECR registry host is required (account.dkr.ecr.region.amazonaws.com)" >&2
  exit 1
fi
require_cmd docker

DIGEST_DIR="${3:-/tmp/usagecore-image-digests}"
mkdir -p "${DIGEST_DIR}"

push_one() {
  local workload="$1"
  local repo="usagecore-${workload}"
  local source="usagecore/${workload}:${TAG}"
  local target="${REGISTRY}/${repo}:${TAG}"
  docker tag "${source}" "${target}"
  docker push "${target}"
  local digest
  digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "${target}" | awk -F@ '{print $2}')"
  if [[ -z "${digest}" ]]; then
    echo "failed to read digest for ${target}" >&2
    exit 1
  fi
  printf '%s\n' "${digest}" > "${DIGEST_DIR}/${workload}.digest"
  echo "${workload} digest=${digest}"
}

push_one control-plane
push_one entitlement-runtime
push_one usage-pipeline
