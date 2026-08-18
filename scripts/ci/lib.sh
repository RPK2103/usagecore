#!/usr/bin/env bash
# Shared helpers for UsageCore CI/CD scripts.
# Do not enable xtrace; secrets must never appear in logs.

set -euo pipefail

repo_root() {
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[1]}")/../.." && pwd)"
  printf '%s\n' "${here}"
}

require_cmd() {
  local cmd
  for cmd in "$@"; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
      echo "Required command not found: ${cmd}" >&2
      exit 1
    fi
  done
}

validate_image_tag() {
  local tag="${1:-}"
  if [[ -z "${tag}" ]]; then
    echo "image tag is required" >&2
    exit 1
  fi
  if [[ ! "${tag}" =~ ^[a-fA-F0-9]{7,40}$ ]] && [[ ! "${tag}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)*$ ]]; then
    echo "image tag must be a git SHA or vMAJOR.MINOR.PATCH; refused: ${tag}" >&2
    exit 1
  fi
}

validate_environment() {
  local env_name="${1:-}"
  if [[ "${env_name}" != "dev" ]]; then
    echo "only environment 'dev' is supported in Phase 14; refused: ${env_name}" >&2
    exit 1
  fi
}
