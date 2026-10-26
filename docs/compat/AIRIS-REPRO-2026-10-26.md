# Airis repro on isolated copies — 2026-10-26

## Status (owner wording, 2026-10-26)

```
MEKANISM-REGISTRY-RACE-001
Root cause:               CONFIRMED
Live reproduction:        CONFIRMED
A234 negative control:    CONFIRMED
B307 negative control:    CONFIRMED
M-A deterministic harness: VERIFIED
M-A live stress:          VERIFIED for tested producer paths
Player-driven paths:      NOT EXERCISED
Production addon:         NOT YET IMPLEMENTED (nestworld-compat-mekanism)
Overall:                  OPEN / ROOT CAUSE CONFIRMED / FIX STRONGLY VERIFIED

AE2 TickManager queue race (A234):  CONFIRMED
B307 + AE2 addon, same symptom:     NOT OBSERVED (contended path exercised)
causal prevention by the addon:     CORRELATED, not PROVEN
```

Owner decisions: M-A algorithm APPROVED; delivery = separate Mekanism addon with a dedicated `@Unique`
lock and an exact compatibility gate; `mod_compound_races.js` in Core production: NO (868be3340 stays an
oracle/reference); main-only collections (R2/R4): NOT patched, observe only; GMEIL docs fixed
(`docs/MOD_COMPATIBILITY_FIXES.md`). AE2 and Mekanism are independent gates (B307 with the AE2 addon
still crashed on Mekanism).

Source: owner-uploaded Airis backup `skyblock-team-island-of-airisminhistory-64aa241d-25-2026-10-25T21-52-31.tar.gz`.
Workspace `/root/airis-2026-10-25/` (copies only; no live server touched).

| instance | Core | addon | CPUs | notes |
|---|---|---|---|---|
| A234 | 47.4.234 (as live) | none | 0-2 | pins as live (exdeorum only) |
| B307 | 47.4.307-canary-core-306-ownership | nestworld-compat-ae2 0.1.0 | 3-5 | same world/pins |

Test-only deviations on both copies: playersync disabled (no MySQL in netns), loopback-only netns,
`-XX:-OmitStackTraceInFastThrow`, forceload of the base chunks, observation-only detector mod
`compat/nestworld-ae2-race-detector` (counts EnergyService inject/extract overlap; never changes behaviour).
0 players.

## 1. AE2 TickManagerService queue corruption — REPRODUCED on A234 (CONFIRMED)

00:53:35 UTC (~30 s after start), A234, Server thread:

```
NullPointerException: Cannot invoke "TickTracker.getNextTick()" because "t" is null
  TickTracker.compareTo → PriorityQueue.siftUp/offer/add
  TickManagerService.tickQueue(142) ← tickLevelQueue ← onLevelEndTick ← Grid.onLevelEndTick
Grid: 380 nodes, serial 44, GlassCablePart @ -40,113,122
```
4 consecutive tick-skipped reports (02.53.35–38 local), then during shutdown:
- `ChunkMap: Failed to save chunk [1,5]` — same NPE via
  `MEP2PTunnelPart.onTunnelNetworkChange → TickManagerService.wakeDevice → updateQueuePosition → addToQueue`;
- `GridEnergyStorage.removeNode: Removing a node from energy storage while it has no nodes` (ISE,
  `TickHandler.onUnloadLevel → GridNode.destroy`) — energy-storage bookkeeping already inconsistent;
- forced exit after 90 s (`NestWorld-Shutdown-Forced-Exit-Watchdog`).

Interpretation: a `null` element inside the `PriorityQueue` is only possible after unsynchronized concurrent
`add`/`poll`/`remove` — QUEUE class (TickManagerService), entered here also from the ME-P2P network-change
path. This class is covered by the addon (QUEUE-001 staging + P(G)). The live Airis history did not contain
this signature (live: 17× EnergyService.addRequester ISE, 3× Mekanism commit NPE) — new signature, same domain.

Evidence: `/root/airis-2026-10-25/evidence-A234-run1/`.

