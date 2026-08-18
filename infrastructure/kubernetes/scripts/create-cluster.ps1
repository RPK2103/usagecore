$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$config = Join-Path $PSScriptRoot "..\kind-config.yaml"
$clusterName = "usagecore-local"

Set-Location $repoRoot

if (Get-Command kind -ErrorAction SilentlyContinue) {
    $kind = "kind"
} else {
    $kind = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\kind.exe"
    if (-not (Test-Path $kind)) { throw "kind not found. Install with: winget install Kubernetes.kind" }
}

& $kind get clusters 2>$null | ForEach-Object {
    if ($_ -eq $clusterName) {
        Write-Host "Cluster '$clusterName' already exists. Skipping create."
        exit 0
    }
}

Write-Host "Creating kind cluster '$clusterName'..."
& $kind create cluster --name $clusterName --config $config
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Cluster ready. Context:"
kubectl cluster-info --context "kind-$clusterName"
