param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("start", "dump", "stop")]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [string]$MainClassContains,

    [string]$RecordingName = "usagecore-lab",

    [string]$DumpPath = "performance/target/jfr/recording.jfr"
)

$ErrorActionPreference = "Stop"
$jps = Get-Command jps -ErrorAction SilentlyContinue
if (-not $jps) {
    throw "jps not found. Use a full JDK, not a JRE."
}

$line = & jps -l | Select-String $MainClassContains | Select-Object -First 1
if (-not $line) {
    throw "No JVM found whose main class contains '$MainClassContains'. Start the application first."
}

$jvmPid = ($line.ToString() -split "\s+")[0]
Write-Host "pid=$jvmPid line=$line"

switch ($Action) {
    "start" {
        & jcmd $jvmPid JFR.start name=$RecordingName settings=profile dumponexit=true filename=$DumpPath maxsize=64m
    }
    "dump" {
        New-Item -ItemType Directory -Force -Path (Split-Path $DumpPath) | Out-Null
        & jcmd $jvmPid JFR.dump name=$RecordingName filename=$DumpPath
    }
    "stop" {
        & jcmd $jvmPid JFR.stop name=$RecordingName
    }
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