B307 over the same window: 0 new crash reports, `mode=NORMAL`, `unauditedPaths=0`,
`callbackExceptions=0`, `contended` 671→1860 (the addon serialising real overlap). Detector counters on B307
all 0 (expected: under the addon inject/extract cannot overlap).

Confidence: the A234 failure is CONFIRMED (real stack, real world). "Addon prevents it" is CORRELATED only
(one failure on A, zero on B, short window). A234 was restarted 01:0x UTC to collect repeats.

Detector influence: the detector touches only `EnergyService` inject/extract/requester/provider methods,
not `TickManagerService`; a 30-min A234 run *without* the detector had 0 failures. Timing perturbation by the
detector cannot be excluded (HYPOTHESIS, low).

## 2. Mekanism transmitter network registry — REPRODUCED live; M-A verified as offline oracle; delivery = addon

Live signature (3× on Airis, incl. the 14.10 crash): fastutil iterator NPE in
`NetworkAcceptorCache.commit` on Server thread.

Bytecode facts (Mekanism 10.4.16.80):
- `NetworkAcceptorCache.changedAcceptors` / `cachedAcceptors`: plain `Object2ObjectOpenHashMap`, no locking.
  - `acceptorChanged(t, dir)`: `changedAcceptors.computeIfAbsent(t, EnumSet).add(dir)` then
    `TransmitterNetworkRegistry.registerChangedNetwork(network)`.
  - `commit()`: iterate `changedAcceptors` → `updateTransmitterOnSide` (writes `cachedAcceptors`) → `clear()`.
- `TransmitterNetworkRegistry` (singleton, no locking anywhere): `networksToChange`, `invalidTransmitters`,
  `newOrphanTransmitters`, `transmitters` (Multimap), `networks`, `changedTicketChunks`.
  - consumer: `onTick(ServerTickEvent END)` on Server thread: `handleChangedChunks → removeInvalidTransmitters
    → assignOrphans → commitChanges (→ commit) → networks.onUpdate()`.
- producers (call sites), all reachable from region threads in NestWorld:
  - `registerChangedNetwork` ← `NetworkAcceptorCache.acceptorChanged` ← `DynamicNetwork.acceptorChanged`
    ← `Transmitter.markDirtyAcceptor` ← `onModeChange`, `AbstractAcceptorCache.invalidateCachedAcceptor`
    (capability-invalidation listener), `AcceptorCache.updateCachedAcceptorAndListen`;
  - `invalidateTransmitter` ← `Transmitter.markDirtyTransmitters` (neighbour/redstone change),
    `TileEntityTransmitter.onWorldSeparate` ← `setRemoved`, `chunkAccessibilityChange`;
  - `registerOrphanTransmitter`, `trackTransmitter` ← `TileEntityTransmitter.onWorldJoin` ← `clearRemoved`,
    `onAdded`, `chunkAccessibilityChange`.

Conclusions:
- The live NPE is one symptom of a whole-registry producer/consumer race (C3: region-thread producers,
  main-thread iterate+clear consumer), not of a single map. Fixing only `changedAcceptors` would leave
  `networksToChange` (written in the same call) and the other registry sets racing. STRONGLY INDICATED.
- A Mekanism pin does NOT close it: pins cover ticks only; the producers are BE load/remove, neighbour change,
  capability invalidation and chunk-accessibility callbacks. (Same lesson as AE2 P2P: pin ≠ safe.) CONFIRMED by
  call-site analysis; not yet by a live counter.

Design options (for owner decision):
- **M-A detach-swap + narrow locks** (Draconic `CrystalUpdateBatcher` pattern): every producer write into a
  registry/cache collection under that object's monitor; `onTick` steps and `commit()` swap the collection for
  a fresh one under the monitor and process the detached copy outside it; `registerChangedNetwork` outside the
  cache monitor. No lock is held across world access → no new deadlock edge. Cost: ~6 sites in 2 classes;
  `cachedAcceptors` readers on other threads remain open (no evidence of a failure there yet).
