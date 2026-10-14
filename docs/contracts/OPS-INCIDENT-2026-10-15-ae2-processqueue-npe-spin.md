# OPS-INCIDENT-2026-10-15 — AE2 `TickHandler.processQueue` NPE spin on island airisminhistory

**Status: CONFIRMED from island evidence (2026-10-15), not reproduced** (owner rule for live-island races). Evidence: a log excerpt
pasted by the owner on 2026-10-15. The nestworld-compat-ae2 addon is **not** deployed on islands.

## Observation (from the owner's paste)
- `01:52:15` `[Server thread/WARN] [AE2:S/]: Exception: java.lang.NullPointerException: null`,
  repeated hundreds of times within the same second.
- No stack trace. That fits HotSpot's `OmitStackTraceInFastThrow` after repeated identical NPEs.
- Next log lines: the server starting again at `01:53:50` (coremod lines, CPU planner
  `CRITICAL 2.05x oversubscription`). A restart in between: watchdog, crash or wrapper restart —
  UNKNOWN which.
- `/tmp/nestworld-ae2-diagnostic.txt` (14 MB, 01:58) exists on the island. Its contents have not
  been seen.

## Attribution
- **Which AE2 site.** `[AE2:S/]: Exception: <throwable>` is `AELog.warn(Throwable)`. Its
  server-side, server-thread call sites in AE2 15.4.10:
  - **`TickHandler.processQueue`** (`catch (Exception) { AELog.warn(e) }`);
  - `CachedPlane` (spatial IO transfer only);
  - `AEBaseBlockEntity.readUpdateData` is client-side update-packet handling.
- **STRONGLY INDICATED: `processQueue`.**

**Why it spins rather than logging once (CONFIRMED from code):**
```java
while (!queue.isEmpty()) {
    try {
        queue.poll().call(level);          // NPE if poll() returns null
        ...; if (stopWatch > 25 ms) break; // budget check only on the success path
    } catch (Exception e) { AELog.warn(e); }   // no break here
}
```
- `ArrayDeque.pollFirst()` returns `null` **without advancing `head`** when the head slot is null.
- If concurrent unsynchronized `add` / `poll` corrupt the deque (head slot null while `head != tail`),
  `isEmpty()` stays false and `poll()` keeps returning null.
- So the loop never terminates: NPE, warn, repeat. **The server thread is stuck in an infinite loop
  inside `processQueue`.** A restart is the expected outcome.

**Root cause class: AE2-COMPAT-QUEUE-001** (recorded 2026-10-14, implemented in the addon).
- `serverQueue` is an unsynchronized `ArrayDeque`.
- It is fed by `addCallable(null, …)` from `AEBaseBlockEntity.saveChanges` on whichever thread
  changes an AE2 block entity: region threads (unpinned), or external inserters' threads (Pipez and
  similar).
- It is drained on main.
- QUEUE-001 predicted "`poll()` returning null → NPE swallowed by `AELog.warn`". This incident is
  that hazard, and worse than predicted: **an infinite main-thread spin, not just lost callbacks.**
- The `callQueue[level]` path, with the same pattern, is equally possible.

Confidence: **CONFIRMED** (owner-run read-only checks, 2026-10-15):
- **NestWorld watchdog thread dump** `crash-reports/threaddump-2026-10-15_01.51.59-server.txt`:
  - Reason: `watchdog api-world: Minecraft мовчить 92 с (hung)`.
  - Run ID `39e8c798-336a-468f-a7cb-ff59a3df61ef`; 3 snapshots, 7 s apart.
  - Server thread at `appeng.hooks.ticking.TickHandler.processQueue(TickHandler.java:451)` ←
    `onServerTickEnd(:334)` ← `onServerTick(:299)`, in the snapshots (lines 358 and 85169 of the
    dump). So it is the **`serverQueue`** drain (server END), the `addCallable(null, …)` path fed by
    `AEBaseBlockEntity.saveChanges`.
  - The NPE spam already appears in the dump's interleaved log at 01:51:59.
- **Volume:** `AE2:S/]: Exception:` lines — `debug-1.log.gz` 1 955 766, `debug-2.log.gz` 210 273.
  The spin ran for at least ~92 s before the watchdog, then a restart (01:53:50).
- Still UNKNOWN: which thread corrupted the deque. Read-only follow-up:
  `grep -n "addCallable\|saveChanges\|ArrayDeque" crash-reports/threaddump-2026-10-15_01.51.59-server.txt | head`.


## Read-only checks for the owner (on the island)
```
grep -c "AE2:S/]: Exception: " logs/latest.log logs/*.log.gz 2>/dev/null; zgrep -c "AE2:S/]: Exception: " logs/*.log.gz 2>/dev/null | tail -5
ls -lt crash-reports | head -5
grep -l "processQueue" crash-reports/* 2>/dev/null | head
grep -n -m5 "processQueue\|TickHandler" /tmp/nestworld-ae2-diagnostic.txt
head -40 /tmp/nestworld-ae2-diagnostic.txt
```

## Mitigation options (owner decision; nothing applied)
- **`/nestworld pinmod ae2` alone is insufficient.**
  - It keeps AE2 block-entity ticks on main.
  - But external inserters (Pipez, other mods' block entities) still call AE2 block-entity methods
    such as `onChangeInventory` → `saveChanges` → `addCallable` on **their** region threads.
  - The writer set is "any thread that touches an AE2 block entity", not only AE2 ticks.
- **Diagnostics:** run the islands with `-XX:-OmitStackTraceInFastThrow`. That was already proposed
  in OPS-INCIDENT-2026-10-05; it is what removed the stack here.
- **Structural fix:** QUEUE-001 staging (`nestworld-compat-ae2`, `cbbb6577f`). It is not deployed;
  islands need the owner-approved canary path. Until then the island remains exposed whenever AE2
  block entities are touched off-main.
