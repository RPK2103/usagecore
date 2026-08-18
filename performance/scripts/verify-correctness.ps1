param(
    [ValidateSet("all", "ingestion", "quota", "drain")]
    [string]$Mode = "all"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

& .\mvnw.cmd -pl performance exec:java "-Dexec.mainClass=io.usagecore.performance.verify.PostRunCorrectnessVerifier" "-Dexec.args=$Mode"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
