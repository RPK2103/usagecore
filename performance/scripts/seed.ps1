param(
    [switch]$ResetQuota
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

$execArgs = @("-pl", "performance", "exec:java", "-Dexec.mainClass=io.usagecore.performance.seed.PerformanceDatasetSeeder")
if ($ResetQuota) {
    $execArgs += "-Dexec.args=--reset-quota"
}

& .\mvnw.cmd @execArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
