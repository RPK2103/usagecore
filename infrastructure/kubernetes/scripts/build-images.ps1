param(
    [string]$Tag = "phase12"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$dockerfile = Join-Path $repoRoot "infrastructure\docker\Dockerfile.workload"

Set-Location $repoRoot

$workloads = @(
    @{ Name = "control-plane"; Port = 8080 },
    @{ Name = "entitlement-runtime"; Port = 8082 },
    @{ Name = "usage-pipeline"; Port = 8083 }
)

foreach ($w in $workloads) {
    $image = "usagecore/$($w.Name):$Tag"
    Write-Host "Building $image ..."
    docker build -f $dockerfile `
        --build-arg "WORKLOAD=$($w.Name)" `
        --build-arg "SERVER_PORT=$($w.Port)" `
        -t $image `
        $repoRoot
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    docker image inspect $image --format "{{.Id}} {{.Size}} {{.Config.User}}" | Write-Host
}

Write-Host "All images built with tag '$Tag'."
