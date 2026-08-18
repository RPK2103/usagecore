#!/usr/bin/env bash
# Retrieve RDS and MSK credentials from Secrets Manager and apply Kubernetes Secret
# usagecore-secrets. Secret values are not printed. Temporary files are deleted.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

NAMESPACE="${1:-usagecore}"
RDS_SECRET_ARN="${2:-}"
MSK_SECRET_ARN="${3:-}"

require_cmd aws jq kubectl python3

if [[ -z "${RDS_SECRET_ARN}" || -z "${MSK_SECRET_ARN}" ]]; then
  echo "usage: sync-runtime-secrets.sh <namespace> <rds-secret-arn> <msk-secret-arn>" >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
cleanup() {
  rm -rf "${tmpdir}"
}
trap cleanup EXIT

umask 077

aws secretsmanager get-secret-value --secret-id "${RDS_SECRET_ARN}" --query SecretString --output text > "${tmpdir}/rds.json"
aws secretsmanager get-secret-value --secret-id "${MSK_SECRET_ARN}" --query SecretString --output text > "${tmpdir}/msk.json"

python3 - "${tmpdir}" <<'PY'
import json, os, sys, pathlib
d = pathlib.Path(sys.argv[1])
rds = json.loads((d / "rds.json").read_text())
msk = json.loads((d / "msk.json").read_text())
username = rds.get("username")
password = rds.get("password")
msk_user = msk.get("username")
msk_pass = msk.get("password")
if not username or not password:
    raise SystemExit("RDS secret missing username/password")
if not msk_user or not msk_pass:
    raise SystemExit("MSK secret missing username/password")
jaas = (
    'org.apache.kafka.common.security.scram.ScramLoginModule required '
    f'username="{msk_user}" password="{msk_pass}";'
)
(d / "USAGECORE_DB_USERNAME").write_text(username, encoding="utf-8")
(d / "USAGECORE_DB_PASSWORD").write_text(password, encoding="utf-8")
(d / "USAGECORE_KAFKA_SASL_JAAS_CONFIG").write_text(jaas, encoding="utf-8")
PY

kubectl create secret generic usagecore-secrets \
  --namespace "${NAMESPACE}" \
  --from-file=USAGECORE_DB_USERNAME="${tmpdir}/USAGECORE_DB_USERNAME" \
  --from-file=USAGECORE_DB_PASSWORD="${tmpdir}/USAGECORE_DB_PASSWORD" \
  --from-file=USAGECORE_KAFKA_SASL_JAAS_CONFIG="${tmpdir}/USAGECORE_KAFKA_SASL_JAAS_CONFIG" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Kubernetes secret usagecore-secrets applied in namespace ${NAMESPACE}."
