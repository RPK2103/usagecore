# Runbook: Kafka unavailable (Kubernetes)

Symptom: Outbox PENDING grows; async processing stalls; HTTP ingestion may still accept events.

## Expected semantics (Usage Pipeline)

- `POST /api/v1/usage/events` → HTTP **202** when PostgreSQL healthy
- Readiness: **UP** (Kafka excluded from readiness group)
- Liveness: **UP**

## Verify

```powershell
kubectl get pods -n usagecore -l app.kubernetes.io/name=kafka
kubectl exec -n usagecore deploy/postgres -- psql -U usagecore -d usagecore -c \
  "SELECT status, count(*) FROM outbox_event GROUP BY status;"
```

## Restore

```powershell
kubectl scale deployment kafka -n usagecore --replicas=1
kubectl rollout status deployment/kafka -n usagecore
```

Publisher scheduler drains PENDING rows. Duplicate transport possible; inbox ensures one business effect.

## Scale note

Multiple usage-pipeline replicas share consumer group `usagecore-usage-pipeline-v1`; outbox uses `FOR UPDATE SKIP LOCKED`.

Do **not** manually mark outbox rows PUBLISHED in SQL.
