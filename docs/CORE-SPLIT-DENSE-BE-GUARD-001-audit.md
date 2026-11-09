# CORE-SPLIT-DENSE-BE-GUARD-001 — audit + design proposal

Status: **AUDIT / DESIGN ONLY.** No code changed, nothing built, no server touched. Branch
`claude/nestworld-hybrid-arch-invq4h` @ `fbf9d7cdb`. Source paths are relative to
`src/main/java/net/nestworld/region/`. Live evidence is from `/root/project-infinity/logs/` (read-only).

Confidence words: **CONFIRMED** (code or log shows it directly), **STRONGLY INDICATED** (several independent
signals agree), **CORRELATED** (happens together, cause not isolated), **HYPOTHESIS**, **UNKNOWN**.
§1–§4 are facts. §5–§7 are proposals.

---

## 0. Summary

| # | Finding | Confidence |
|---|---|---|
| S1 | On 2026-11-07 the overworld was auto-split **three times on the same line** (X: chunk 2 \| 3, through the base at chunks ≈(−1..1, −2..1)). Each time ghostZones went from 0.15–0.27 ms to **31–56 ms/tick**. Each manual merge brought it back to 0.14–0.18 ms. | CONFIRMED (logs) |
| S2 | The cost is `BoundaryManager.syncGhostZones()` running **full `saveWithFullMetadata()` NBT on every BE** (ticking or not, dirty or not, read or not) in a 3-chunk strip on each side of every region edge, **every tick**. | CONFIRMED (code); STRONGLY INDICATED as the dominant term (the 19:12 incident ran with free-running OFF, so there was no lock wait) |
| S3 | The split decision cannot see this cost. Region cost (`avgTickMs`/p95) only measures the region thread. Ghost sync and MAIN-band BE ticks run on the main thread, outside every region's metric. So after the split both children look cheap. | CONFIRMED (code) |
| S4 | The cut scorer models the **border band** (4 columns go to MAIN), using tick-**count** heat of ticking BEs only. It does **not** model the ghost strip (6 columns), non-ticking BEs (AE2 cables, drives, chests), or serialization cost. Both bad splits moved ~24–25% of the BEs onto MAIN, just under the `MAX_CUT_BAND_SHARE = 0.25` veto. | CONFIRMED (code); CORRELATED (the 24–25% ratio) |
| S5 | Auto-merge can **never undo** such a split on this host: merge requires `active > SPLIT_TARGET_PARALLELISM` (= 35 on 36 CPUs) or an entity-less region. Manual merge is undone by a re-split 69 s later (flap). The bad layout is also **persisted** and restored on reboot. | CONFIRMED (code + logs) |
| S6 | The scorer already enumerates several candidate lines per axis on 2 axes, but it ranks them only by band heat and returns one. It has no cost/benefit model. | CONFIRMED (code) |

---

## 1. Current split triggers (as implemented)

All triggers are evaluated in `RegionSplitManager.onTick()`, called once per main tick after the barrier
(`NestworldDimensionRegion.java:1129`). Each dimension has its own tree and its own `RegionSplitManager`.

