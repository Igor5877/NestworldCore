# Airis repro on isolated copies — 2026-10-26

## Status (owner wording, 2026-10-26)

```
MEKANISM-REGISTRY-RACE-001            (owner decision 2026-10-26)
Root cause:               CONFIRMED
Negative control:         VERIFIED (unit 4/4; live A234, B307; addon OFF crash in 30 s)
Production delivery form: VERIFIED (nestworld-compat-mekanism 0.1.0, ccc888714)
M-A correctness:          VERIFIED
Loss/duplication:         0 observed after drain
Lock contention:          no JavaMonitorEnter >= 10 ms observed
Supported target:         Mekanism 10.4.16, exact jar SHA, exact target-class SHA,
                          no foreign Mixins on the target classes
STATUS:                   CLOSED / VERIFIED / FROZEN
M-A:                      FROZEN for the exact supported Mekanism fingerprint (changes need a new gate)
868be3340 (Core oracle):  research/reference, never production

AE2 TickManager queue race (A234):  CONFIRMED
B307 + AE2 addon, same symptom:     NOT OBSERVED (contended path exercised)
causal prevention by the addon:     CORRELATED, not PROVEN
```

**Closure scope (normative).** The gate covers the reproduced `NetworkAcceptorCache` /
`TransmitterNetworkRegistry` multi-producer race for the exact supported Mekanism build. Closure does not
assert global thread-safety of all Mekanism subsystems under free-running regions.

Follow-ups (not blockers of this gate):
- `MEK-GATE-PERF-001` — OPEN: the eligibility gate adds ~5.6 s to startup (scans every mod class for
  foreign Mixins).
- Tick spikes 228 / 624 ms in two 10 s windows — separate performance investigation (JFR showed no long wait
  on Mekanism locks).
- `BOT-KEEPALIVE` — test-harness follow-up: the bot's client-side keepAliveError after ~300 cycles occurs with
  and without the addon; server healthy.
- Wrench / configurator paths, `cachedAcceptors`, other Mekanism subsystems — DISCOVERY ONLY until evidence
  appears.

Owner decisions (2026-10-26): M-A algorithm APPROVED; delivery = separate Mekanism addon with a dedicated `@Unique`
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

### 2.7 Player-driven path (real bot, B307 = no Mekanism addon, detector on) — exercised

Bot `/root/airis-2026-10-25/bot/cablebot.js` (mineflayer, raw `block_place`/`block_dig` packets, creative,
op, FTB Chunks `bypass_protection`; copy-only deviations: online-mode=false, whitelist off,
ProxyCompatibleForge disabled, `-Dnestworld.bypassForgeHandshake=true`, isolated netns) placed and broke a
`mekanism:basic_universal_cable` on top of the cable line ~333 times.

Detector delta: TRACK +668, UNTRACK +333, INVALIDATE +333, ORPHAN thousands, NETWORK_ADD/REMOVE +333,
DEREGISTER +333 — **all on the main thread (offMain=0)**; no new off-main writer and no crash. In this Core the
player place/break path reaches the Mekanism registry on main, so R2/R4 stay "observe only" now with
player-path evidence too. (The bot ended with a client-side keepAliveError; server healthy.)

### 2.8 Production delivery: `nestworld-compat-mekanism` (Mixin addon) — delivery-form regression

Addon `compat/nestworld-compat-mekanism` 0.1.0 (jar sha256 `91b5632c3c660b6b4664c017edc0a37e0634eef9f8b4e2603f5c060446de54e8`):
- M-A ported literally from the oracle as `@Overwrite`s (`acceptorChanged`, `commit`, `adoptAcceptors`,
  `deregister`, `registerChangedNetwork`, `commitChanges`, `removeNetwork`, `reset`) plus one `@Redirect`
  (`assignOrphans` Set.add). Lock = `@Unique private final Object nestworld$lock` per cache / registry
  (owner decision), never Mekanism's own monitor; no world/capability/callback under it; never two locks held.
- Exact compatibility gate in the mixin plugin (`MekGate`): Mekanism `10.4.16` + jar sha256 `3b0d191f…58d2`;
  class-file sha256 of `NetworkAcceptorCache` (`9b119c3b…168d`) and `TransmitterNetworkRegistry`
  (`c932a16a…5d85`); no foreign `@Mixin` on either target. Mismatch → `INELIGIBLE`, no mixin applies, ERROR
  "DEGRADED: MEKANISM-REGISTRY-RACE-001 NOT mitigated". Kill switch `-Dnestworld.compatMekanism.disable=true`;
  accounting `-Dnestworld.compatMekanism.audit=true`; `/nwcompatmek stats|problems`.
