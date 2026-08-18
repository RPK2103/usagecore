param(
    [string]$Url = "http://localhost:8083/actuator/prometheus"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

& .\mvnw.cmd -pl performance exec:java "-Dexec.mainClass=io.usagecore.performance.observe.MetricsSnapshot" "-Dexec.args=$Url"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
