param(
    [string]$Tag = "phase12",
    [string]$Namespace = "usagecore",
    [switch]$Wait
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$chart = Join-Path $repoRoot "infrastructure\kubernetes\helm\usagecore"

if (Get-Command helm -ErrorAction SilentlyContinue) {
    $helm = "helm"
} else {
    $helm = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\helm.exe"
    if (-not (Test-Path $helm)) { throw "helm not found. Install with: winget install Helm.Helm" }
}

kubectl get namespace $Namespace 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    kubectl create namespace $Namespace
}

Write-Host "Helm lint..."
& $helm lint $chart
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Helm upgrade --install..."
& $helm upgrade --install usagecore $chart `
    --namespace $Namespace `
    --create-namespace `
    --set "images.controlPlane.tag=$Tag" `
    --set "images.entitlementRuntime.tag=$Tag" `
    --set "images.usagePipeline.tag=$Tag" `
    --wait --timeout 10m

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Deployments:"
kubectl get deployments -n $Namespace
kubectl get pods -n $Namespace
