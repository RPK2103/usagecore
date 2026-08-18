param(
    [ValidateSet("kafka-outage", "postgres-outage", "pending-outbox-restart", "scale", "rollout", "all")]
    [string]$Drill = "all",
    [string]$Namespace = "usagecore"
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
Set-Location $repoRoot

function Invoke-Sql {
    param([string]$Query)
    kubectl exec -n $Namespace deploy/postgres -- psql -U usagecore -d usagecore -t -A -c $Query
}

function Get-UsagePipelinePod {
    (kubectl get pods -n $Namespace -l app.kubernetes.io/name=usage-pipeline -o jsonpath='{.items[0].metadata.name}')
}

Write-Host "=== UsageCore Kubernetes failure drills (namespace=$Namespace) ==="

if ($Drill -in @("kafka-outage", "all")) {
    Write-Host "`n--- Kafka outage drill ---"
    kubectl scale deployment kafka -n $Namespace --replicas=0
    Start-Sleep -Seconds 5
    $ready = kubectl get pods -n $Namespace -l app.kubernetes.io/name=usage-pipeline -o jsonpath='{.items[0].status.conditions[?(@.type=="Ready")].status}'
    Write-Host "Usage Pipeline readiness (expect True): $ready"
    kubectl scale deployment kafka -n $Namespace --replicas=1
    kubectl rollout status deployment/kafka -n $Namespace --timeout=180s
    Write-Host "Kafka restored."
}

if ($Drill -in @("postgres-outage", "all")) {
    Write-Host "`n--- PostgreSQL outage drill ---"
    kubectl scale deployment postgres -n $Namespace --replicas=0
    Start-Sleep -Seconds 10
    $ready = kubectl get pods -n $Namespace -l app.kubernetes.io/name=usage-pipeline -o jsonpath='{.items[0].status.conditions[?(@.type=="Ready")].status}'
    $live = kubectl get pods -n $Namespace -l app.kubernetes.io/name=usage-pipeline -o jsonpath='{.items[0].status.containerStatuses[0].state.running}'
    Write-Host "Usage Pipeline readiness (expect False): $ready"
    Write-Host "Usage Pipeline running (liveness, expect present): $(if ($live) { 'running' } else { 'not running' })"
    kubectl scale deployment postgres -n $Namespace --replicas=1
    kubectl rollout status deployment/postgres -n $Namespace --timeout=180s
    Start-Sleep -Seconds 15
    Write-Host "PostgreSQL restored."
}

if ($Drill -in @("pending-outbox-restart", "all")) {
    Write-Host "`n--- Pending outbox pod restart drill ---"
    kubectl scale deployment kafka -n $Namespace --replicas=0
    Start-Sleep -Seconds 3
    Write-Host "Post events while Kafka down (run smoke manually or use curl via port-forward)."
    $pod = Get-UsagePipelinePod
    Write-Host "Deleting usage-pipeline pod: $pod"
    kubectl delete pod $pod -n $Namespace --wait=true
    kubectl wait --for=condition=Ready pod -l app.kubernetes.io/name=usage-pipeline -n $Namespace --timeout=300s
    kubectl scale deployment kafka -n $Namespace --replicas=1
    kubectl rollout status deployment/kafka -n $Namespace --timeout=180s
    Start-Sleep -Seconds 15
    $pending = Invoke-Sql "SELECT count(*) FROM outbox_event WHERE status='PENDING';"
    Write-Host "PENDING outbox rows: $pending"
}

if ($Drill -in @("scale", "all")) {
    Write-Host "`n--- Usage Pipeline scale drill ---"
    foreach ($r in @(1, 2, 3, 1)) {
        kubectl scale deployment usagecore-usage-pipeline -n $Namespace --replicas=$r
        kubectl rollout status deployment/usagecore-usage-pipeline -n $Namespace --timeout=300s
        Write-Host "Replicas=$r ready."
        Start-Sleep -Seconds 3
    }
}

if ($Drill -in @("rollout", "all")) {
    Write-Host "`n--- Rolling deployment drill ---"
    kubectl set env deployment/usagecore-entitlement-runtime -n $Namespace DRILL_MARKER=phase12 --overwrite
    kubectl rollout status deployment/usagecore-entitlement-runtime -n $Namespace --timeout=300s
    Write-Host "Rollout complete."
}

Write-Host "`nDrills finished. Inspect database state manually with kubectl exec postgres psql."