| Item | Value / rule | Evidence |
|---|---|---|
| Evaluation gate | Wall clock: at most once per real second (`EVAL_INTERVAL_NANOS = 1 s`), whatever the TPS | `RegionSplitManager.java:109,141-144` |
| Cost metric | `WorldRegion.getAvgTickMs()` = mean of a 100-sample ring of **region-thread** tick time (entity round plus work rounds, including the region's BE batches). Main-thread costs are not included. | `WorldRegion.java:809-834`; `RegionThread.java:606-620` |
| Tail metric | `getPercentileTickMs(0.95)` over the same 100 samples | `WorldRegion.java:849-854` |
| **avg-threshold** | `costMs > SPLIT_MS_THRESHOLD = 25.0` | `:44, :208` |
| **p95-tail** | `p95 > 25.0` (the average can be healthy) | `:203-204` |
| **fill-cores** | `spareCores && region == hottest && costMs > SPLIT_FILL_CORES_MS (12.0, -Dnestworld.splitFillCoresMs)`. `spareCores = busy < SPLIT_TARGET_PARALLELISM`, where busy = regions with ≥1 owned entity, and target = `-Dnestworld.splitTargetParallelism` (default `availableProcessors−1`). On project-infinity (36 logical CPUs) target = 35, so this trigger is almost always armed. | `:60-64, :165-178, :205` |
| Hysteresis | `++splitPressureChecks >= SPLIT_CHECKS_REQUIRED (5)` consecutive evaluations (≈5 s). Any non-hot evaluation resets it. | `:46, :211, :240-244` |
| Region cap | `active + pendingSplits >= MAX_REGIONS_TOTAL` (default `2×CPUs`, i.e. 72 here; `-Dnestworld.maxRegions`) blocks the split | `:73-75, :206` |
| Size gate | `canSplit()`: more than 1 chunk on some axis | `WorldRegion.java:866-868` |
| Load-separation gate | `splitWouldSeparateLoad()` = `loadAwareCut() != null` (see §2). Failure logs "skipped" at most every 30 s per region. | `:212, :895-897, :998-1012` |
| Cooldown after split | None as such. Children start with fresh counters, and the parent's counters are reset (`resetThresholdCounters`). | `:221`, `WorldRegion.java:875` |
| Apply | Pending splits run in `applyPending()` in the same evaluation, under the region and neighbour chunk write locks with a timeout | `:441-446, :502-574, :591-640` |
| **Merge** | `cost < 10 && !tailHot && (active > SPLIT_TARGET_PARALLELISM \|\| region has no entities)` for **25** evaluations, region not `pinned`, and the sibling is also a candidate | `:45,47, :227-235, :450-470` |
| Persistence | The layout is saved whenever its version changes (`LAYOUT_SAVE_INTERVAL`) and restored at boot | `NestworldDimensionRegion.java:669-680, 1177-1181, 242` |
| CpuCapacityPlanner | **Reporting only.** It duplicates the target formula and has no input into split or merge. | `CpuCapacityPlanner.java:115-124` |
| TickDurationHistory | **Presentation only** (status/lag 10 s / 1 m rows). Not used by split. | `TickDurationHistory.java:5-20` |
| Auto free-running | Promotion and demotion after a split (`-Dnestworld.autoFreeRunning`, true on project-infinity now). It does not affect split choice. | `:287-440` |

## 2. Candidate boundary selection today

Path: `doSplit(region, reason)` → `loadAwareCut(region)` (`:695`) → `scoreAxis(pref)` and, if profiled,
`scoreAxis(perp)` (`:712`) → `scoreCut(min,max,combined,vetoHeat)` (`:800-868`). `loadAwareCut` is computed
twice: once in the gate and once in `doSplit`.

| Question | Answer | Evidence |
|---|---|---|
| Inputs | `combined` = owned entities (weight 1 per entity, at its chunk column) + `BlockTickHeat.counts × CUT_HEAT_WEIGHT (0.15)`. `vetoHeat` = `BlockTickHeat.vetoCounts × 0.15`. | `:741-756`; `NestworldTuning.java:406` |
| What heat is | Decayed (×0.5/s) per-chunk **count** of scheduled ticks, fluid ticks and **ticking** BE ticks. Pinned and cascade-safe BE ticks go only to `counts`, not to `vetoCounts`. It is not time, and non-ticking BEs are invisible to it. | `BlockTickHeat.java:42-81`; `NestworldDimensionRegion.java:563-567` |
| Per-chunk tick **time** | Exists but is **not used** by split: `TickAttribution.chunkNanos` (entity + BE tick ns per chunk, decayed ×0.5/s, JVM-wide per dimension). Per-mod BE time also exists, but by namespace only, not type or chunk. | `TickAttribution.java:52-104`; call sites `NestworldDimensionRegion.java:538,628`, `RegionThread.java:1126` |
| BE count per chunk | Not tracked. It is available in O(1) per chunk from `chunk.getBlockEntities().size()`. | — |
| Candidates | Per axis: every populated column plus the midpoint of every gap > 1. On **both** axes when the perpendicular axis is splittable. | `:836-843, :712` |
| Admissibility | Each child gets ≥ `MIN_SPLIT_SEPARATION_SHARE (10%)` of the **combined** weight. Total < `GUARD_MIN_LOAD (50)` → unprofiled → weighted median, with no veto. | `:853, :834` |
| Ranking | Lowest vetoHeat inside `(cut−2, cut+2]`, which is exactly the MAIN border band. Ties go to the weighted median. Across axes, the lower band wins. | `:845-863, :714-720` |
| Veto | `bestHeatBand > 25%` of total vetoHeat → `null` → the region stays whole. | `:669, :866` |
| Border band considered? | **Yes**, as tick-count heat (band = `BORDER_BAND_CHUNKS = 2`). | `NestworldTuning.java:121` |
| BE execution domain considered? | **Partly.** Pinned and cascade-safe heat are removed from the veto (they don't move domain), and NORMAL heat in the band is counted. Neither the band's *time*, nor non-ticking BEs, nor position pins at chunk granularity are modelled. | `NestworldDimensionRegion.java:289-320` |
| **Ghost strip considered?** | **No.** The strip is 3 chunks deep per side (`withinGhostDepth`: distance ≤ `GHOST_DEPTH = 2`), so **6 columns** in total, against the band's 4. It contains every BE. | `BoundaryManager.java:30, 189-194` |
| Multiple candidates scored? | Several lines are scored, but **one** is returned, and only on band heat. There is no "try the next line if this one is expensive" step and no benefit term. | `:845-868` |

Geometry of the incident line (cut = 2, axis X):

| Columns x → | −1 | 0 | 1 | 2 \| 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| Child | A | A | A | A \| B | B | B | B |
| BE domain (NORMAL) | A | A | MAIN | MAIN \| MAIN | MAIN | B | B |
| In ghost snapshot | – | yes | yes | yes \| yes | yes | yes | – |

The hottest base chunks logged at the split were (0,1), (−1,1) and (1,−2): one is in the band, one is in the strip, and one is adjacent.

## 3. Ghost-zone cost model

### 3.1 What `syncGhostZones()` does each tick (`BoundaryManager.java:84-168`, called at `NestworldDimensionRegion.java:1116`)

| Step | Work | Scales with |
|---|---|---|
| 1 | Iterate **all** loaded FULL chunks (`nestworldLoadedFull`), with a grid lookup and a depth test for each | loaded chunks in the dimension (baseline ≈0.15–0.27 ms *including* `chunkMap.tick()`; see note) |
| 2 | For each region (`grid.getAllRegions()`), and for each strip chunk it owns: copy the BE map entries | strip chunks × BEs/chunk |
| 3 | For **every BE** in those chunks: `BlockEntity.saveWithFullMetadata()` → new `CompoundTag` into a fresh HashMap | Σ over strip BEs of the per-type NBT cost (inventories, AE2 cell/part NBT, …) |
| 4 | Free-running owner: `tryReadLock(2 ms)` first. A miss keeps the old snapshot. | ≤ 2 ms per free-running region per tick |
| 5 | Publish the snapshot (AtomicReference swap) | O(1) |

These BEs are **not** filtered out: non-ticking BEs, unchanged (non-dirty) BEs, BEs that tick on MAIN (band, pinned), and BEs that no
reader asked for. Readers are rare. `ChunkViewDiag` on Region-1 during the 22:25 incident shows **6–191 ghost-zone
reads per 20 000** cross-region BE lookups, i.e. a few reads per second. Meanwhile the whole strip was serialized 20×/s.
CONFIRMED (code + `2026-11-07-1.log.gz` ChunkViewDiag lines 22:25:13–22:27:42).

Note: the logged `ghostZones=` phase is `t4→t5`. It spans syncGhostZones, deferred drains and `chunkMap.tick()`
(`NestworldDimensionRegion.java:1116-1125`). The tracker part is ~constant (0.15–0.27 ms before and after each merge), so the
**delta** is syncGhostZones. Precise per-call numbers exist only behind `/nestworld ghostzones`
(`BoundaryManager.java:72-82`). They were not captured in any log, and there is no Prometheus export.

### 3.2 Why a split through a dense base costs 31–56 ms

1. Before the split the overworld is one region whose edges are at ±1 875 000 chunks, so the strip is empty: ghost ≈0.
2. The cut at X = 2|3 creates two edges that cross the base, and all BEs in columns 0..5 across all loaded Z are now serialized every tick.
3. Project-infinity has ≈3 400 ticking BEs in the overworld, plus an unknown number of non-ticking AE2/storage BEs. AE2 drives and cable
   buses serialize cell or part state (HYPOTHESIS: the heaviest per-BE cost).
4. Neither the split heuristic nor the merge heuristic sees this, because it is main-thread time (S3).

UNKNOWN: the number of BEs serialized per tick and the per-type cost. Order of magnitude, as a HYPOTHESIS: 30–55 ms / (≈1 000–1 500 strip BEs) ≈ 25–50 µs/BE.

### 3.3 Reconstructed incidents (overworld; `ghostZones` = t4→t5 phase average over 200 ticks)

| # | Log | Event | Trigger | Line | BE phase main / buckets | ghostZones before → after | Other |
|---|---|---|---|---|---|---|---|
| I-0 | `2026-11-07-4/-3.log.gz` 15:30–16:06 | Split storm into ~50 tiny regions (ids → 69) | avg-threshold (45.4, 38.9 …), then p95-tail (42–73 ms) | many | n/a | ~1 → 14–42.9, steady 22–28 | Small regions are almost entirely strip. CORRELATED (many confounders) |
| I-1 | `pre-072-1942.log` 19:12:22 | Split R0 → R1/R2 | **p95-tail 35.8** (avg 23.6) | X 2\|3 | 69 / #0=3342 → **905** / #1=2477 **#2=29** | **0.16 → 36.7**, steady 31–35 | free-running **OFF** (0 promotions), so no lock wait. regionPool 3.7 → 6–9.8; vanilla ≈23 unchanged |
| I-1m | same, 19:22:07 | Manual merge (RCON session 19:22:07.19–.31) | manual | — | — | 31.1 → **0.14** | children at merge: 9.1 / 6.0 ms |
| I-2 | same, 19:23:16 | **Re-split, same line, 69 s later** | p95-tail 36.3 (avg 23.3) | X 2\|3 | main 905 again | 0.15 → **31.5–32.2** | flap CONFIRMED |
| I-2r | `pre-sharded-2012.log` 19:43:49 | Boot **restored the saved 2-region layout** | — | X 2\|3 | main 952 / #0=2432 #1=29 | 10–18 (fewer loaded chunks after boot: CORRELATED) | merge 20:06:47 (RCON 20:06:42) → 0.18, main 194 |
| I-3 | `2026-11-07-1.log.gz` 22:25:06 | Split R0 → R1/R2 | **p95-tail 34.3** (avg 10.6) | X 2\|3 | 615 / #0=2799 → **1292** / #1=2094 **#2=28** | **0.27 → 46.5, 55.6, 52.6, 50.6, 44.1**, decaying to ~30 | overworld phase sum ≈46 → ≈106 ms (22:26:19). Children 6.7 / 0.9 ms. R1 free-running. |
| I-3m | same, 22:33:24 | Manual merge (RCON 22:33:15) | manual | — | — | 32.6 → **0.18** | sum ≈28 ms afterwards |

Implied net effect (region-side gain ≤ parent − max(child), against measured ghost delta):

| | parent avg | max(child) | gain ≤ | ghost Δ | net |
|---|---|---|---|---|---|
| I-1 | 23.6 | 9.1 | 14.5 ms | +31…36 | **≈ −17 … −22 ms** |
| I-3 | 10.6 | 6.7 | 3.9 ms | +44…55 | **≈ −40 … −51 ms** |

The split also gave child B only 28–29 ticking BEs (~1%). It passed the 10% separation gate on *combined*
(entity + 0.15×heat) weight. The band moved +836 / 3342 (25.0%) and +677 / 2799 (24.2%) of the region's BEs to MAIN:
both just under the 0.25 veto. All later attempts on the same base were "skipped: no cut line…" (e.g.
`latest.log` 00:34–00:38), so the base sits **on the knife edge** of the 25% rule. CORRELATED.

## 4. Cost of computing each prediction term per candidate line

Today a decision evaluates ≤ ~2×(populated columns) candidates, at most once per second per hot region.

| Term | Data available today | Cost per evaluation | Gap |
|---|---|---|---|
| BE count in strip / band | None stored. `chunk.getBlockEntities().size()` is O(1) per chunk. | One pass over the region's loaded chunks → column histogram per axis → prefix sums → **O(1) per candidate**. The pass is the same size as syncGhostZones step 1 (≈0.1–0.2 ms), and it runs only when a split is about to be applied. | trivial |
| MAIN-domain BE count | Domain rule in `blockEntityRegionFor` (`NestworldDimensionRegion.java:309-313`) | Same pass, with a policy lookup per BE (`blockEntityTypePolicy` is a hash lookup) | cheap |
| Pinned BE count | `NestworldPins` type pins plus position pins (`isBePositionPinned`) | Same pass | cheap |
| Heavy / known-expensive BEs | **Tick** ns per chunk (`TickAttribution.chunkNanos`, mixed with entity time) and per mod namespace. **No per-type serialization cost.** | Read the map: O(cells) | Needs an NBT-cost-per-type EWMA (see §5.3). syncGhostZones already times each save (`BoundaryManager.java:130-141`), so it only needs attribution by type. |
| Est. ghost bytes | None | — | Byte size needs NBT size accounting, which is expensive. Use **time**, not bytes (proposal). |
| Est. serialization cost | Aggregate only (`nestworldGhostNbtNanos`). Strip BEs of a *future* line were never serialized. | Σ count×typeCost via the column histogram: O(1) per candidate | Cold start: per-type cost is unknown for types never serialized. Use a default constant, plus an optional bounded probe (§5.3). |
| Band time shift | vetoCounts (count). `TickAttribution.chunkNanos` (time, includes entities). | O(cells) | Needs a BE-only, NORMAL-policy per-chunk ns map: one extra `addTo` in `tickBlockEntityList` |
| Cross-boundary interaction density | Ghost reads (global `RegionChunkView.ghostZoneReads`, ChunkViewDiag per thread), CrossRegionCapabilityBus. No per-chunk adjacency counters. AE2 grid span is in the compat layer. | — | Before a split there is no boundary to observe, so this cannot be predicted cheaply now. UNKNOWN. Phase 2: per-chunk "neighbour/capability lookup crossing a column" counters, or AE2 grid bounding boxes. |
| Parallel gain | Region avg/p95. Per-chunk tick time (`TickAttribution`). Entity counts per column. | O(cells) | Per-chunk ns gives a predicted child split of region time. It is avg-based, so it is weak evidence for p95-triggered splits. |

---

## 5. Design proposal (NOT implemented)

### 5.1 Model

For region R and candidate line L (axis, cut):

```
T_R          = region-thread cost that triggered (avg, or p95 for a tail trigger)
T_A, T_B     = predicted child costs (per-chunk tick ns of chunks on each side, scaled to T_R)
gain(L)      = T_R − max(T_A, T_B) − c_thread              (c_thread ≈ 0.3 ms barrier/thread overhead, calibratable)
G(L)         = k_ghost × Σ_{BE in ghost strip of L} typeCost(BE)          (main thread, serial, every tick)
M(L)         = Σ_{NORMAL BE in band of L, currently region-domain} tickNs  (moves to serial MAIN)
X(L)         = 0 in phase 1 (logged as UNKNOWN); phase 2 adds cross-boundary interaction
net(L)       = gain(L) − G(L) − M(L) − X(L)
admissible(L) ⇔ existing separation gate (10%) AND net(L) > margin
margin       = max(MARGIN_ABS = 2 ms, MARGIN_REL = 0.2 × T_R)        (owner decision D1)
```

Decision procedure, replacing "best band heat → one line":

1. Enumerate the existing candidates on both axes (no new candidate generator; optionally add the gap midpoints of the BE-count histogram).
2. Compute net(L) for every candidate from the prefix sums.
3. Pick the max net(L) among admissible lines.
4. If none is admissible, **veto this split** (the region stays whole, and the per-tick budget contains it, as today).
5. Keep the existing 25% band veto as a hard pre-filter for cascade safety. It is about correctness, not cost.

This vetoes a bad **line** first and the split only as a last resort. A uniform entity crowd has G = M = 0, so it still
splits at its median (existing `selfTest` case "entity crowd: splits"). Parallelism is preserved.

Against the incidents, even a crude G (e.g. 25 µs × strip BEs) is ≫ gain (3.9 / 14.5 ms), so X = 2|3 is rejected and
the procedure tries the next line. Expected outcome: the region stays whole, or it is cut away from the base.
STRONGLY INDICATED by §3.3 numbers.

### 5.2 Computation cost

- Only when `splitPressureChecks` reaches 5 (≤1/s per hot region, usually much rarer). Nothing runs per tick.
- One pass over the region's loaded chunks plus a BE-map scan for policy and type. O(loaded chunks + BEs in region). Bounded by
  the same work syncGhostZones step 1 does every tick, at 1/20 of its frequency or less.
- Cache the per-column histogram for 5 s, so `splitWouldSeparateLoad` + `doSplit` don't compute it twice (they already call
  `loadAwareCut` twice: `:212` and `:491-493`).

### 5.3 Calibration and feedback

| Mechanism | Detail |
|---|---|
| typeCost EWMA | In syncGhostZones, attribute the already-measured per-BE save ns to `BlockEntityType` (EWMA, α = 0.05). This is one hash update per serialized BE, or a 1-in-16 sample to stay negligible. |
| Cold start | Types never serialized use `DEFAULT_BE_SAVE_NS` (initial ≈30 µs, derived from I-1/I-3 once strip BE counts are measured in Stage 0). |
| Optional probe (D3) | At decision time, serialize a sample of the candidate's strip BEs within a hard 1–2 ms budget to measure real cost. This is a one-shot main-thread cost per decision. |
| Post-split measurement | A window of W = 30 s after every split. Measure per-region ghost ns (new per-owner timer in syncGhostZones), the MAIN BE ns delta, and the child costs. Store `measured_net`. |
| k_ghost | `k_ghost = clamp(EWMA(measured_G / predicted_G), 0.5, 4)`, persisted in memory only |
| Regret | If `measured_net < −margin` → **split-regret merge** of the two children. This is a new reason, allowed even when `active ≤ target` (D4). The line also goes into BadLineMemory. |
| BadLineMemory | Key (dimension, parent bounds, axis, cut) → measured cost, expiry TTL (e.g. 30 min) **or** invalidation when the strip BE count changes by > 25%. It blocks the I-1m → I-2 flap. A manual merge of auto-split children also records the line (D8). |
| Restored layout | At boot, re-measure the restored lines in the first window, and apply regret if they are bad (D5). |

### 5.4 Flags / modes

`-Dnestworld.splitDenseBeGuard=off|shadow|enforce`, with **shadow** as the proposed first default.

- **off** = today's behaviour exactly. This is the negative control.
- **shadow** computes and logs the verdict and the predicted-vs-measured costs, but never changes a decision. This follows the standing
  shadow-mode practice.
- **enforce** acts on line veto, regret and BadLineMemory.

The margins, W, TTL and the default per-BE cost are system properties.

### 5.5 Telemetry to add

| Where | Field |
|---|---|
| `SplitMergeEventLog.Event` (split) | candidatesConsidered, chosen (axis, cut), rejected lines with reason (`band25`, `net<=margin`, `badLineMemory`), T_R basis (avg/p95), predicted gain / G / M / net, stripBeCount, bandBeCount (by policy), pinnedInStrip |
| SplitMergeEventLog (follow-up record, +W s) | measured child costs, measured ghost ns per child, measured MAIN BE ns delta, measured_net, ratio measured/predicted, verdict (`ok` / `regret`) |
| BoundaryManager | Per-region: ghost ns, serialized BE count, lock misses; top-N types by save ns. Prometheus gauges (none today). |
| Merge event | Reason `split-regret(line=X2, measured_net=−41ms)` and the BadLineMemory insert |
| `/nestworld status` | Guard mode, last verdict per region, BadLineMemory size |

### 5.6 Interaction with merges (anti-flap)

- A line veto resets `splitPressureChecks` (already done), and the region gets an exponential back-off for re-evaluating the *same* line (via BadLineMemory). Other lines remain eligible.
- A regret merge is immediate and is a topology change only. The merged parent keeps BadLineMemory, so the I-2 re-split cannot recur.
- The ordinary merge rule is unchanged. Regret is the only new path that merges below `SPLIT_TARGET_PARALLELISM`.
- A thrash guard reuses `SplitMergeEventLog`'s rapid-thrash detection. A regret merge is not counted as thrash. A split of the same line within TTL is
  impossible by construction.

### 5.7 Negative control, fixtures, evidence plan

| ID | Kind | Setup | Pass condition |
|---|---|---|---|
| F1 | Headless pure function (extend `RegionSplitManager.selfTest`) | Synthetic column histograms: (a) dense BE block straddling the median + entity farm far away; (b) dense block only; (c) uniform entity crowd, no BEs; (d) two bases with a gap; (e) I-1 replay (≈3 400 BEs, hot columns −1..1, strip cost 25 µs/BE) | (a) cut lands in the gap, not through the block; (b) split vetoed; (c) splits at the median (parallelism preserved); (d) gap line; (e) X 2\|3 rejected |
| F2 | Integration, test server (**not** project-infinity live) | ≥1 500 BEs with heavy NBT (full chests / shulker-filled, AE2 drives if available) in columns −3..8, plus an entity load that forces an avg > 25 split | guard **off**: split through the block, ghost phase +≥20 ms (**negative control reproduces**). **enforce**: no line through the block; ghost Δ < 2 ms. Hopper/chest conservation test (existing 1728/1728 style) shows the same results in both modes. |
| F3 | Regret | `doSplitAtForTest` at the dense line, guard enforce | regret merge within W + 1 s; no re-split of that line for TTL; the event log has a predicted/measured pair |
| F4 | Shadow on project-infinity (owner-gated deploy) | mode = shadow | Logged verdict for the X 2\|3 line = reject. Predicted vs measured G within 2× after calibration. |
| F5 | Equivalence | Any mode | Topology change only: no tick-rate, spawn or player change (GAMEPLAY_EQUIVALENCE) |

### 5.8 GAMEPLAY_EQUIVALENCE

Every proposed action changes only **which thread** runs which chunk: veto a line, choose another line, keep the region whole,
or regret-merge. Nothing throttles, skips ticks, changes spawn rules, or restricts players. The probe (D3) is a read-only
`saveWithFullMetadata()`, which is already done 20×/s for strip BEs today.

---

## 6. Relation to CORE-GHOST-ZONE-DELTA-001

| Split-cost component | Removed by delta sync? | Notes |
|---|---|---|
| Serializing unchanged BEs every tick (§3.1 step 3) | **Mostly.** Cost becomes ∝ BEs changed per tick. | HYPOTHESIS: many machine BEs call `setChanged()` every working tick, so the reduction depends on dirty-rate. That rate is unmeasured. UNKNOWN. |
| Strip BEs that tick on MAIN (band = 4 of the 6 strip columns, plus pinned) | **Yes**, if they are skipped | Readers on region threads would then need a different safe read path for MAIN-domain BEs (free-running regions run concurrently with MAIN). This is a correctness question for that gate. UNKNOWN. |
| Non-ticking BEs that never change (most chests, idle cables) | Yes, with dirty-only | — |
| Outer scan of all loaded chunks (≈0.1–0.2 ms) | No | Small |
| Band shift M(L) (BE ticks move to serial MAIN) | **No** | Still needs this guard |
| Poor parallel gain (child B got 28 BEs) | **No** | Still needs this guard |
| Merge blindness / flap / persisted bad layout (S5) | **No** | Still needs this guard |

The guard's calibration (`typeCost`, `k_ghost`) adapts automatically once delta lands: G(L) shrinks and more lines become
admissible. Order suggestion: Stage 0 telemetry of this gate first, because it also gives DELTA its baseline (per-region ghost ns,
per-type save ns, dirty-rate counter).

## 7. Staging (proposal)

| Stage | Content | Behaviour change |
|---|---|---|
| 0 | Telemetry (§5.5), typeCost EWMA, per-region ghost ns, dirty-rate counter (for DELTA) | none |
| 1 | Net-gain multi-candidate ranking, `shadow` default | none (logs) |
| 2 | `enforce`: line veto → next line → split veto | topology only |
| 3 | Post-split measurement, regret merge, BadLineMemory, restored-layout revalidation | topology only |
| 4 | X(L) cross-boundary interaction term (AE2 grid span / per-column neighbour counters) | topology only |

---

## Decisions requested from owner

| # | Decision | Options / my default |
|---|---|---|
| D1 | Safety margin | `max(2 ms, 20% × T_R)`, or a different absolute/relative value |
| D2 | First rollout mode | **shadow** on project-infinity, then enforce after F1–F3 pass, or straight to enforce on test servers only |
| D3 | Allow a bounded one-shot NBT probe (≤ 1–2 ms main thread) at split decision for cold-start cost | yes / no (no → default constant + EWMA only) |
| D4 | Allow a **split-regret merge** that bypasses `active > SPLIT_TARGET_PARALLELISM` | yes (needed, or bad splits are permanent on 36-CPU hosts) / no |
| D5 | Persist BadLineMemory across restarts, and revalidate restored layout lines at boot | persist / memory-only |
| D6 | p95-tail trigger: all 3 bad splits were p95-triggered with a healthy average. Require that predicted gain be computed on p95 (tail separability), or keep p95 triggers but gate them only by this guard. | — |
| D7 | GHOST_DEPTH (3 columns/side, inclusive ≤2) vs BORDER_BAND (2 columns/side) mismatch | leave to CORE-GHOST-ZONE-DELTA-001 / align here |
| D8 | Should a **manual** merge of auto-split children record the line in BadLineMemory (an operator signal)? | yes / no |
| D9 | Ordering vs CORE-GHOST-ZONE-DELTA-001: land Stage 0 telemetry here first, shared with DELTA | yes / no |
| D10 | Out of scope but observed: on 36 CPUs, fill-cores (target 35) and MAX_REGIONS 72 allowed the I-0 storm of ~50 tiny regions. Track as a separate gate? | — |

STOP. No implementation until the owner reviews.

## 8. Stage 0 telemetry — implemented (a9c72b724), first evidence (2026-11-09)

Owner 2026-11-09: Stage 0 telemetry only, no enforce. Measurement only — split / merge decisions unchanged (observer
runs before `tree.split`, failures are caught and ignored). `/nestworld splitguard`; `-Dnestworld.splitGuardTelemetry=false` off.

Run on the production-pack copy `/root/pi-smoke` (live world copy, live pin files, region layout copied from live =
#0 [..2] / #1 [3..], i.e. the X = 2|3 line), 0 players, core = live jar + Stage 0 classes
(`patched3…445…universal.jar` 781d8ac6). Evidence `/root/airis-2026-10-25/evidence-be-ready/sg-stage0-check-run{1,2}`.

```
ghost cost (mirrored live layout, before any test split)   33.3 ms/tick ewma; #0 = 24.9 ms (2 694 BEs), #1 = 6.0 ms
unchanged NBT between ticks (sampled 1/16)                 92.3–95.7 %   → CORE-GHOST-ZONE-DELTA-001 baseline
top ghost save cost by type                                 ae2:cable_bus 4.5 us × many (16 %), extendedae_plus:assembler_matrix_hybrid_plus 96 us,
                                                            mm:*_item_port_* 46–82 us, expatternprovider:ex_pattern_provider 76 us, ex_drive 205 us
known-bad split reproduced: manual split of the base region #0 (cut Z = 2, through the base)
  PREDICT   T_R 28.4 ms, parentGhost 25.1 ms, strip BEs 4 689 (pinned 2 667), G 51.0 ms, band normal/pinned 1 507/1 496,
            M <= 16.4 ms, gain 0.63 ms, net −66.8 ms → shadowVerdict WOULD_VETO_LINE (calc 6.4 ms, one-shot)
  MEASURED  +30 s: child 18.45 / 0.17 ms, gain 9.97 ms, ghost delta +24.1 ms (children 44.3 / 4.9 vs parent 25.1),
            MAIN BE +2.7 ms, net −16.8 ms; G meas / pred 0.47
  → verdict direction CORRECT; magnitudes need Stage 1 calibration (G overestimated ~2×, gain underestimated —
    chunk-cost shares do not capture where the region thread's time goes)
```

Live (read-only, existing `/nestworld ghostzones`, 0 players, ~24 min after the 13:00 restart): ghost sync avg
**27.6 ms/tick** (max 153 ms), 3 316 BEs serialized per tick, server tick 53 ms, layout #0 [..2] / #1 [3..] — the known
bad X = 2|3 line is the CURRENT live layout (restored from `nestworld-regions.dat`). CONFIRMED.

Harness note: the soak H on the copy left 13 persisted regions (its own split steps) — the copy's TPS ≈ 7.7 in H also
reflected that fragmentation; the copy's layout was replaced by the live one for this check (old one kept in
`/root/pi-smoke/pins-backup-2026-11-09/`).

## 9. Live incident 2026-11-09 and emergency mitigation

```
incident (CONFIRMED)   5 × ServerHangWatchdog (single tick > 60 s) at 02:42, 05:11, 06:49, 08:44, 11:17, 0 players,
                       systemd auto-restart masked them. Trigger 01:52 auto-split of #0 at X = 2|3 (through the base,
                       cost 50 ms); the layout persists in nestworld-regions.dat and is restored on every boot.
                       4 / 5 watchdog stacks: main thread in BoundaryManager.syncGhostZones → saveWithFullMetadata
                       (titanium, AE2 CableBusContainer.writeToNBT, NBT); 1 / 5 in runBlockEntityPhase.
causal chain           auto-split X=2|3 → dense base in the ghost strip → full saveWithFullMetadata per tick
                       (~3 300 BEs, 21–30 ms/tick) → allocation / latency spikes (3.5 s ticks) → 60 s hang.
                       GC as the last link: HYPOTHESIS (GC / safepoint log enabled from 16:17 to settle it).
mitigation             333fefe93 -Dnestworld.autoSplit=false (default true = unchanged; blocks only the automatic
                       split queue; manual split, merges, layout restore, free-running unchanged) + Stage 0, deployed
                       16:17 (core patched4 f81fd364, backup /root/core-hotpatch-2026-11-09/live-2026-11-09-autosplit/),
                       -Xlog:gc*,safepoint → logs/gc.log; then `merge 0 1`.
production A/B         before merge: ghost ewma 16.4 ms (27.6–29.9 ms avg earlier), server tick 62.8 ms, MAIN BE 10.8 ms
                       after merge (+4 min): ghost 0.06 ms, 0 BEs serialized, server tick 39.6 ms, TPS 20.0, MAIN BE 8.0 ms,
                       merged region 9.9 ms; auto-split suppressed (AUTO_SPLIT_SUPPRESSED), 0 new splits.
```

autoSplit=false is a stabiliser, not the fix. It leaves production JVM args once Stage 2 + 3 are verified and stays only
as an emergency kill switch.

## 10. Permanent fix — owner plan 2026-11-09

```
p95-tail / avg / fill trigger → evaluate multiple candidate lines → predict gain − ghost serialization − MAIN band
→ no good line → NO SPLIT (a 30 ms region is better than a split that adds 25 ms ghost on MAIN)
→ good line → SPLIT → 30 s observation → good: keep | bad: REGRET MERGE + line cooldown
```

| stage | content | gate |
|---|---|---|
| 0 | telemetry (this doc §8) — ghost ns, strip BEs, cost by type, MAIN band, predicted vs measured | DONE, live; production A/B above |
| 1 | shadow scorer over ALL candidate lines: `net = parallelGain − ghostCost − mainBandCost`, log `WOULD_REJECT` per line; no decision change | must consistently reject X=2\|3 and not reject good splits; calibrate k_ghost / gain |
| 2 | enforce: line allowed only if `predictedNet > margin` (≈ +3 ms/tick initially); else try next line; none → no split | after Stage 1 calibration |
| 3 | split-regret: 30 s post-split measurement; `measuredNet < −3 ms/tick` → automatic regret merge (bypasses the 35-region rule) + line cooldown ≈ 30 min | closes merge → 69 s → same bad split |
| 4 | startup layout validation: after a stabilization window, a restored split that is known-bad / clearly negative → regret merge | regions.dat must not restore a known-bad layout forever |
| 5 | CORE-GHOST-ZONE-DELTA-001: change-driven snapshots, skip unchanged (90–96 % measured), skip unused / MAIN-domain BEs, snapshot generation, full fallback on uncertainty | second layer; does not replace the guard |

Exit: Stage 0 → shadow → X=2|3 proven negative → enforce → regret merge → autoSplit=true again.

## 11. Stage 1 shadow scorer — first evidence (2026-11-09, production-pack copy, live pins + layout)

Implementation: commit after a1ea1229d (`SplitGuardTelemetry.shadowScore`, hook `SplitObserver.wantsSplit`). Every time
the heuristic wants to split (applied or suppressed by autoSplit=false), every line on both axes that passes the
scorer's 10 % separation rule is scored `net = gain − G − M` (gain from chunk-cost shares, G = Σ strip BE save cost,
M = band chunk-cost share × T_R); verdict `net > 3 ms`. No decision changes. Evidence
`/root/airis-2026-10-25/evidence-be-ready/sg-stage1-{shadow,positive}-run*`.