- Remaining semantic difference vs Mekanism (same as the oracle, tested there): `adoptAcceptors` *takes* the
  adopted cache's pending generation instead of copying it, so it is committed once (by the adopter).

Copy D307MEK = 307 canary Core (unmodified, no oracle) + AE2 addon + this addon + churn mod:

| run | result |
|---|---|
| gate negative (detector mixins present) | `INELIGIBLE`, 2 problems: both detector mixins reported as foreign Mixins on the targets |
| addon OFF (`disable=true`, same jar), churn period 1 | **crash in 30 s**: `NetworkAcceptorCache.commit` NPE (`Map$Entry.getValue()` null — torn entry, same race) |
| addon ON, churn period 1, 60 min | **0 crash, 0 Mekanism error lines**, 577 584 toggles, 127 125 generations; drained `produced = published = 483 827`, `dropped = 0` |
| addon ON, churn + bot cable place/break (~300 cycles) | 0 crash; drained `produced 20 271 = published 18 019 + dropped 2 252` (deregister on break), `mergedDuplicate 0` |

Tick under workload (10 s windows): median 5.2–7.6 ms, p95 9.9–22 ms, max mostly 18–106 ms (two windows
228 / 624 ms, not attributed). JFR 120 s mid-run: no `JavaMonitorEnter` ≥ 10 ms on any Mekanism-related
monitor; GC pauses up to 87 ms.

Follow-ups (not blocking, owner to prioritise): gate scan costs ~5.6 s of startup (walks every mod class for
foreign mixins — can be narrowed to classes referencing the targets via jar-level prefilter);
`cachedAcceptors` remains uncovered (no off-main evidence); the bot's client-side `keepAliveError` after ~300
cycles happens with and without the addon (HYPOTHESIS: client parse desync, server healthy); wrench /
configurator paths not exercised.

## 5. MEK-GATE-PERF-001 — narrow the Mekanism compatibility gate

Status: **IMPLEMENTED / VERIFIED except decision equivalence C7** (stricter than legacy, see 5.3) — closure
awaits the owner's decision on C7; not self-closed.
`MEKANISM-REGISTRY-RACE-001` stays CLOSED / VERIFIED / FROZEN; M-A unchanged (`git diff` of
`compat/nestworld-compat-mekanism/.../mixin` is empty). Addon version 0.1.1, jar sha256
`8f0cdf0079bb9a34aba1bb48dbb69e24309f11ece7bcb5f25c91a47fcc44f1f0`.

### 5.1 New gate (`MekGateV2`, impl `mixin-config-v2`), same ACTIVE contract

