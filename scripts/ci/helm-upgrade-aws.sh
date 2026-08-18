#!/usr/bin/env bash
# Helm upgrade of the Phase 12 chart with the AWS overlay.
# Control Plane is applied first so Flyway can complete before the other workloads.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CHART="${ROOT}/infrastructure/kubernetes/helm/usagecore"
NAMESPACE="${1:-usagecore}"
IMAGE_TAG="${2:-}"
ECR_REGISTRY="${3:-}"
GIT_SHA="${4:-${IMAGE_TAG}}"
IMAGE_DIGEST="${5:-}"
DB_HOST="${6:-}"
KAFKA_BOOTSTRAP="${7:-}"
JWK_SET_URI="${8:-}"
ALB_SG_ID="${9:-}"

validate_image_tag "${IMAGE_TAG}"
require_cmd helm kubectl

if [[ -z "${ECR_REGISTRY}" || -z "${DB_HOST}" || -z "${KAFKA_BOOTSTRAP}" || -z "${JWK_SET_URI}" ]]; then
  echo "usage: helm-upgrade-aws.sh <ns> <tag> <ecr-registry> <git-sha> <digest-or-empty> <db-host> <kafka-bootstrap> <jwk-uri> <alb-sg>" >&2
  exit 1
fi

common_args=(
  --namespace "${NAMESPACE}"
  --create-namespace
  -f "${CHART}/values-aws.yaml"
  --set "images.controlPlane.repository=${ECR_REGISTRY}/usagecore-control-plane"
  --set "images.entitlementRuntime.repository=${ECR_REGISTRY}/usagecore-entitlement-runtime"
  --set "images.usagePipeline.repository=${ECR_REGISTRY}/usagecore-usage-pipeline"
  --set "images.controlPlane.tag=${IMAGE_TAG}"
  --set "images.entitlementRuntime.tag=${IMAGE_TAG}"
  --set "images.usagePipeline.tag=${IMAGE_TAG}"
  --set "release.gitSha=${GIT_SHA}"
  --set "release.imageTag=${IMAGE_TAG}"
  --set "release.imageDigest=${IMAGE_DIGEST}"
  --set "config.dbHost=${DB_HOST}"
  --set "config.kafkaBootstrapServers=${KAFKA_BOOTSTRAP}"
  --set "config.jwkSetUri=${JWK_SET_URI}"
  --set "ingress.albSecurityGroupId=${ALB_SG_ID}"
  --wait
  --timeout 10m
)

echo "Helm lint"
helm lint "${CHART}" -f "${CHART}/values-aws.yaml"

echo "Deploy Control Plane (Flyway owner) first"
helm upgrade --install usagecore "${CHART}" \
  "${common_args[@]}" \
  --set entitlementRuntime.enabled=false \
  --set usagePipeline.enabled=false

kubectl rollout status "deployment/usagecore-control-plane" -n "${NAMESPACE}" --timeout=300s

echo "Deploy remaining workloads"
helm upgrade --install usagecore "${CHART}" \
  "${common_args[@]}" \
  --set entitlementRuntime.enabled=true \
  --set usagePipeline.enabled=true

kubectl rollout status "deployment/usagecore-control-plane" -n "${NAMESPACE}" --timeout=180s
kubectl rollout status "deployment/usagecore-entitlement-runtime" -n "${NAMESPACE}" --timeout=180s
kubectl rollout status "deployment/usagecore-usage-pipeline" -n "${NAMESPACE}" --timeout=180s

echo "Helm release usagecore is ready in ${NAMESPACE}."