- **M-B producer marshalling**: off-main producer calls are re-posted to the main thread and executed before
  `onTick`. Simple ownership story, but changes the timing of `onWorldJoin` (network join becomes one tick
  later) — semantic risk inside Mekanism, needs its own audit.
- **M-C one global graph lock** around producers and the whole `onTick`: simplest, but `onTick` →
  `networks.onUpdate()` touches BEs in region-owned chunks while holding the lock → deadlock edge with a region
  thread that holds its chunk write lock and waits for the graph lock. Not recommended.

Owner decision 2026-10-26: **M-A APPROVED, detector first REQUIRED; M-B REJECTED (for now); M-C REJECTED.**
After M-A no other Mekanism path is touched until the detector shows this gate closed the observed race.

### Gate MEKANISM-REGISTRY-RACE-001

1. DETECT — every producer path instrumented (thread, op, registry/cache identity, producer↔producer overlap,
   producer↔main-consumer overlap). Done: detector `/nwmekdetect [threads|stacks]` (section 2.2).
2. REPRODUCE — A234/current config: prove writes come from region threads while main consumes; negative
   control without patch (section 2.1).
3. PATCH M-A — short critical section only around mutation/publication; consumer atomically takes ownership
   of the pending generation; processing outside the lock; no callbacks, capability lookup, world access or
   network callbacks under the lock.
4. STRESS — topology churn, BE load/unload, capability invalidation, neighbour changes, concurrent main tick.
5. REGRESSION — no lost update, no duplicate processing, no deadlock, no new main-thread stalls.
   Acceptance after drain: `produced == consumed`, `dropped == 0`, `duplicates == 0` (per generation counters).

### 2.1 Negative control (unit, real bytecode) — CONFIRMED

`tests/coremods/MekanismAcceptorCacheRegression.java`: the real 10.4.16.80 `NetworkAcceptorCache`
`acceptorChanged`/`commit` bytecode; `updateTransmitterOnSide` replaced by an accounting hook; 4 producer
threads (`NestWorld-Region-*`) × 20 000 transmitters × 6 sides vs a `Server thread` commit loop, then a
drain. Bounded (daemon threads, 20 s round deadline; a spin is reported as HANG with stacks).

Unpatched, 3/3 runs, first attempt each (produced = 480 000):

| run | consumed | lost | duplicates | consumer exc. | producer exc. |
|---|---|---|---|---|---|
| 1 | 458 793 | 383 012 | 361 805 | 872 | 28 |
| 2 | 228 697 | 452 081 | 200 778 | 4 111 | 26 |
| 3 | 1 548 183 | 198 180 | 1 266 363 | 599 | 26 |

Producer side fails too (`ArrayIndexOutOfBoundsException` in `Object2ObjectOpenHashMap.rehash/get` under
concurrent `computeIfAbsent`); an earlier unbounded variant spun forever (open-hash corruption livelock).
A 4th harness run produced the livelock variant: `NestWorld-Region-0` RUNNABLE forever inside
`Object2ObjectOpenHashMap.get` ← `computeIfAbsent` ← `acceptorChanged` (on a server: watchdog). Negative control
4/4 (3× corruption, 1× livelock).

Patched (M-A, section 2.5), 4/4 full harness runs: 10 rounds × 480 000 each, `consumed == produced`, `lost=0`,
`duplicates=0`, consumer/producer exceptions 0; adopt moves a pending generation exactly once, deregister drops
it; all transformed methods pass ASM BasicVerifier and the JVM verifier (class defined and executed);
1190 assertions total. Structural assertions: helpers synchronized; `acceptorChanged`/`commit`/`adoptAcceptors`
not synchronized; no `registerChangedNetwork` under the cache monitor.

### 2.3 M-A contract (to implement after detector evidence)