- A `resolveMod`: exactly one loaded mod file provides modid `mekanism` (FML loading mod list); 0 or >1 → INELIGIBLE.
- B `jarSha`: full SHA-256 of that jar (streamed, 64 KiB buffer) + version 10.4.16 — unchanged rule.
- C `classSha`: only the two target entries are read and fingerprinted — unchanged rule.
- D foreign Mixins, from the Mixin runtime instead of every class of every mod:
  - `mixinConfigs`: every config registered with the Mixin runtime (`Mixins.getConfigs()`, JSON already parsed at
    registration, so available in our plugin's `onLoad`) is mapped by its `mixinPackage` to the mod jar(s) holding
    classes in that package **or any subpackage** (package index built once: package + ancestors → jars).
  - `mixinTargets`: per jar, only classes under those packages are read, through FML's already-open SecureJar view,
    each class once (nested prefixes deduplicated). Byte prefilter on the target *simple* names
    (`NetworkAcceptorCache`, `TransmitterNetworkRegistry`) — every form that can name a target (descriptor, slashed
    or dotted string) contains them — then `@Mixin(value=Type…, targets="…")` is resolved exactly, both string forms.
  - A config whose package holds no class in any mod jar can apply nothing (all mixins, plugin-supplied too, must be
    classes under the package) → counted as `emptyConfigs` (14 on Airis: cobweb, melody, powerpots, bclib…).
- Runtime cross-check (new, fail-closed): `IMixinConfigPlugin.acceptTargets(otherTargets)` receives the targets of
  every other prepared config; if one is a protected class while the gate said ACTIVE, startup is refused
  (`IllegalStateException`, mode `CONFLICT`) instead of running an unverified M-A.
- Diagnostics: `/nwcompatmek status` → `state reason mekanism jarSha classSha foreignMixins configs emptyConfigs
  mixinClassesRead gateTime phases impl`. Normal log: `[NW-MEK] Gate ACTIVE in N ms`; phases at DEBUG.
  Diagnostic flags (copies only): `gateCompare=true` (also runs the legacy gate, records decision/time),
  `gateImpl=legacy` (decides with the 0.1.0 gate), `gateDump=<file>` (every class read).
- No cache (not needed: < 250 ms without it). No new threads; reads via FML's open file systems; all streams closed.

### 5.2 Performance (cold JVM starts, Airis copy, 196 mods, no concurrent load)

| gate | starts (ms) | min | median | max |
|---|---|---|---|---|
| legacy 0.1.0 (walk every class) | 5521, 5693, 5795, 6063, 6435 | 5521 | **5795** | 6435 |
| final 0.1.1 `8f0cdf00` | 151, 190, 204, 218, 237 | 151 | **204** | 237 |

Speedup **28.4×** (median), startup saved **≈ 5.6 s**. Typical phases: resolveMod 0, jarSha 55–130, classSha 2–6,
mixinConfigs 11–14, mixinTargets 76–125 ms (1055 classes read instead of every class of every mod).
Intermediate builds (evidence kept): subpackage-unaware v2 → false INELIGIBLE (fixed); index + ZipFile → 268 ms;
+ byte prefilter → 288 ms (I/O-bound, no gain); SecureJar + dedup → 179 ms. The 1085 vs 1055 class count difference
between builds was proven to be double reads of nested prefixes in jar-in-jar modules (offline zip comparison: all
880 top-level classes identical, 30 nested double counts), not a blind spot.

### 5.3 Decision equivalence (same JVM, `gateCompare=true`)

| case | legacy | new | equal |
|---|---|---|---|
| C1 exact supported environment | ACTIVE | ACTIVE | yes |
| C2 `disable=true` | DISABLED | DISABLED | yes (gate not run) |
| C3 Mekanism jar +1 entry | INELIGIBLE | INELIGIBLE (jar SHA) | yes |
| C4 target class modified (SourceFile attr) | INELIGIBLE | INELIGIBLE (jar + class SHA) | yes |
| C5 detector mixins (foreign) | INELIGIBLE | INELIGIBLE, foreign=2 | yes |
| C6 unrelated mixin on `DynamicNetwork` | ACTIVE | ACTIVE | yes |
| C7 `@Mixin(targets="mekanism.common...NetworkAcceptorCache")` | **ACTIVE** | **INELIGIBLE** | **no** |
| C8 `@Mixin(TransmitterNetworkRegistry.class)` | INELIGIBLE | INELIGIBLE | yes |
| C9 legacy decides + C7 fixture | — | startup refused by `acceptTargets` | — |

**C7 = STOP per the TZ, investigated:** the legacy prefilter searched only the slashed full name, so a Mixin naming
the target as a dotted string was invisible and the legacy gate said ACTIVE. C9 proves that Mixin really targets
`NetworkAcceptorCache` (the runtime cross-check sees it and refuses startup). The legacy ACTIVE was therefore unsound;
the new gate is stricter in exactly this case. Restoring 100 % equivalence would require re-introducing the blind
spot. Owner decision needed: accept the stricter decision. (The shipped 0.1.0 has the blind spot; on the real Airis
pack the new gate finds 0 foreign mixins, so it is not triggered there.)

### 5.4 Short live M-A regression with the final jar (D307MEK, churn period 1, 12 min)

`state=ACTIVE` in 168 ms; 116 792 toggles; drained `produced = published = 100 398`, `dropped = 0`,
`mergedDuplicate = 0`; 0 crash reports, 0 Mekanism error lines; tick p95 11–15 ms, 1 m max 78 ms; process open file
descriptors 619 before and after (no leaked handles).

### 5.5 DoD

- [x] exact Mekanism accepted (C1) · [x] wrong jar rejected (C3) · [x] wrong target class rejected (C4)
- [x] foreign target Mixin rejected (C5, C8, and C7 string form) · [x] unrelated Mixin accepted (C6)
- [x] M-A unchanged · [x] short live churn clean · [x] accounting invariant clean
- [x] median gate < 500 ms (204) · [x] target < 250 ms met · [x] diagnostics · [x] documentation · [x] evidence
- [ ] old/new decision equivalence 100 % — 8/8 comparable cases equal except **C7** (legacy unsound) → owner
- [x] separate commit

Evidence: `/root/airis-2026-10-25/evidence-mek-gate-perf/` (baseline, per-build timings, cases, read dump,
fixtures in `/root/airis-2026-10-25/fixtures/` with SHA256SUMS).

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
