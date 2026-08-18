#!/usr/bin/env bash
# Install AWS Load Balancer Controller using the Terraform Pod Identity role.
# Not part of the application Helm chart.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

CLUSTER_NAME="${1:-}"
AWS_REGION="${2:-}"
CONTROLLER_ROLE_ARN="${3:-}"
VPC_ID="${4:-}"

if [[ -z "${CLUSTER_NAME}" || -z "${AWS_REGION}" || -z "${CONTROLLER_ROLE_ARN}" || -z "${VPC_ID}" ]]; then
  echo "usage: install-alb-controller.sh <cluster> <region> <controller-role-arn> <vpc-id>" >&2
  exit 1
fi

require_cmd helm kubectl

# Pod Identity is associated by Terraform to kube-system/aws-load-balancer-controller.
# Do not annotate the ServiceAccount with the role ARN (that is the IRSA pattern).
echo "Installing AWS Load Balancer Controller; Terraform Pod Identity role ${CONTROLLER_ROLE_ARN}"

helm repo add eks https://aws.github.io/eks-charts
helm repo update eks

helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller \
  --namespace kube-system \
  --create-namespace \
  --set clusterName="${CLUSTER_NAME}" \
  --set region="${AWS_REGION}" \
  --set vpcId="${VPC_ID}" \
  --set serviceAccount.create=true \
  --set serviceAccount.name=aws-load-balancer-controller \
  --wait \
  --timeout 5m

echo "AWS Load Balancer Controller release is ready."
echo "Pod Identity association is owned by Terraform; this chart only installs the controller."
