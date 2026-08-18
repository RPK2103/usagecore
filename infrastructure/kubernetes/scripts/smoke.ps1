param(
    [string]$Namespace = "usagecore"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
Set-Location $repoRoot

function Wait-PortForwardReady {
    param([string]$Url, [int]$TimeoutSec = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null
            return $true
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

function Get-KeycloakToken {
    param([string]$TokenUrl)
    $body = @{
        client_id = "usagecore-control-plane"
        username = "acme-developer"
        password = "acme-developer"
        grant_type = "password"
    }
    $resp = Invoke-RestMethod -Method Post -Uri $TokenUrl -Body $body -ContentType "application/x-www-form-urlencoded"
    return $resp.access_token
}

Write-Host "Starting port-forwards (background jobs)..."
$pfKeycloak = Start-Job { kubectl port-forward -n usagecore svc/keycloak 18081:8081 2>&1 | Out-Null }
$pfEntitlement = Start-Job { kubectl port-forward -n usagecore svc/usagecore-entitlement-runtime 18082:8082 2>&1 | Out-Null }
$pfUsage = Start-Job { kubectl port-forward -n usagecore svc/usagecore-usage-pipeline 18083:8083 2>&1 | Out-Null }

Start-Sleep -Seconds 3

if (-not (Wait-PortForwardReady "http://127.0.0.1:18082/actuator/health/readiness")) {
    throw "Entitlement runtime not ready via port-forward"
}
if (-not (Wait-PortForwardReady "http://127.0.0.1:18083/actuator/health/readiness")) {
    throw "Usage pipeline not ready via port-forward"
}

Write-Host "Seeding performance dataset against cluster PostgreSQL..."
$env:USAGECORE_PERF_JDBCURL = "jdbc:postgresql://127.0.0.1:15432/usagecore"
$pfPostgres = Start-Job { kubectl port-forward -n usagecore svc/postgres 15432:5432 2>&1 | Out-Null }
Start-Sleep -Seconds 2
& .\mvnw.cmd -pl performance exec:java "-Dexec.mainClass=io.usagecore.performance.seed.PerformanceDatasetSeeder" -q
if ($LASTEXITCODE -ne 0) {
    Get-Job | Stop-Job -ErrorAction SilentlyContinue
    Get-Job | Remove-Job -Force -ErrorAction SilentlyContinue
    throw "Seed failed"
}

$tokenUrl = "http://127.0.0.1:18081/realms/usagecore/protocol/openid-connect/token"
Write-Host "Fetching JWT from Keycloak..."
$token = Get-KeycloakToken $TokenUrl
if (-not $token) { throw "No token" }

$headers = @{
    Authorization = "Bearer $token"
    "Content-Type" = "application/json"
    "X-Correlation-Id" = "k8s-smoke-1"
}

Write-Host "Entitlement check..."
$entBody = '{"productKey":"datapilot-cloud","featureKey":"scheduled_exports","requestedUnits":1}'
$ent = Invoke-WebRequest -Method Post -Uri "http://127.0.0.1:18082/api/v1/entitlements/check" -Headers $headers -Body $entBody -UseBasicParsing
Write-Host "  HTTP $($ent.StatusCode)"

$idempotencyKey = "k8s-smoke-" + [guid]::NewGuid().ToString()
$usageBody = @{
    productKey = "datapilot-cloud"
    meterKey = "scheduled_export"
    quantity = 1
    occurredAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    idempotencyKey = $idempotencyKey
} | ConvertTo-Json

Write-Host "Usage event..."
$usage = Invoke-WebRequest -Method Post -Uri "http://127.0.0.1:18083/api/v1/usage/events" -Headers $headers -Body $usageBody -UseBasicParsing
Write-Host "  HTTP $($usage.StatusCode) $($usage.Content)"

Write-Host "Waiting for async processing..."
Start-Sleep -Seconds 8

Write-Host "Usage consume..."
$occurredAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$consumeBody = "{`"productKey`":`"datapilot-cloud`",`"meterKey`":`"scheduled_export`",`"quantity`":1,`"occurredAt`":`"$occurredAt`",`"idempotencyKey`":`"k8s-consume-smoke-1`"}"
try {
    $consume = Invoke-WebRequest -Method Post -Uri "http://127.0.0.1:18083/api/v1/usage/consume" -Headers $headers -Body $consumeBody -UseBasicParsing
    Write-Host "  HTTP $($consume.StatusCode) $($consume.Content)"
} catch {
    Write-Host "  consume response: $($_.Exception.Response.StatusCode.value__) (may be business reject)"
}

Write-Host "Smoke complete."

Get-Job | Stop-Job -ErrorAction SilentlyContinue
Get-Job | Remove-Job -Force -ErrorAction SilentlyContinue
