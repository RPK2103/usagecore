param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("entitlement", "events", "consume", "burst")]
    [string]$Workload,

    [ValidateSet("warmup", "smoke", "baseline", "ramp", "sustained", "burst", "contention")]
    [string]$Profile = "smoke",

    [string]$RunId = ("run-" + (Get-Date -Format "yyyyMMdd-HHmmss")),

    [switch]$SkipWarmup
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

$simulation = switch ($Workload) {
    "entitlement" { "io.usagecore.performance.gatling.EntitlementCheckSimulation" }
    "events" { "io.usagecore.performance.gatling.UsageEventsSimulation" }
    "consume" { "io.usagecore.performance.gatling.UsageConsumeSimulation" }
    "burst" { "io.usagecore.performance.gatling.IngestionBurstSimulation" }
}

function Invoke-Gatling([string]$ProfileName) {
    Write-Host "Running $simulation profile=$ProfileName runId=$RunId"
    $mvnArgs = @(
        "-pl", "performance",
        "gatling:test",
        "-Dgatling.simulationClass=$simulation",
        "-Dusagecore.perf.profile=$ProfileName",
        "-Dusagecore.perf.runId=$RunId"
    )
    if ($Workload -eq "consume" -and $ProfileName -eq "contention") {
        $mvnArgs += "-Dusagecore.perf.meterKey.consume=quota_contention"
    }
    & .\mvnw.cmd @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Gatling failed for profile $ProfileName" }
}

if (-not $SkipWarmup -and $Profile -notin @("smoke", "warmup")) {
    Invoke-Gatling "warmup"
}

Invoke-Gatling $Profile

& .\mvnw.cmd -pl performance exec:java "-Dexec.mainClass=io.usagecore.performance.report.GatlingReportSummarizer" "-Dexec.args=performance/target/gatling"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
