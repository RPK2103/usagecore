#!/usr/bin/env bash
# Bounded post-deploy smoke. Authenticated entitlement + usage event + consume.
# /actuator/health alone is not sufficient.
# OIDC token URL and JWT are supplied by the operator/environment; passwords
# are not logged.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl jq

ENTITLEMENT_URL="${USAGECORE_SMOKE_ENTITLEMENT_URL:-}"
USAGE_URL="${USAGECORE_SMOKE_USAGE_URL:-}"
TOKEN_URL="${USAGECORE_SMOKE_TOKEN_URL:-}"
CLIENT_ID="${USAGECORE_SMOKE_CLIENT_ID:-}"
USERNAME="${USAGECORE_SMOKE_USERNAME:-}"
PASSWORD="${USAGECORE_SMOKE_PASSWORD:-}"

if [[ -z "${ENTITLEMENT_URL}" || -z "${USAGE_URL}" || -z "${TOKEN_URL}" || -z "${CLIENT_ID}" || -z "${USERNAME}" || -z "${PASSWORD}" ]]; then
  echo "smoke requires USAGECORE_SMOKE_ENTITLEMENT_URL, USAGECORE_SMOKE_USAGE_URL," >&2
  echo "USAGECORE_SMOKE_TOKEN_URL, USAGECORE_SMOKE_CLIENT_ID, USAGECORE_SMOKE_USERNAME, USAGECORE_SMOKE_PASSWORD" >&2
  exit 1
fi

token_response="$(curl -sS -X POST "${TOKEN_URL}" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "client_id=${CLIENT_ID}" \
  --data-urlencode "username=${USERNAME}" \
  --data-urlencode "password=${PASSWORD}" \
  --data-urlencode "grant_type=password")"

token="$(printf '%s' "${token_response}" | jq -r '.access_token // empty')"
if [[ -z "${token}" ]]; then
  echo "failed to obtain access token" >&2
  exit 1
fi

entitlement_code="$(curl -sS -o /tmp/usagecore-entitlement-smoke.json -w '%{http_code}' \
  -X POST "${ENTITLEMENT_URL}/api/v1/entitlements/check" \
  -H "Authorization: Bearer ${token}" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: aws-smoke-entitlement" \
  -d '{"productKey":"datapilot-cloud","featureKey":"scheduled_exports","requestedUnits":1}')"

if [[ "${entitlement_code}" != "200" ]]; then
  echo "entitlement check expected HTTP 200, got ${entitlement_code}" >&2
  exit 1
fi

idempotency="aws-smoke-$(date +%s)-$RANDOM"
occurred_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
usage_code="$(curl -sS -o /tmp/usagecore-usage-smoke.json -w '%{http_code}' \
  -X POST "${USAGE_URL}/api/v1/usage/events" \
  -H "Authorization: Bearer ${token}" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: aws-smoke-usage" \
  -d "{\"productKey\":\"datapilot-cloud\",\"meterKey\":\"scheduled_export\",\"quantity\":1,\"occurredAt\":\"${occurred_at}\",\"idempotencyKey\":\"${idempotency}\"}")"

if [[ "${usage_code}" != "202" ]]; then
  echo "usage event expected HTTP 202, got ${usage_code}" >&2
  exit 1
fi

sleep 8

consume_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
consume_code="$(curl -sS -o /tmp/usagecore-consume-smoke.json -w '%{http_code}' \
  -X POST "${USAGE_URL}/api/v1/usage/consume" \
  -H "Authorization: Bearer ${token}" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: aws-smoke-consume" \
  -d "{\"productKey\":\"datapilot-cloud\",\"meterKey\":\"scheduled_export\",\"quantity\":1,\"occurredAt\":\"${consume_at}\",\"idempotencyKey\":\"aws-consume-${idempotency}\"}")"

if [[ "${consume_code}" != "200" && "${consume_code}" != "409" ]]; then
  echo "usage consume expected HTTP 200 or business 409, got ${consume_code}" >&2
  exit 1
fi

echo "Smoke passed: entitlement=${entitlement_code} usage=${usage_code} consume=${consume_code}"
