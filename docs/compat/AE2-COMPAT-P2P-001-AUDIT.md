# AE2-COMPAT-P2P-001 — audit (P2P state, transfer paths, external-callback boundaries)

**Status: AUDIT ACCEPTED (owner, 2026-10-15). Decisions Q1–Q5 recorded in §9. No P2P code is written yet.**
- Source: AE2 15.4.10, decompiled (`/root/mod-compat-audit/src/appliedenergistics2-forge-15.4.10`).
  Line numbers refer to that tree.
- NestWorld Core code is cited from HEAD (`a34eff745`+).
- Confidence words: CONFIRMED FROM CODE (read; not run), CONFIRMED (run), STRONGLY INDICATED,
  UNKNOWN.
- Prior records: `AE2-COMPAT-P2P-001.md` (finding), `AE2-COMPAT-SERVICE-MUTATION-001.md` §2
  (`updateFreq` is a synchronous postcondition).

## 0. Summary

The audit found **three separate P2P hazard classes**. The approved direction ("per-grid P2P
guard, snapshot readers, linearizable writers, never hold the guard across a foreign callback")
fully addresses only the first.

| # | Class | What | Closed by the approved direction? |
|---|---|---|---|
| **H1** | P2P service maps | `inputs` (`HashMap`) and `outputs` (`LinkedHashMultimap`) mutated by `add/removeNode` and `updateFreq` while transfer paths, `wakeInputTunnels` and ME `updateConnections` read or iterate them on other threads. `newFrequency()` and the bind are two calls | **Yes** (per-grid `P2PState`, §4.1) |
| **H2** | Per-tunnel recursion counter | `CapabilityP2PTunnelPart.accessDepth` is a plain `int`, the recursion guard of **every** FE / item / fluid transfer. Two threads transferring through the same tunnel corrupt it. The tunnel can then **permanently** return the empty handler, or throw `IllegalStateException` | **No**, a separate small fix (§4.2) |
| **H3** | **Non-local capability forwarding across regions** | A transfer that enters the input tunnel on thread T (the caller's region, or main) calls `getBlockEntity(outputFacingPos).getCapability(...).receiveEnergy/insertItem/fill` **synchronously**, at the output's position, which is anywhere on the grid: another region, or another dimension. Under NestWorld that call is either **a detached NBT copy** (ghost zone: the transfer is lost) or **a live foreign BE mutated from a non-owner thread** | **No.** No P2P-local guard can make a synchronous call into another region's block entity correct. **Model unknown → STOP (§5)** |

Two facts matter for scope:
- **H2 and H3 exist today, with AE2 fully pinned.** A pin moves block-entity *ticks* to main
  (`NestworldPins`). A capability call made by an unpinned caller still runs on the caller's
  thread: for example a Pipez or Mekanism pipe ticking on a region thread pushing into an FE P2P
  input. So the pinned baseline is not safe for cross-region P2P either. Exposure on live servers
  is **UNKNOWN**: islands and production were not inspected.
- **There is a gap in a closed audit.** TOPOLOGY-CALLBACK-001 states that the only synchronous
  world side effect on the P2P add/remove path is Light P2P's `markForUpdate`. **That is
  incomplete.** Redstone P2P's `onTunnelNetworkChange` synchronously runs
  `notifyBlocksOfNeighbors` at the output's position, which is arbitrary neighbour-block code, and
  this happens under `P(G)` (§3.4). Per the freeze rule, GRID-PHASE was **not** changed. This is
  recorded for an owner decision.

## 1. State inventory

| State | Owner | Type | Written by | Read by |
|---|---|---|---|---|
| `P2PService.inputs` | per grid | `HashMap<Short, P2PTunnelPart>` | `addNode`, `removeNode`, `updateFreq` | `getInput`, `getOutputs` (the input check), `newFrequency` (`containsKey`), `wakeInputTunnels` (iteration) |
| `P2PService.outputs` | per grid | `LinkedHashMultimap<Short, P2PTunnelPart>` | `addNode`, `removeNode`, `updateFreq` | `getOutputs` (live view → `stream().toList()`), `updateTunnel` (iteration), `updateFreq` (`containsValue`) |
| `P2PService.frequencyGenerator` | per grid | `java.util.Random` (thread-safe) | `newFrequency` | — |
| `P2PTunnelPart.freq` | per part | `short` (not volatile) | `setFrequency` (from `updateFreq`), `readFromNBT`, `readFromStream` (client) | every `getInput` / `getOutputs` on that part (`this.getFrequency()`), `exportSettings` |
| `P2PTunnelPart.output` | per part | `boolean` | `setOutput` (memory card, attunement, `importSettings`) | `addNode`, `removeNode`, `updateFreq` (which map), `getCapability` (which handler) |
| `CapabilityP2PTunnelPart.accessDepth` | per part | `int` | `getAdjacentCapability` (++), `CapabilityGuard.close` (--) | `CapabilityGuard.get` (`==0` → ISE, `==1` → real, `>1` → empty) |
| `CapabilityP2PTunnelPart.inBlockUpdate` | per part | `boolean` | `sendBlockUpdate`, `onNeighborChanged` | the same |
| `RedstoneP2PTunnelPart.power`, `.recursive` | per part | `int`, `boolean` | `putInput`, `onNeighborChanged` | `isProviding*Power`, `setNetworkReady` |
| `LightP2PTunnelPart.lastValue`, `.opacity` | per part | `int` | `doWork`, `setLightLevel`, `onNeighborChanged` | `getLightLevel`, `blockLight` |
| `MEP2PTunnelPart.connections`, `.pendingUpdate` | per part | map, enum | `tickingRequest` (T2), `updateConnections` (main callable) | `onTunnelNetworkChange` |

## 2. Method table

"Caller thread" is the thread that can execute the method under NestWorld with free-running
regions. "Region" means any `RegionThread`. "Owner" means the region owning the part's block.

| Method (file:line) | R/W | State touched | Possible caller thread | Holds an external callback? | Synchronous postcondition? | Can defer? | Needs snapshot? | Needs linearization? |
|---|---|---|---|---|---|---|---|---|
| `P2PService.addNode` (:66) | W | `inputs` / `outputs` + `updateTunnel(freq, !output, false)` | topology thread: the owner region or main, **under P(G)** (GRID-PHASE) | **yes**: `onTunnelNetworkChange` of the opposite side (§3.4) | yes: membership is visible to the next reader | maps: no. Callbacks: already partly deferred by AE2 | — | **yes** (writer) |
| `P2PService.removeNode` (:49) | W | the same | the same | the same | yes | the same | — | **yes** |
| `P2PService.updateFreq` (:105) | W | maps + `t.setFrequency` (→ `markForSave` / `markForUpdate` of the host) + 4× `updateTunnel(…, configChange=true)` | player interaction (memory card, attunement): the owner region or main (STRONGLY INDICATED: PLAYER_BLOCK_INTERACTION routing) | **yes**: config + network change on every tunnel of the old and new frequency, which can sit in **other regions** | **yes** (SERVICE-MUTATION-001 §2: the caller exports the frequency right after) | no (approved ruling) | — | **yes** |
| `P2PService.newFrequency` (:131) | R | `inputs.containsKey` loop, `Random` | the same as `updateFreq` | no | the result is used immediately by the caller | no | — | **yes, together with the bind** (§4.1) |
| caller pair `newFrequency` → `updateFreq` (`P2PTunnelPart:193–196`; also `P2PPlotHelper:52–53`, test plots) | R then W | — | the same | via `updateFreq` | yes | no | — | **must be ONE writer transaction** |
| `P2PService.getInput` (:152) | R | `inputs.get` | **any**: FE / item / fluid output handlers (`extractEnergy`, `canExtract`, `get*Stored`, …) on the caller's thread; Light / Redstone network change; capability `onNeighborChanged`; Jade data provider (server thread) | the **callers** then call foreign code | no | — | a single reference read, but on a non-thread-safe `HashMap` (§3.1) | read must observe a consistent version |
| `P2PService.getOutputs` (:147) | R | `inputs.get` + `outputs.get(freq)` **live view**, streamed to `toList()` | **any** (same as above, plus ME `updateConnections` on main) | the callers then do | no | — | **yes**: iterates a live multimap view (§3.1) | yes |
| `P2PService.wakeInputTunnels` (:38) | R | iterates `inputs.values()` | whoever posts `GridBootingStatusChange` / `GridPowerStatusChange`: `EnergyService.publicPowerState` (:205), i.e. **main** END tick under the energy domain; booting from pathing | `wakeDevice` (T2, addon-handled) | no | yes (a wake request) | **yes** (iteration) | no |
| `P2PService.updateTunnel` (:82) | R + callbacks | iterates `outputs.get(freq)`, `inputs.get(freq)` | inside the writers above | **yes**, by type: Capability → `addCallable(sendBlockUpdate)` (deferred to main); ME → `wakeDevice` (T2); Light → `doWork` / `setLightLevel` → `markForUpdate`, **synchronous**; **Redstone → `setNetworkReady` → `putInput` → `notifyBlocksOfNeighbors`, synchronous world update at the output's position** | — | AE2 already defers capability and ME. Light and Redstone are synchronous | iterate a snapshot | callbacks must run **after** the writer releases (§4.1) |
| `FEP2P InputEnergyStorage.receiveEnergy` (:38) | R + foreign | `getOutputs()` **twice** (count, then iterate), per target `getAdjacentCapability().get().receiveEnergy` | **any**: the thread of whoever pushes energy into the input | **yes**: a foreign `IEnergyStorage` at the output's position | **yes**: returns the accepted amount synchronously | no (the Forge contract) | **yes**: one snapshot for both uses | the target call is H3 |
| `FEP2P get(Max)EnergyStored` (:73, :85) | R + foreign | the same pattern | any | yes | yes | no | yes | H3 |
| `FEP2P OutputEnergyStorage.*` (:124–171) | R + foreign | `getInputCapability()` → `getInput()` → input's `getAdjacentCapability` | any: whoever pulls from an output | yes: a foreign storage at the **input's** position | yes | no | yes | H3 |
| `ItemP2P InputItemHandler.insertItem` (Item:43) | R + foreign | the same pattern, `ItemHandlerHelper.insertItem` per target | any | yes | **yes**: the remainder stack is returned synchronously | no | yes | H3, plus **item conservation** |
| `FluidP2P fill` (Fluid:53) | R + foreign | the same | any | yes | yes | no | yes | H3 |
| `CapabilityP2PTunnelPart.getAdjacentCapability` / `CapabilityGuard.get/close` (:40, :99–124) | **RW** | `accessDepth++ / --`; `get` → `level.getBlockEntity(facingPos)` | any, **concurrently on the same part** when two callers feed one frequency, or when one output is shared by several inputs' transfers | `te.getCapability(...)` (foreign) | — | — | — | **H2**: the depth must be per-thread (§3.2) |
| `deductEnergyCost` / `deductTransportCost` (`P2PTunnelPart:260–279`) | W | `EnergyService.extractAEPower` | the transfer thread, after the target loop | AE2 energy (ENERGY-001 domain, VERIFIED) | no | — | — | already linearized by ENERGY-001, **provided no P2P guard is held** (§4.1 R3) |
| `RedstoneP2P putInput` / `sendToOutput` / `onNeighborChanged` (RS:44–141) | RW | `power`, `recursive`; `notifyBlocksOfNeighbors` at the **output** | the input's `onNeighborChanged` thread (the owner of the input, or main), and writers via `updateTunnel` | **yes**: synchronous neighbour updates in another region | yes (vanilla redstone semantics) | UNKNOWN | — | H3 (world mutation, not capability) |
| `LightP2P doWork` / `setLightLevel` / `onTunnelNetworkChange` (Light:64–146) | RW | `lastValue`, `opacity`; `markForUpdate` on the output host; `level.getBrightness` at the input | the input's thread, T2 tick, writers | `markForUpdate` (vanilla block update / sync) in another region | weak (a visual light level) | likely | — | H3 (mild) |
| `MEP2P updateConnections` (ME:129) | R + W(topology) | `getOutputs()`; `GridHelper.createConnection` / `destroy` | **main** (`TickHandler.addCallable`, QUEUE-001) | topology → P(G) (main is reentrant) | — | already deferred | **yes** (`getOutputs` vs region writers) | covered by P(G) for the topology half |
| `P2PStateDataProvider.provideServerData` (igtooltip:59–74) | R | `getOutputs().size()`, `getInput()` | server thread (tooltip request) | no | no | — | yes | no |

## 3. Findings

### 3.1 H1: map races (CONFIRMED FROM CODE)

Writers and readers run on different threads with no synchronization:
- writers: owner region or main for `updateFreq`, and the P(G) holder for `add/removeNode`;
- readers: any region, plus main for `wakeInputTunnels` and ME `updateConnections`.

Consequences:
- `HashMap.get` racing `put` / `remove` can miss a present key or see a stale one. A resize race
  can corrupt buckets.
- `getOutputs` streams `outputs.get(freq)`, a **live** view of the `LinkedHashMultimap` value
  set, into a list. A concurrent `put` / `remove` gives a `ConcurrentModificationException` or a
  torn list.
- `wakeInputTunnels` iterates `inputs.values()` on main during a region-thread `updateFreq` → CME.
- **`newFrequency` + bind.** `newFrequency()` only *picks* a value not in `inputs`. Registration
  happens later in `updateFreq` (`P2PTunnelPart:193→196`). Between the two calls, another writer
  can register the same value. `updateFreq` then does `inputs.put(freq, t)`, which **silently
  displaces** the other input: the map loses it, while that part still believes it is the input
  of `freq`.

  The probability of a collision of two random shorts is low, but the gap between check and use is
  real. Under the owner's rule it must be one linearizable transaction (TZ §1.2).
- A reader also reads `t.freq` (a plain `short`) and then the maps. A concurrent `updateFreq` can
  interleave between the two reads, so the reader routes with the new frequency against old maps,
  or the reverse. This interleaving is impossible in vanilla.

### 3.2 H2: `accessDepth` (CONFIRMED FROM CODE)

`accessDepth` is AE2's **recursion guard**. Depth 1 means the real neighbour capability; depth
greater than 1 means "we re-entered ourselves, return empty" (loop prevention, e.g.
input → output → input). It assumes thread confinement. When two threads use the same part:

| Interleaving | Effect |
|---|---|
| T1 `++` (1), T2 `++` (2), T2 `get` | T2 is treated as recursion → `emptyHandler` → **transfer silently refused** |
| `++` lost-update (both read 0, both write 1), then two `close` | depth −1 → `IllegalStateException("Close has been called multiple times")` thrown out of a foreign mod's pipe tick |
| `--` lost-update | depth stuck ≥ 1 → **every later `get` on this part returns empty or ISE → the tunnel is permanently dead until reload** |

Who reaches the same part concurrently:
- several callers of one input on different threads, e.g. two pipes in two regions feeding one
  FE / item / fluid P2P input. Each transfer increments the depth of **every output** it visits;
- an output pulled by several extractors on different threads.

This hazard exists **with AE2 pinned**, because the callers are the pipes' threads.

### 3.3 H3: non-local capability forwarding across regions (CONFIRMED FROM CODE; runtime NOT reproduced)

Path: the caller on thread T calls `input.receiveEnergy` (or `insertItem` / `fill`). That reaches
`target.getAdjacentCapability().get()`:
```
BlockEntity te = self.getLevel().getBlockEntity(target.getFacingPos())   // CapabilityP2PTunnelPart:106
te.getCapability(cap, side).orElse(empty).receiveEnergy(...)            // foreign mod code
```
The target's position is the **output tunnel's** neighbour. The grid is not spatially bounded:
outputs can be anywhere, including other dimensions through a quantum bridge.

NestWorld's `Level.getBlockEntity` (`projects/forge/.../Level.java:647`) routes `RegionThread`
callers through `RegionChunkView.getBlockEntity` (`RegionChunkView.java:141`):

| Target position relative to T | Returned | Effect of `receiveEnergy` / `insertItem` / `fill` |
|---|---|---|
| owned by T's region | live BE | correct |
| foreign region, chunk within `GHOST_DEPTH = 2` of **any** edge of the owner region (`BoundaryManager.withinGhostDepth`) | **detached copy**: `BlockEntity.loadStatic(pos, state, tag)` from the last ghost snapshot (`BoundaryManager.java:230–233`), or `null` if there is no snapshot entry | the copy accepts energy or items and is discarded. **The P2P returns "accepted" and the source deducts. Energy / items are destroyed.** (`null` → empty handler → 0, no loss) |
| foreign region, deeper | live BE, read under `tryReadLock`, but the lock is **released before the BE is returned** (`RegionChunkView:166–190`); on timeout, a raw unsynchronized read | live foreign BE **mutated from a non-owner thread**, concurrently with the owner region's free-running tick. This is the race class the border band exists to prevent |
| T = main (pinned / border-band caller) | raw live read (`Level.getBlockEntity`, not a RegionThread) | the same foreign-thread mutation, concurrently with the owner's free-running tick |
| other dimension | that level's chunk view, owner ≠ T | as above |

Why this is not ordinary pipe behaviour:
- NestWorld's existing argument for block-entity-to-block-entity I/O safety is **locality**.
  Pipes and hoppers reach only adjacent positions, and the border band routes edge block
  entities to main (memory: border-band-protects-be-io).
- P2P (and any wireless forwarder) breaks locality. The caller is near the input, the effect is
  near the output, at unbounded distance.

The same holds for:
- `OutputEnergyStorage` → the **input's** neighbour (pull direction);
- Redstone P2P `notifyBlocksOfNeighbors` at the output (synchronous world updates in a foreign
  region);
- Light P2P `markForUpdate` (milder).

Classification under `docs/RACE_CLASSIFICATION.md`: **C5 (region-ownership dispatch)** or **C7
(redesign)**. It is not C1–C3/C6: no P2P-local lock, snapshot or container change makes a
synchronous call into another region's block entity owner-correct.

### 3.4 Audit gap in TOPOLOGY-CALLBACK-001 (CONFIRMED FROM CODE)

What happens:
1. `addNode` / `removeNode` runs under `P → T0 → domains` and calls `updateTunnel(freq, !output, false)`.
2. That calls `onTunnelNetworkChange` on the opposite side.
3. For `RedstoneP2PTunnelPart` this is `setNetworkReady` → `putInput(in.power)` → when the power
   changes, `notifyNeighbors` → `Platform.notifyBlocksOfNeighbors` around the **output**: vanilla
   `neighborChanged` on arbitrary blocks, which may be other mods' code.

TOPOLOGY-CALLBACK-001 §1 lists only Light P2P `markForUpdate` as the synchronous world side effect.

What already limits the damage, and what is not known:
- **Known:** a callback that re-enters another grid's topology meets GRID-PHASE's
  nested-P fail-closed rule (`acquireNested` → unaudited → DEGRADED), so the kernel does not
  silently go wrong.
- **Not known:**
  - whether arbitrary neighbour code under `P(G)` + domains can reach a blocking acquisition;
  - the lock-order effect of another grid's energy domain acquired under `P(G1)`.

Per the freeze rule, GRID-PHASE was **not** touched. Decision needed (§6 Q4).

## 4. Proposed model for H1 and H2 (for review; not implemented)

### 4.1 `P2PState` per grid (H1)

```
P2PState (one per P2PService / grid)
  lock          short critical sections only (ReentrantLock or StampedLock; no waiting on anything inside)
  generation    long, ++ on every structural mutation
  inputs, outputs  (AE2's maps, accessed only under lock)
  view(freq) → immutable FreqView{gen, input, outputs:List}   cached per frequency, rebuilt on mutation
```
Rules:
- **R1 (readers).** `getInput` / `getOutputs` / `wakeInputTunnels` / `updateTunnel` iteration
  read an immutable `FreqView`, taken under the lock (or published through a volatile per-frequency
  map). The reader then **releases** and works on the snapshot. `getOutputs` called twice in one
  transfer becomes one snapshot.
- **R2 (writers).** `add/removeNode` and `updateFreq` mutate under the lock, `generation++`,
  republish the affected views, collect the affected tunnels, **release**, then run the
  `updateTunnel` callbacks on the collected list, in AE2's order. Map mutation is linearized at the
  lock. Callbacks are never under the lock.
- **R3 (no lock across foreign code).** The lock is never held while calling:
  - a capability;
  - `deductEnergyCost` (the ENERGY domain);
  - `wakeDevice` (T2);
  - `markForUpdate` / `notifyNeighbors`;
  - any part callback.

  Lock order: P2PState is a **leaf**. Nothing is acquired under it.
- **R4 (allocation).** A new writer operation `bindNew(t)` = under the lock, pick a random value
  not in `inputs`, remove `t` from the maps, `t.setFrequency(v)`, insert, `generation++`.
  - The memory-card path (`P2PTunnelPart:192–196`) is redirected to `bindNew` when
    `needsNewFrequency`. `P2PPlotHelper` (test plots) likewise.
  - `newFrequency()` alone remains for callers that only want a value, and stays non-binding.
  - `t.setFrequency` inside the lock calls `host.markForSave/markForUpdate`. These are flags on the
    part's own host (owner thread), with no foreign code. To be verified in the design step.
- **R5 (`freq` read).** A reader that needs `t.freq` and the maps consistent takes both from one
  snapshot of `(t.freq, generation)`. A transfer whose snapshot generation is stale at the end is
  **not** retried in v1. AE2 has no transactional semantics across targets, and every target call
  already completed with an honest return value. This is the "stale snapshot completes safely"
  branch of TZ P2P-5.
- **Parallelism.** One lock per grid. Different grids never share a lock (TZ P2P-6).

### 4.2 Per-thread recursion depth (H2)

Replace `accessDepth`'s field semantics with a per-thread depth for that part, for example
`ThreadLocal<IdentityHashMap<Part, int>>` or a per-part `ThreadLocal<int[]>`. Recursion within one
thread keeps vanilla behaviour; concurrent threads no longer see each other's depth. This is a
Mixin redirect of the field reads and writes, with no lock.

### 4.3 What 4.1 + 4.2 do **not** cover

H3 (§3.3) and the Redstone / Light world side effects. With 4.1 + 4.2 alone, P2P stays
**NOT eligible** for region execution, and the cross-region loss and race stay present.

## 5. H3: open model (STOP — owner decision needed)

The TZ requires a STOP when the audit shows an unknown synchronous contract or external-callback
boundary. H3 is exactly that:
- the Forge capability contract is synchronous (accepted amount / remainder returned);
- the target is owned by another region's thread.

Candidate directions, none chosen:

| Option | Idea | Correctness | Cost / risk |
|---|---|---|---|
| **A. Owner-gated target** | Before each target call, resolve the target position's owner region. If the current thread is not its owner, treat that target as unavailable for this call (0 accepted / stack returned) | no loss, no foreign mutation; honest synchronous return | **cross-region P2P stops transferring** whenever the caller is in another region. Functional regression; P2P across regions is common |
| **B. Buffered P2P** | The input accepts into a bounded P2P-owned buffer (per output or per frequency); each output drains it on **its own** region thread during its tick | conservation held by the buffer (linearizable); no foreign mutation | changes AE2 semantics: one-tick latency, `simulate` answers become buffer-capacity answers, item / fluid buffers need NBT persistence and drop-on-break rules. Largest change |
| **C. Core cross-region synchronous call** | A Core primitive "execute this call as region R": for example, non-blocking `tryLock` of R's chunk write lock, run, release; else refuse (0) | needs Core semantics: Phase 9 ownership assertions key on **thread identity**, not lock possession. UNKNOWN whether that is acceptable | conflicts with the owner's architecture note: **the addon has no Core dependency**. It would be a Core-side C5 fix, like a generic "non-local forwarder" contract for P2P, Flux Networks, Mekanism QIO / entangloporters, … |
| **D. Classify and pin by enforcement** | Keep P2P serialized: route the whole transfer of a cross-region frequency to main and refuse it on region threads | main still races the owner's free-running tick (§3.3, T = main row) unless the target region is quiescent. **Not correct** under free-running regions | — |
| **E. Exclude** | Declare cross-region P2P (FE / item / fluid / redstone / light) **UNSUPPORTED** in the audited matrix; enforce it with option A's owner check as a fail-closed guard plus a diagnostic counter | honest. No silent loss: refusal instead of destruction | a documented functional limitation for rc1 |

My assessment (for the review, not a decision):
- H3 is **not** an AE2-addon-local problem. It is the general "non-local capability forwarder"
  class.
- The detached-copy branch means that **today** a cross-region FE / item P2P whose output faces a
  block within 2 chunks of a region edge can destroy what it forwards. This should be **reproduced
  first** (fixture P2P-9 below) before any design is chosen.

## 6. Questions for the owner

1. **Q1 (H3 direction).** Which of A–E (or another) to design? Is Core allowed to own a generic
   non-local forwarding primitive (C), given the addon's no-Core-dependency rule?
2. **Q2 (reproduce first).** May I build fixture P2P-9 (below) on the scratch rig now, before any
   design, to turn H3's loss branch from CONFIRMED FROM CODE into CONFIRMED? It would be a test-only
   fixture with no P2P fix code.
3. **Q3 (H1 + H2 now).** Approve §4.1 + §4.2 as a separate implementation step independent of
   H3? Without H3 that step does not make P2P unpin-eligible. It does remove the map CMEs, the
   duplicate-frequency gap and the dead-tunnel counter that exist with the pin on.
4. **Q4 (TOPOLOGY-CALLBACK-001 gap).** Treat Redstone P2P's synchronous neighbour update under
   `P(G)` as:
   - (a) acceptable, with the nested-P fail-closed rule as the bound, documented; or
   - (b) a reason to defer that callback (re-opens frozen GRID-PHASE scope); or
   - (c) to be measured first, with a fixture recording whether any blocking acquisition is
     reachable from that neighbour code.
5. **Q5 (scope of tunnel types).** FE, item, fluid, redstone, light and ME are in AE2 15.4.10.
   Addon tunnels (ExtendedAE, MegaCells, …) are **not** in the manifest; the manifest lists only
   `P2PService` as an `IGridServiceProvider`. Proposed rule: any `P2PTunnelPart` subclass not in
   the audited set → manifest-ineligible → DEGRADED / retain pin.

## 7. Fixture plan (after review)

TZ P2P-1…7 apply to H1 / H2. The additional fixtures follow from this audit.

| Id | Scenario | Expect (fixed) | Negative control (old) |
|---|---|---|---|
| P2P-1 | reader holds a `FreqView` while `updateFreq` runs (barrier between snapshot and use) | reader completes on its snapshot; no CME | live-view iteration → CME / torn list |
| P2P-2 | two `bindNew` on the same grid from two region threads, barrier between pick and bind (forced equal pick via a seeded test hook) | distinct frequencies, both inputs present | `newFrequency`+`updateFreq` → displaced input |
| P2P-3 | add/remove membership racing `getOutputs` traversal | no CME; snapshot or new view | CME |
| P2P-4 | frequency change while an FE transfer is inside a foreign `receiveEnergy` (barrier inside the target) | `P2PState` lock **not held** (instrumented holder check); the writer proceeds | — (R3 instrumentation) |
| P2P-5 | tunnel removed while a transfer holds a snapshot | the transfer completes on the snapshot (honest returns); the next transfer sees the new view | — |
| P2P-6 | two grids, forced contention on each | no cross-grid blocking (holder trace) | — |
| P2P-7 | guard removed (negative patch) | deterministic duplicate allocation or CME | must FAIL |
| **P2P-8** | two threads transfer through one FE input concurrently (barrier between `++` and `get`) | per-thread depth: both get the real handler; depth back to 0 | shared `int`: the empty handler, or a stuck depth |
| **P2P-9** | FE (and item) P2P: the input fed on region A's thread, the output in region B facing a buffer within 2 chunks of B's edge, plus a deep variant | **conservation**: the source deducted = the target gained (energy and item counts) | **today's code: expected loss (detached copy)**, and foreign-thread mutation in the deep variant (owner-thread probe in the target) |
| **P2P-10** | Redstone P2P input added under `P(G)` with a neighbour that reacts | record the holder set while the neighbour code runs | — (measurement, Q4c) |

After that: the 120 s stress (multi-region, free-running, several P2P networks, FE + item + fluid,
topology / frequency churn), then a soak. Acceptance per the TZ §3 list, plus:
**0 conservation violations**, **0 stuck `accessDepth`**.

## 8. Unpin readiness impact (input for `AE2-UNPIN-READINESS.md`)

| Subsystem | Evidence | Remaining unknown | Can run on RegionThread? |
|---|---|---|---|
| P2P FE | this audit | H1, H2, **H3** | **NO** |
| P2P item | this audit | H1, H2, **H3** (+ item conservation) | **NO** |
| P2P fluid | this audit | H1, H2, **H3** | **NO** |
| P2P redstone / light | this audit | H3 world side effects; Q4 | **NO** |
| P2P ME | `updateConnections` on main via QUEUE-001; topology under P(G) | H1 (map reads vs region writers) | after H1 only |

Selective energy unpin (TZ §10) needs a **real enforcement boundary** for FE P2P. With H3 open,
that boundary must also stop cross-region FE P2P transfers in the **pinned** configuration
(§0, first bullet). A pin alone does not.

## 9. Owner decisions (2026-10-15)

### Normative finding
> AE2 pinning controls where AE2-owned ticks execute. It does **not** establish ownership
> correctness for synchronous capability calls entering AE2 from a RegionThread and exiting
> through P2P into another region.

So `FULL AE2 PIN` does **not** mean "P2P safe". This corrects the earlier mental model.

The general pattern is **NONLOCAL_SYNCHRONOUS_FORWARDER**. Candidates: AE2 P2P, Flux Networks,
Mekanism Entangloporter, and other teleport / proxy capabilities. In future, Core / GMEIL should be
able to **detect** this class. It should not try to fix it automatically by generic callback
routing.

### Q1: H3 for RC = option E, enforced as A (fail-closed)
```
P2P capability operation
  → resolve the actual target position / level
  → does the current thread own the target?
      YES → normal synchronous operation
      NO  → fail closed, with no target mutation:
              energy  0 accepted / extracted
              item    unchanged stack / empty result
              fluid   0 / empty
```
- **Cross-dimension is non-local**, unless a separate ownership contract exists.
- **B (buffering)** is a possible future design, but not a quick fix. It is a separate gate
  (persistence, backpressure, ordering, extraction semantics, crash recovery, conservation).
- **C (generic Core "execute arbitrary callback on owner") is REJECTED.** It is an escape hatch of
  the kind the Mod API deliberately avoids. Blocking on region B also creates an A→B→A deadlock
  surface.
- **Narrow Core contract required:** a read-only ownership query, `isCurrentThreadOwner(level, pos)`
  or equivalent.
  - Here correctness outweighs "the compat addon knows nothing about Core". A formal narrow
    ownership contract is better than heuristics based on `RegionThread`, ghost BEs or thread
    names.
  - If even this API is not added, the only provably safe v1 is: **P2P capability forwarding
    always refuses under NestWorld region execution**, because locality cannot be established
    reliably.
- The shape of that query, and how the addon reaches it without a compile dependency on Core, is
  a design item for review before implementation.

### Q2: P2P-9 before any H3 fix (APPROVED), in three discriminating configurations

| Id | Configuration | Expected |
|---|---|---|
| P2P-9A SAME-OWNER | the source and the output target are in one ownership domain | conservation PASS |
| P2P-9B REMOTE-GHOST | the target is in another region's ghost zone | current code reproduces it: the source reports a transfer, but the target's **persistent** state does not gain the same amount |
| P2P-9C REMOTE-LIVE | the target is deep in another region | a cross-owner mutation is observed |

- Invariant: `source decrease == persistent destination increase + explicitly accounted transport
  buffer`. There is no buffer today, so `source decrease == destination increase`.
- Item / fluid variants are wanted as well, as long as the fixture does not grow
  disproportionately.
- The fixture must **distinguish** detached-snapshot loss (9B) from live foreign-owner mutation
  (9C). They are two different H3 failure modes.

### Q3: H1 and H2 are closed independently of H3 (APPROVED)
- Target statuses:
  - `P2P-MAP-STATE` CLOSED / VERIFIED;
  - `P2P-ACCESS-DEPTH` CLOSED / VERIFIED;
  - **AE2-COMPAT-P2P-001 stays OPEN, blocked by H3**.
- H1:
  - per-grid `P2PState` (lock, generation, immutable snapshots);
  - `getOutputs()` never returns a live view;
  - `newFrequency()`'s own semantics are **not** changed. A new operation
    `allocateAndBind(input)` (or an equivalent transaction boundary around the real call sites)
    does: LOCK → choose an unused frequency → verify it is still free → register the input →
    `generation++` → UNLOCK;
  - `inputs.put` must never silently displace another input as the result of a race.
- H2: `ThreadLocal` recursion depth, because recursion is a property of the dynamic call stack, not
  of the tunnel object:
  ```
  increment → try { transfer } finally { decrement; if zero → remove() }
  ```
  A negative fixture with two simultaneous transfers on **one** tunnel object is mandatory.

### Q4: Redstone callback under P(G): nested-P fail-closed is NOT sufficient
- `P(G)` is a structural exclusion primitive. It must not become a lock under which arbitrary
  neighbour-block code runs.
- **P2P-10 APPROVED first.** It records:
  - the thread;
  - whether `P(G)` is held;
  - the target's owner;
  - whether the neighbour callback re-enters AE2;
  - whether it requests topology;
  - whether it touches another region.
- If it confirms that a foreign neighbour callback runs under `P(G)`:
  - **GRID-PHASE stays CLOSED for its proven invariant**;
  - a new narrow finding **REDSTONE-P2P-CALLBACK-001** opens;
  - GRID-PHASE is not unfrozen as a whole.
- The narrow fix: under `P(G)`, determine the notification work and record a stable intent →
  release `P(G)` → execute the notification only in a valid owner context. If the target is in
  another region, this is H3 class again, and the notification cannot simply run on the current
  thread.

### Q5: unknown `P2PTunnelPart` subclass (APPROVED, with an addition)
```
unknown subclass of P2PTunnelPart
  → COMPAT_MANIFEST_UNKNOWN_P2P
  → manifestEligible=false → no unpin eligibility
  → AND P2P forwarding eligibility = false → its forwarding path fails closed
```
- DEGRADED (global AE2 serialization) does **not** make H3 safe, because it does not change the
  target BE's owner.
- "DEGRADED + keep pin" is **not** a mitigation for H3.

### Approved order
1. Record Q1–Q5 (this section).
2. P2P-9: reproduce H3 without a fix.
3. P2P-10: measure the Redstone callback boundary.
4. STOP: record the evidence.
5. Implement H1.
6. Deterministic H1 negative control.
7. Implement H2.
8. Deterministic H2 concurrent-transfer negative control.
9. Stress H1 / H2.
10. H3 stays OPEN, with the enforced cross-owner refusal design.

Do not write `AE2-COMPAT-P2P-001 CLOSED` after H1 / H2 until a real cross-owner boundary exists.