Fact: Mekanism's consumers already detach-swap (`handleChangedChunks`, `removeInvalidTransmitters`,
`assignOrphans`, `commitChanges` each replace the field with a fresh collection), but without synchronization,
and producers mutate **pairs** of collections:
- `invalidateTransmitter`: `invalidTransmitters.add(t)` + `newOrphanTransmitters.remove(coord)`;
- `registerOrphanTransmitter`: `invalidTransmitters.remove(t)` + `newOrphanTransmitters.put(coord, t)`;
- `removeNetwork`: `networks.remove(n)` + `networksToChange.remove(n)`.

Therefore independent per-collection swaps are NOT allowed (a producer between the invalid-swap and the
orphan-swap would see half of generation N). Contract:

- **R1 one registry lock** (`pendingLock`, a dedicated monitor object, never the registry instance itself).
  Every producer mutation of `invalidTransmitters`, `newOrphanTransmitters`, `networksToChange`, `networks`,
  `transmitters`, `changedTicketChunks` runs inside it; paired mutations in one critical section. The
  critical section contains only collection operations (no logging, no Transmitter/world/capability calls
  except pure getters already evaluated before entering — `getTileCoord`/`getTileChunk` are computed first).
- **R2 one publication point per tick**: at the start of `onTick` the consumer, under the lock, takes
  `changedTicketChunks`, `invalidTransmitters`, `newOrphanTransmitters` together (generation N, `generation++`)
  and installs fresh collections; it then processes the detached generation outside the lock. Producers that
  run later land in N+1 = exactly as if they ran after this tick in single-threaded Mekanism.
- **R3 `networksToChange`** keeps Mekanism's own semantics: it is filled during the same tick by
  `assignOrphans` (main) — so it is taken under the lock at the original `commitChanges` point, not at R2.
  A region producer before that point is included in this tick, after it → next tick (both are valid
  single-threaded orders).
- **R4 iteration snapshots**: `networks` (onUpdate loop) and `transmitters.get(chunk)` (handleChangedChunks)
  are copied under the lock and iterated outside; a network removed concurrently linearizes after the copy.
- **R5 `NetworkAcceptorCache`**: `changedAcceptors` mutated under the cache's own monitor; `commit()` swaps it
  under that monitor and processes the detached map outside; `registerChangedNetwork` is called after the
  cache monitor is released (no nested cache→registry lock order; the only order allowed is none).
- **R6 accounting** (`-Dnestworld.mekRegistryAudit=true`, off by default): produced / published / consumed /
  dropped / duplicate per generation; `produced == consumed`, `dropped == 0`, `duplicates == 0` after drain.
- Lock order: no code holds two of {registry lock, any cache monitor} at once → deadlock-free by construction.

### 2.2 Live detector

`compat/nestworld-ae2-race-detector` extended (observation only, `@WrapMethod` calls the original once):
producers TRACK, UNTRACK, INVALIDATE, ORPHAN, CHANGED_NETWORK, NETWORK_ADD, NETWORK_REMOVE, TICKET_CHANGE,
ACCEPTOR_CHANGED, ADOPT, DEREGISTER; consumers `onTick`, `commit`. Counters per op: calls, offMain,
region-thread, whileMainConsuming, concurrentProducer; plus the crash shape
`acceptorChangedDuringForeignCommit`; first off-main / overlap stack per op.

### 2.4 Live detection + reproduction on the Airis copies — CONFIRMED

Idle (no workload): `ACCEPTOR_CHANGED`/`CHANGED_NETWORK` 9/40 off-main, all `NestWorld-Region-0`, stack
`Level.updateNeighbourForOutputSignal → BlockTransmitter.onNeighborChange → Transmitter.refreshConnections →
…markDirtyAcceptor → DynamicNetwork.acceptorChanged → NetworkAcceptorCache.acceptorChanged →
registerChangedNetwork`. TRACK / ORPHAN / INVALIDATE / NETWORK_* / TICKET_CHANGE: 0 off-main (main only).
Region layout: 2 regions, #0 = chunks x ≤ 1 (the whole base), #1 empty.