```
known-bad layout / base only (sg-stage1-shadow-run1)
  merged region, fill-cores trigger: candidates 9–10, accepted 0 → NO_GOOD_LINE (would stay whole)
  best line X = 2: net −29.6 ms, G = 26.4 ms (3 265 strip BEs)  ← live measured ghost on X=2|3: 27.6–29.9 ms avg
positive control: 2 × ~430 floating cows at x = ±1 600, no BEs between (sg-stage1-positive-run2)
  T_R 40.5 ms: candidates 205, accepted 187–189 → SPLIT_AT_BEST X = −99 (net +13.7 ms, G 0, M 0)
  today's scorer choice under the same load: X = 2 (through the base) → shadow net −15.4 ms → WOULD_REJECT
```

Findings: (1) CONFIRMED on the copy — today's band-heat scorer picks the base line X = 2 even when good lines exist;
the shadow scorer rejects it and accepts the good ones. (2) G for X = 2 predicted 26.4 ms vs 27.6–29.9 ms measured live.
(3) Cold-start bias fixed: with no ghost strips (single region) every type fell back to 30 us → G 98 ms for X = 2; the
default is now 8 us (live mean) and unseen types use the mean of measured saves. (4) Gain is still a chunk-cost-share
model (underestimated 0.63 vs 9.97 ms in §8) — calibration item before Stage 2. (5) Test hygiene: pi-smoke runs on the
same host caused 2–3 s "Can't keep up" on live (only inside the test windows, also with taskset 0-7) — further copy
tests only when live has 0 players.

