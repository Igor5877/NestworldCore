# PERF-FINDING-001 — global lock contention on the entity-tick hot path (TickAttribution)

**Status: RECORDED (2026-10-04), found by the PERF-1A pilot calibration. Not fixed.** The rule
"measure first, do not fix during PERF-1" applies. Fixing it is an owner decision and a separate
gate.

## Finding
`RegionThread.tickEntities` (RegionThread.java:1114) calls
`TickAttribution.recordChunkCost(dim, entity.chunkPosition(), nanos)` after **every entity tick
on every region thread**:
- **Data structure:** one JVM-wide `ConcurrentHashMap<Long, LongAdder>` per dimension, keyed by
  `ChunkPos.toLong()`, updated through `computeIfAbsent`.
- **Gating:** none; it is always on.
- **Hash:** `Long.hashCode(ChunkPos.toLong())` = `x XOR z`. Any dense square of chunks maps onto
  very few distinct hashes. Measured with the CHM bin computation:

  | Loaded area | Keys | Table | Bins used | Max per bin | Tree bins (≥8) |
  |---|---|---|---|---|---|
  | 21×21 around a player | 441 | 1024 | 16 | 41 | 16 |
  | 40×40 base anywhere | 1600 | 4096 | 64 | 40 | 64 |
  | PERF-1A W1 (16 cells) | 1600 | 4096 | 61 | 160 | 34 |

- **Locking:** every bin becomes a `TreeBin`. `computeIfAbsent` takes the lock-free path only
  when the key is the bin's first node; otherwise it does `synchronized` on the bin. So almost
  every entity tick takes one of a handful of monitors **shared by all region threads**.

The regions therefore serialise on attribution bookkeeping, not on game state. This is a
Core-level scalability limit independent of the workload's parallelism. It contradicts the
independent-regions premise and is exactly what PERF-1A must surface.

## Evidence
1. **JFR, PERF-1A mechanics run** (N=2 cores, 16 regions × 256 cows, run valid, TPS 15.7, CPU only
   76% used): 298 `jdk.JavaMonitorEnter` events ≥ 20 ms in 60 s, **8.98 s blocked**. 298 of the
   299 events are on `ConcurrentHashMap$TreeBin` at `TickAttribution.recordChunkCost`. Events
   under 20 ms are not recorded, so this is a lower bound.
2. **Snapshot at N=1** (16 regions × 2500 cows, one core). The crash-report thread dump shows
   region threads 7, 8, 9, 13, 14 and others `BLOCKED on ConcurrentHashMap$TreeBin@3bec21e4
   owned by NestWorld-Region-15`, all in `TickAttribution.recordChunkCost`. A second convoy is on
   `TreeBin@63897424`, owned by Region-21.

   **Correction (same day):** this record first said the convoy produced a 60 s tick. That is
   wrong:
   - Real ticks were about 1.8 s.
   - The "single server tick took 60 s" is the vanilla `ServerWatchdog` measuring
     `now - nextTickTime`. `MinecraftServer` only catches that lag up once per 15 000 ms of game
     time (300 ticks), so any server sustained below ≈ 4 TPS trips the 60 s limit without any
     long tick.
   - The same watchdog stopped a later pilot run at about 2 TPS whose dump shows the region
     threads RUNNABLE, not blocked.

   The dump remains valid as a snapshot of the convoy. **The watchdog stop is not evidence of this
   finding.** Evidence 1 (JFR) is the measurement.

Run artefacts (scratch): `perf1/mech/mech3.{json,jfr}`, `perf1/cal/cal50-console.log`, and the
crash report `crash-2026-10-04_10.37.14-server.txt`.

## Consequences for PERF-1
- Any scaling curve measured on this build includes this lock. It would answer "does Core
  scale?" with "not with this lock", not with the scheduler's own limit.
- (Corrected) The calibration failure at N=1 was the vanilla watchdog, not this lock. The
  harness needs `max-tick-time=-1`, because its N=1 point is deliberately below 4 TPS.
- `recordEntityTick` (same class) uses `modNanos` / `modCounts` with very few keys (one per mod
  namespace). Its `computeIfAbsent` mostly hits the lock-free first-node path. It has not been
  measured separately and is not cleared.

## Not decided here (owner)
- Whether PERF-1A measures the current build as is (curve includes this lock) or waits for a fix.
  The latter means a new reference build, so the trusted artifact / canary / smoke chain is redone.
- Fix direction, if any: for example region-confined accumulation merged by the main thread, a
  spread hash, gating behind a flag, or sampling. No code change is proposed in this record.
