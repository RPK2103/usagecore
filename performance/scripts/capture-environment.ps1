$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

Write-Host "=== Java / OS (from lab JVM) ==="
& .\mvnw.cmd -pl performance exec:java "-Dexec.mainClass=io.usagecore.performance.observe.EnvironmentCapture"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "=== java -version ==="
java -version

Write-Host ""
Write-Host "=== docker version ==="
docker version

Write-Host ""
Write-Host "=== docker compose config ==="
docker compose -f infrastructure/docker/docker-compose.yml config --quiet
if ($LASTEXITCODE -eq 0) {
    Write-Host "compose config: ok"
}

Write-Host ""
Write-Host "=== docker stats snapshot (one read) ==="
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"
