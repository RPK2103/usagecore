$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

& .\mvnw.cmd -pl performance exec:java "-Dexec.mainClass=io.usagecore.performance.db.ExplainAnalyzeCapture"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
