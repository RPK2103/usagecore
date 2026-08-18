# JVM profiling guide (JFR)

Use JDK 21 built-in Flight Recorder. Do not add commercial APM agents for this lab.

`.jfr` files are gitignored. Commit **summaries**, not binaries.

## Start with the process

```powershell
# Optional: record from process start
java "-XX:StartFlightRecording=name=lab,filename=performance/target/jfr/usage-pipeline.jfr,dumponexit=true,settings=profile,maxsize=64m" `
  -jar applications/usage-pipeline/target/usage-pipeline-0.1.0-SNAPSHOT.jar
```

`settings=profile` includes allocation and more CPU samples than `default`. Cap `maxsize` so a forgotten recording cannot fill the disk.

## Attach to a running app

```powershell
.\performance\scripts\jfr.ps1 -Action start -MainClassContains EntitlementRuntimeApplication
.\performance\scripts\jfr.ps1 -Action dump -MainClassContains EntitlementRuntimeApplication -DumpPath performance/target/jfr/entitlement.jfr
.\performance\scripts\jfr.ps1 -Action stop -MainClassContains EntitlementRuntimeApplication
```

Main classes:

- `io.usagecore.controlplane.ControlPlaneApplication`
- `io.usagecore.entitlementruntime.EntitlementRuntimeApplication`
- `io.usagecore.usagepipeline.UsagePipelineApplication`

Equivalent:

```text
jcmd <pid> JFR.start name=lab settings=profile dumponexit=true filename=... maxsize=64m
jcmd <pid> JFR.dump name=lab filename=...
jcmd <pid> JFR.stop name=lab
```

## Inspect a recording

```powershell
jfr summary performance/target/jfr/usage-pipeline.jfr
jfr print --events jdk.CPULoad,jdk.GarbageCollection,jdk.GCPhasePause,jdk.JavaMonitorEnter,jdk.ThreadPark,jdk.SocketRead,jdk.SocketWrite performance/target/jfr/usage-pipeline.jfr
jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB performance/target/jfr/usage-pipeline.jfr
```

JDK Mission Control (optional GUI) can open the same file. Not required.

### Phase 11 lab recording (2026-08-18)

Usage Pipeline JAR started with `-XX:StartFlightRecording=name=lab,...,settings=profile,maxsize=64m`. Dump via `jcmd <pid> JFR.dump`. Duration **730 s**. File gitignored.

Observed (`VERIFIED BY JFR`):

- `jdk.NativeMethodSample` and `jdk.SocketRead` dominate over `jdk.ExecutionSample` — time is in native/I/O (JDBC/Kafka), not a single hot Java method
- `jdk.ThreadPark` frequent (schedulers / Kafka consumer wait)
- `jdk.JavaMonitorEnter` count **2** over the whole recording — in-process monitors were not the quota bottleneck
- G1 `jdk.GCPhasePause` samples roughly **2–17 ms** on this machine (not a production pause SLO)
- Allocation samples present; not used to justify a cache

| Question | Events / views |
| --- | --- |
| CPU hotspots | Method profiling / `jdk.ExecutionSample` |
| Allocation | TLAB / outside-TLAB allocation |
| GC pauses | `jdk.GarbageCollection`, `jdk.GCPhasePause` |
| Lock contention | `jdk.JavaMonitorEnter` |
| Thread blocking | `jdk.ThreadPark`, monitor events |
| Socket/DB wait | `jdk.SocketRead` / `jdk.SocketWrite` (JDBC often shows as socket read to PostgreSQL) |

If samples spend time in `org.postgresql` / socket read rather than application methods, the bottleneck is **not** a Java hotspot — record that and use EXPLAIN / Hikari / outbox metrics next.

## Micrometer (always on with Actuator)

Prefer existing series over new metrics:

- `process_cpu_usage`, `jvm_cpu_usage`
- `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`
- `jvm_threads_live_threads`
- `hikaricp_connections_active`, `_idle`, `_pending`, `_max`, `_timeout_total`
- `http_server_requests_seconds_*`
- `usagecore_outbox_pending`, `usagecore_outbox_publish_total`
- `usagecore_usage_events_processed_total`
- `usagecore_quota_decisions_total`
- `usagecore_entitlement_decisions_total`

```powershell
.\performance\scripts\capture-metrics.ps1
```

No high-cardinality labels (`tenantId`, `eventId`, `correlationId`).

## Production defaults

Do not raise Hikari `maximum-pool-size`, turn off fsync, or weaken Kafka `acks` to make a local chart look better. If a pool change is proposed: measure baseline → change one variable → measure again → document trade-off vs PostgreSQL `max_connections`.