Stage 1 exit criteria still open: shadow verdicts on real auto-split triggers on live (autoSplit=false keeps them
coming as AUTO_SPLIT_SUPPRESSED + SPLIT_GUARD_SHADOW), gain calibration, a second positive case with BEs on both sides.

## 12. Calibration run 1 (2026-11-09, pi-smoke, `sg-calibrate-run1`, explicit lines via `/nestworld splitat`)

| line | pred net | pred G | meas ghost Δ | MSPT before → after (net) | G meas/pred |
|---|---|---|---|---|---|
| base X=2 | −29.4 | 26.1 | +32.8 | 19.8 → 51.3 (−31.5) | 1.26 |
| base Z=1 | −58.7 | 53.8 | +57.7 | 18.7 → 83.1 (−64.4) | 1.07 |
| cows + X=2 (region 40 ms) | −22.2 | 27.4 | +16.8 | 40.0 → 31.2 (+8.8) | 0.61 |
| cows X=−99 / X=50 | +10.2 / +11.1 | 0 | — | split refused ("bounds / locks") — to investigate | — |

STRONGLY INDICATED: G and net match MSPT for the base lines. Gain model wrong when the region is the bottleneck
(cows + X=2: predicted −22, measured +8.8 → a false reject, the safe direction). Before Stage 2: gain from the barrier /
MSPT critical path instead of chunk-cost shares; reproduce the two refused positive-control splits.

