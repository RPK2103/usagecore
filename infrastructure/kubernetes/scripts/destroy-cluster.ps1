$ErrorActionPreference = "Stop"
$clusterName = "usagecore-local"

if (Get-Command kind -ErrorAction SilentlyContinue) {
    $kind = "kind"
} else {
    $kind = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\kind.exe"
}

Write-Host "Deleting kind cluster '$clusterName'..."
& $kind delete cluster --name $clusterName
Write-Host "Done."
