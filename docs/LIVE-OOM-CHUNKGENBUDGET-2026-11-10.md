# Live incident 2026-11-10 — heap exhaustion from `-Dnestworld.chunkGenBudget=2` (my regression)

Status: MITIGATED (flag removed, live restarted 13:40). Root cause in the budgeted branch: OPEN (audit before reuse).

```
timeline     08:40 live restarted with core patched9 + chunkGenBudget=2 (owner asked for a limit; I enabled the flag)
             10:21 host reboot (power) → 10:27 start (addon 0.9.1)
             11:14:59, 12:03 (wrapper log), 12:42:17 ServerHangWatchdog "single tick took 243 s" → auto-restart each time
             13:30 fourth hang; 13:40 manual restart without the flag
symptom      "hang": watchdog stacks at three unrelated places (Level.getChunk, DistanceManager:587, tickChunks)
cause        CONFIRMED (gc.log + jmap -histo, /root/infinity-monitor/hang-2026-11-10-1336.histo): heap 18399M / 18432M,
             789 back-to-back Full GCs (~3.7 s each, reclaiming nothing). Live objects: 10 383 384 ChunkHolder,
             12 061 527 DistanceManager$Tier2Candidate, 22 M CompletableFuture, 23.6 M long[].
             Tier2Candidate exists only in the CHUNK_GEN_BUDGET > 0 branch of DistanceManager.runAllUpdates.
mechanism    STRONGLY INDICATED: with a bulk budget of 2 promotions / tick the bulk backlog grows faster than it drains
             (who requests ~10 M bulk holders is UNKNOWN — candidate: tickets of the always-loaded base / other
             dimensions); holders waiting in the backlog are never completed, so never unloaded; the ready-heap keeps a
             candidate per push. ~45–50 min from start to a full 18 GB heap.
not the cause  addon 0.9.1 (no addon classes in the top of the histogram); GC tuning; the watchdog (it did its job)
fix now      flag removed → default CHUNK_GEN_BUDGET = 0 (the branch that ran for days). After restart: 14 961 ChunkHolder,
             heap 1.65 GB.
lessons      (1) the code comment says the deployed default is 2 — it is 0; the budgeted branch had no live soak on this
             pack; never enable it on live without one. (2) monitor now alerts on Full-GC thrash (watch-critical.sh).
             (3) max-tick-time=240 s only delayed the restart of a dead JVM — heap alerts are the right signal.
```