## 13. Stage 2 / 3 implemented (4958ee807) — enforce run 1 (2026-11-09, pi-smoke, `sg-enforce-run1`)

Flags (TEST-ONLY on the copy): `-Dnestworld.autoSplit=true -Dnestworld.splitGuard=enforce`; production default stays
`shadow` (no decision change); live keeps autoSplit=false. Core patched7 `2bd504e4` (gain model 591afc2c9 + Stage 2/3).

```
base region #3 (contains the base)     auto-split triggers (fill-cores / p95-tail) → VETO every time (68 vetoes), base line never applied
cows, region #3                        guard split at X = −102 between the clusters (predicted +10.2 ms; today's scorer: no line)
cows, region #4                        guard split at X = 100 (predicted +4.2, measured MSPT +0.8, component +1.1)
manual bad split #0                    regret merge after 30 s (MSPT −146.7) → #7; line blocked 30 min
X = −102 split                         FALSE regret: MSPT 59 → 83 (copy overloaded, MSPT 59–230 during the run), component net −11
                                       driven by a ghost "before" deflated by lock misses (parent 13.4 vs ~33 ms); regret merge FAILED
                                       (2 ms split lock) and was not retried
```

Fixes (next commit): regret only when MSPT net AND component net < −3 ms; regret merge retried ≤ 60 s; a lock miss freezes
the owner's ghost EWMA instead of decaying it to 0; VETO log ≤ 1/min/region; bad-line key = the applied (clamped) line.
Still open before production enforce: rerun on a quiet copy (no concurrent tests), and a regret test with a real base line.
