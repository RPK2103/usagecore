param(
    [string]$Tag = "phase12",
    [string]$ClusterName = "usagecore-local"
)

$ErrorActionPreference = "Stop"

if (Get-Command kind -ErrorAction SilentlyContinue) {
    $kind = "kind"
} else {
    $kind = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\kind.exe"
    if (-not (Test-Path $kind)) { throw "kind not found." }
}

$images = @(
    "usagecore/control-plane:$Tag",
    "usagecore/entitlement-runtime:$Tag",
    "usagecore/usage-pipeline:$Tag"
)

foreach ($image in $images) {
    Write-Host "Loading $image into kind cluster '$ClusterName'..."
    & $kind load docker-image $image --name $ClusterName
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "Images loaded."