Workload (test-only mod `compat/nestworld-mek-churn`, never for live servers): 8 driver blocks whose
block-entity ticker (region thread) toggles a `mekanism:basic_energy_cube` next to the advanced universal
cable line at (-10..-3, 115, 100) every 2 ticks. RCON `setblock` was rejected as a workload: it runs on main.

| copy | Core | 30 s | result |
|---|---|---|---|
| A234 | 47.4.234, no addon | 2 433 region producer calls, 91 while main inside `onTick`, **12 `acceptorChanged` while `Server thread` was inside `commit()` of the same cache** | ~2 min: 3× tick-skipped then *Exception in server tick loop*: `NetworkAcceptorCache.commit` → `Object2ObjectOpenHashMap$MapIterator.nextEntry` NPE `"this.wrapped" is null` — **the live Airis signature** |
| B307 | 47.4.307-canary + AE2 addon | 2 417 region calls, 63 during `onTick`, 2 crash-shape overlaps | ~16 min: same signature (3× tick-skipped + server tick loop exception, 04:17:44–47) |

Crash-shape stack (first): `LazyOptional.invalidate → AbstractAcceptorCache$RefreshListener → refreshConnections
→ invalidateCachedAcceptor → markDirtyAcceptor → acceptorChanged` on `NestWorld-Region-0` while `Server thread`
committed the same cache (`cache@fff789`).

Conclusion: the region-thread producer → main consumer race is CONFIRMED live and reproduces the production
signature on two Core versions; the AE2 addon is irrelevant to it. Evidence:
`/root/airis-2026-10-25/evidence-mek-churn-A234/`, `…-B307/`.

### 2.5 M-A as implemented (`mod_compound_races.js` + `NestworldCoremodSupport`) — scope per evidence

Implemented exactly where the detector showed off-main writers, plus the pair that shares the collection:
- `NetworkAcceptorCache` (R5): mutation moved into synthetic `synchronized nestworld$markChanged`;
  `registerChangedNetwork` runs after it, outside the monitor; `commit()` takes the generation through
  `synchronized nestworld$takechangedAcceptors` (swap if non-empty, else empty view) and iterates it unlocked;
  `adoptAcceptors` takes the other cache's generation under ITS monitor, then merges under OURS
  (`nestworld$mergeChanged`); `deregister` takes + drops. `changedAcceptors` loses ACC_FINAL. Asserted: no other
  accessor of the field remains.
- `TransmitterNetworkRegistry` (R3): `registerChangedNetwork` and `assignOrphans` add through
  `synchronized nestworld$addChangedNetwork`; `commitChanges` takes the set through
  `synchronized nestworld$takenetworksToChange`; `removeNetwork` (pair `networks` + `networksToChange`) is
  synchronized; `reset` takes. Asserted: no other accessor of `networksToChange`.
- Accounting R6: `-Dnestworld.mekRegistryAudit=true` → produced / published / dropped / mergedDuplicate /
  generations (`NestworldCoremodSupport.mekAuditSummary`).

Deviations from the 2.3 contract, stated explicitly:
- R1 says "dedicated monitor, never the registry instance". Implemented with the cache / registry *instance*
  monitors (synthetic synchronized methods, the same idiom as the shipped PathfinderCache fix). Justification:
  a full scan of Mekanism 10.4.16.80 found no `monitorenter` in any transmitter/acceptor/network class, and the
  synthetic methods do only collection work. A dedicated lock object needs hand-written monitorenter/exit with
  exception-table frames; can be switched if the owner insists.
- R2 (one publication point for invalid/orphan/ticket) and R4 (snapshots of `networks`, `transmitters`) NOT
  implemented: the detector shows those writers main-only. Per the owner rule they stay untouched until a
  detector shows off-main writes; the detector keeps watching them.
- `cachedAcceptors` (written by `updateTransmitterOnSide` in `commit`, read elsewhere) is not covered; no
  evidence of an off-main reader/writer failure yet.

### 2.6 Live A/B on the copies: M-A ON vs OFF — same workload

