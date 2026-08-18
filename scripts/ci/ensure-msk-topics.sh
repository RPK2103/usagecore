#!/usr/bin/env bash
# Create application Kafka topics on MSK using SASL/SCRAM. Values are not echoed.
#
# GitHub-hosted runners cannot reach private MSK. This script is for an operator
# or bastion inside the VPC. The AWS Helm release creates the same topics via
# the in-cluster kafka-topics-init hook (kafka.saslTopicsJob).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

BOOTSTRAP="${1:-}"
MSK_SECRET_ARN="${2:-}"

if [[ -z "${BOOTSTRAP}" || -z "${MSK_SECRET_ARN}" ]]; then
  echo "usage: ensure-msk-topics.sh <bootstrap> <msk-secret-arn>" >&2
  exit 1
fi

require_cmd aws jq docker

tmpdir="$(mktemp -d)"
cleanup() {
  rm -rf "${tmpdir}"
}
trap cleanup EXIT
umask 077

aws secretsmanager get-secret-value --secret-id "${MSK_SECRET_ARN}" --query SecretString --output text > "${tmpdir}/msk.json"
MSK_USER="$(jq -r '.username' "${tmpdir}/msk.json")"
MSK_PASS="$(jq -r '.password' "${tmpdir}/msk.json")"
if [[ -z "${MSK_USER}" || "${MSK_USER}" == "null" || -z "${MSK_PASS}" || "${MSK_PASS}" == "null" ]]; then
  echo "MSK secret missing username/password" >&2
  exit 1
fi

cat > "${tmpdir}/client.properties" <<EOF
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="${MSK_USER}" password="${MSK_PASS}";
EOF

create_topic() {
  local topic="$1"
  docker run --rm \
    -v "${tmpdir}/client.properties:/tmp/client.properties:ro" \
    apache/kafka:3.8.1 \
    /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server "${BOOTSTRAP}" \
      --command-config /tmp/client.properties \
      --create --if-not-exists \
      --topic "${topic}" \
      --partitions 3 \
      --replication-factor 2
}

create_topic usagecore.usage.received.v1
create_topic usagecore.usage.received.v1.dlq

echo "MSK topics ensured."
