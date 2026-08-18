$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

Write-Host "Performance smoke: entitlement then usage events (profile=smoke, no warmup mix-in)"
& .\performance\scripts\run-benchmark.ps1 -Workload entitlement -Profile smoke -SkipWarmup
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .\performance\scripts\run-benchmark.ps1 -Workload events -Profile smoke -SkipWarmup
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