C307MA = 307 canary jar + exactly the M-A files + test-only `coremods.json` registration (provenance file);
`-Dnestworld.mekRegistryAudit=true`; churn period **1** tick (harsher than A/B's 2).

| copy | M-A | workload | outcome |
|---|---|---|---|
| A234 | off | period 2 | live signature after ~2 min |
| B307 | off | period 2 / period 1 | live signature after ~16 min / < 60 s |
| C307MA | **on** (`patched=true` via reflection) | period 1, 30 min | **0 crash reports, 0 Mekanism error lines**, 293 504 toggles, 70 772 generations |

Accounting after pausing the workload (drain): `produced = published = 250 403`, `dropped = 0`,
`mergedDuplicate = 0` (during the run the gap was 0–8 = in-flight generations).

Tick (C307MA under workload, 10 s windows): median 5.7–6.8 ms, p95 7.7–19.5 ms, max 12–76 ms; idle 1 m window
max 62 ms. JFR 120 s under workload (`/root/perf1-evidence/mek-ma-jfr/`): **no `JavaMonitorEnter` ≥ 10 ms on any
Mekanism monitor** (only one JFR-internal event); GC pauses up to 77 ms explain the 10 s-window maxima. Rare
1 m-window maxima of 170–526 ms are not attributed (HYPOTHESIS: autosave); the unpatched copies crash before a
comparable window exists.

Confidence: M-A closes the reproduced race on the copy — STRONGLY INDICATED (live A/B, same world/workload,
2 negatives on 2 Core versions, 1 positive for 30 min at a harsher rate) + CONFIRMED in the unit fixture
(negative 4/4, positive 4/4). Not PROVEN for production: different vehicle needed (section 4), and player-driven
paths (wrench/configurator, cable placement by players) are not exercised.

## 4. Vehicle decision (resolved 2026-10-26: addon) + documentation discrepancy (fixed)

- `docs/MOD_COMPATIBILITY_FIXES.md` / `docs/gmeil/ARCHITECTURE.md` (11ea64345, 2026-09-22): "the user's GMEIL
  requirement forbids changing mod code or behavior even through runtime bytecode transformation";
  `mod_compound_races.js` is an *offline research oracle* and is NOT registered in `META-INF/coremods.json`
  (verified: absent in HEAD and in the 307 canary jar). Therefore M-A in this file is **not active in
  production** and must not be registered there without an explicit owner exception.
- Discrepancy: d405f5187 (2026-09-29) re-registered `static_collection_race_transformer.js` and
  `tomsstorage_terminal_atomic_publish.js` in production `coremods.json`, but both documents still say no
  NestWorld mod transformer is in production. Not changed; owner to decide which is authoritative.
- Vehicle options for M-A in production: (a) a Mekanism compat addon jar with Mixins (same pattern as the
  approved `nestworld-compat-ae2`); (b) register the coremod in Core (`coremods.json`) under an explicit GMEIL
  exception; (c) a GMEIL execution-only replacement (no mod bytecode change) — behaviourally M-B-like, currently
  rejected.
- Live validation of the M-A logic was done ONLY on the test copy C307MA, which registers the coremod in its
  own jar (provenance: `/root/airis-2026-10-25/evidence-C307MA/PROVENANCE.txt`).

## 3. Harness note (not a Core defect)

`tests/coremods` needs `src/main/java/net/nestworld/compat/NestworldCoremodSupport.java` and fastutil on the
classpath. Without them the Draconic restore path throws `NoClassDefFoundError` and
`ConcurrencyRegression.batch` reports "Failed detached batch was not restored in order" (false alarm seen
2026-10-26). With the full classpath: PASS 3/3, 1144 assertions, BasicVerifier clean.

```
javac -d $O -cp "$CP" $(find tests/coremods -name "*.java") src/main/java/net/nestworld/compat/NestworldCoremodSupport.java
java -cp "$O:$CP" CoremodRegression . <mods dir>      # CP = asm*, nashorn*, coremods*, fastutil* jars
```
