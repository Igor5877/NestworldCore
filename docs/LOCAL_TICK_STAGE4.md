# Local Tick / Stage 4 — Architecture Decision & Progress

## Why this exists

A live incident on ATM9 (2026-08-08): a player built a falling-block "sand
duper" lag machine. The owning region's tick cost hit ~41ms, and the WHOLE
server's TPS collapsed to ~5 for every player — confirmed server-side, not a
client-rendering artifact (the player's own client skips rendering things
behind them, so FPS stayed 60 in both this test and an unrelated zombie-flood
test, yet the lag was still felt). Root cause: `RegionThreadPool.awaitLatch()`
waits for the SLOWEST region every tick — one hot region's cost leaks into
the global tick time for every player, even ones nowhere near it.

This is exactly the motivating case for the project's own staged Folia-style
plan (see `folia-actor-model-staged-plan` project memory): Region Mailbox →
Ownership(entity) → Ownership(chunk) → Local Tick → Eventual consistency.
This document covers the **Stage 4 design decision** and ongoing **Stage
1/2 hardening work**, reached after an exhaustive, evidence-first review
(read all 24 NestworldCore region-package files in full, plus ~700
vanilla-patched Forge files via parallel research passes) — not from
assumptions.

## Decision: what Stage 4 targets, and what it explicitly does NOT

**Target**: Global Logical Tick + independent region **execution** (not
independent **time**) + mailbox/message-passing cross-region interaction,
replacing `awaitLatch()`'s wait-for-slowest semantics so a region that
finishes early does not idle waiting on an unrelated slow sibling.

**Explicitly rejected**: "bounded barrier wait + local tick drift" — an
initial narrower-looking proposal (bound the barrier wait, let an overloaded
region fall behind in its own local tick counter, catch up opportunistically)
was designed, then independently reviewed (a second opinion was sought and
obtained), and rejected as unsafe. It looks like a small, reversible code
change but is actually a disguised sliver of full independent local time,
without the infrastructure that requires:

1. **`LevelTicks.java`** (scheduled redstone/fluid/crop/fire ticks) is pure
   vanilla (zero NestWorld patches) and keyed to ONE global `triggerTick`.
   `NestworldRegionSystem.runScheduledTicksPhase()` only parallelizes
   EXECUTION of already-collected due ticks — collection/bookkeeping inside
   `LevelTicks.tick()` stays strictly single-threaded on main, tied to the
   global counter. A drifted region's scheduled ticks fire at the wrong local
   moment relative to when the global counter says they're due, or get
   silently skipped — a logic break, not a perf degradation.
2. **Border-band routing's safety proof depends on ALL regions being parked
   simultaneously** when main touches border blocks. Drift breaks this
   directly.
3. **Global `MinecraftServer.tickCount`-based expiry** (chunk tickets,
   autosave, predictive-gen tickets, tracker throttling) would see a
   drifted region's tickets expire prematurely relative to its own
   un-caught-up progress — chunk/entity loss risk.

**Full independent per-region local time (true Folia-style Stage 5)** is
explicitly deferred until Stage 1-4 are stability-proven. It requires
rewriting `LevelTicks`, all `tickCount`-based expiry, cross-dimension tick
sequencing, and border-band semantics — a separate, much larger project.

## Structural blockers for full independent local time (confirmed by direct reading)

- `LevelTicks.java` — see above; zero regional awareness, single global
  `triggerTick`.
- `MinecraftServer.tickChildren()` (`server/MinecraftServer.java:873-925`) —
  sequentially ticks dimensions; `NestworldRegionSystem.tickAllRegions()`
  runs only for the overworld and blocks before the loop moves to Nether/End
  (single-threaded, vanilla). The overworld region barrier is ALSO the
  inter-dimension sync point today.
- `RegionTree.split()/merge()` — documented "applied atomically between
  ticks", requiring ALL regions simultaneously parked. Under independent
  time there is no single global pause point; split/merge would need to
  become a targeted quiesce-migrate-resume protocol between only the
  regions actually involved.
- Border-band routing (the main existing cross-region safety net) is safe
  only because of the synchronous global pause.
- `Ticket.timedOut()` and nearly all time-based expiry key off the single
  global `MinecraftServer.tickCount`.
- `WorldGrid.getRegionForChunk()` — O(active regions) linear scan over a
  `CopyOnWriteArrayList`. Not a logic blocker, but a scaling risk: this
  whole direction favors MORE, smaller regions once the barrier's
  wait-for-slowest cost drops, which stresses this exact lookup.
- **`TripWireBlock`'s chain can span 42 blocks**, exceeding the default
  `BORDER_BAND_CHUNKS=2` (32-block) width — the first confirmed real gap in
  vanilla's own "cascades can't travel 2 chunks" assumption, independent of
  which tick model is chosen. Any border-band redesign must account for it.

## Existing precedents already validating the mailbox direction

- `ThreadedLevelLightEngine.checkBlock()/runLightUpdates()` — vanilla's own
  light engine is ALREADY mailbox-isolated: methods post a `Runnable` to
  `sorterMailbox.tell(...)` instead of touching shared state directly;
  execution serializes on one dedicated worker regardless of caller thread.
- `ChunkMap.updatingChunkMap → visibleChunkMap` double-buffer, published
  once/tick via `promoteChunkMap()`.
- `ServerChunkCache.nestworldLoadedFull` (our own code) — ping-pong volatile
  snapshot of loaded-FULL chunks, refreshed once/tick, read lock-free from
  regions. The closest already-shipped prototype of a periodic sync epoch.
- `NestworldRegionSystem.deferredWireUpdates` + `NestworldRedstone`'s
  `NetworkOutOfBounds` handling — already implements a "Deferred" tier: a
  wire update whose network reaches outside the calling region's bounds is
  queued (`deferWireUpdate(pos)`) and applied on main at the start of the
  NEXT tick (`tickAllRegions()` step 2). **Correction (2026-08-09):**
  earlier drafts of this document (and the Gemini consultation transcript)
  cited `BoundarySignalQueue` as this working precedent — that was wrong.
  `BoundarySignalQueue.enqueue()` is never called by ANY code in the
  repository (confirmed by grepping both NestworldCore's own sources and
  every vanilla patch) — it is dead code, exactly like
  `CrossRegionCapabilityBus`, not a shipped mechanism. The actually-wired
  "Deferred" precedent is `deferredWireUpdates`, a plain `Queue<BlockPos>`
  in `NestworldRegionSystem` itself, unrelated to the `BoundarySignalQueue`
  class. Found while starting Stage 2's `BoundarySignalQueue` conversion —
  see the Stage 2 section below for what was actually converted instead.
- `RegionSplitManager.onTick()` — already switched from tick-counted to
  wall-clock (`nanoTime`) gating this same development cycle; a working
  precedent that "anchor to real time, done carefully and scoped" succeeds
  in this codebase.

## Consistency tiers for cross-region messages

- **Tier 0** — immutable/lock-free direct read. `BlockState` via
  `PalettedContainer`'s volatile-snapshot design; needs no mailbox.
- **Tier 1** — snapshot. `BlockEntity` via the Stage-1 Region Mailbox: NBT
  snapshot published tick N, foreign region reads it tick N+1.
- **Tier 2** — deferred mutation via message (redstone signal, entity
  transfer, block mutation) — applied at a controlled point, not
  immediately.
- **Tier 3** — synchronous rendezvous, reserved for cases that genuinely
  need a synchronous answer; must be individually profiled, never assumed
  safe by default. (`RegionChunkView`'s deep-lock-beyond-ghost-depth
  fallback is a live example, already telemetried — see below.)

Every message should carry `sourceRegion` / `destinationRegion` /
`logicalTick` / `epoch` / `messageType` / `payload`, even while the server
still runs one shared global tick, so a future Stage 5 local-time migration
doesn't require reworking the whole protocol.

## Staging

1. **Stage 1** (in progress toward production quality): Region Mailbox /
   BlockEntity snapshot / `BoundaryManager` / `RegionChunkView`. Core
   mechanism shipped (`45b06502f`); hardening pass 2026-08-09 below.
2. **Stage 2** (entity ownership transfer done 2026-08-09): convert to
   mailbox — cross-region BE access (done, Stage 1), entity ownership
   transfer (done — see below), boundary signals (partially done via
   `BoundarySignalQueue`), remaining unsafe foreign reads (not yet).
3. **Stage 3**: audit and classify every remaining direct cross-region
   mutable access (`getChunk`/`getBlockEntity`/entity refs/Level access/
   capability access/POI/scheduled ticks) into
   SAFE/SNAPSHOT/MAILBOX/SYNC_REQUIRED/UNSAFE.
4. **Stage 4**: remove `awaitLatch()`'s wait-for-slowest dependency via
   mailbox-based communication, without introducing local clock drift.
5. **Stage 5** (future, separate project): true independent per-region
   local tick, only after 1-4 are stability-proven in production.

### Success criteria

- **Correctness**: no races, no lost/duplicated messages or entities, no
  corrupted BlockEntities, POI consistency holds, scheduled ticks stay
  correct, worldgen differential test passes.
- **Performance**: one region under extreme load must not force unrelated
  regions to wait unless there's a genuine dependency between them.
- **Compatibility**: large modpacks (400+ mods) run without mass
  `ConcurrentModificationException`/`IllegalStateException`/race
  corruption/deadlock/timeout. Test matrix: normal load, one heavy region,
  entity flood, cross-region flood, split/merge under load, real modpack
  worst case (ATM9).

## Stage 1 hardening — 2026-08-09 audit

Reviewed `BoundaryManager.syncGhostZones()` / `ChunkSnapshot` /
`WorldRegion`'s ghost-snapshot fields / `BlockEntity.loadStatic` for
production-readiness:

- **Safe publication**: `WorldRegion.nestworldGhostBeSnapshot` is an
  `AtomicReference<Map<...>>`, swapped as a whole map each publish — no
  reader ever sees a partially-populated snapshot. Confirmed correct.
- **No stale-entry leak on chunk unload/region merge**: the per-region
  snapshot map is rebuilt from scratch every tick from currently-loaded
  chunks only; a region with no ghost-zone chunks left gets an explicitly
  empty map published (`perRegion.getOrDefault(region, Map.of())`), not a
  carried-over stale one.
- **`BlockEntity.loadStatic()` is safe for arbitrary (including modded) BE
  types**: factory construction and `.load()` are both individually
  exception-guarded, returning `null` on failure rather than throwing;
  Forge capabilities round-trip through NBT (`ForgeCaps`) same as any other
  field, so a reconstructed detached copy's capability providers are valid.
- **Fixed**: when `saveWithFullMetadata()` itself throws (a misbehaving
  mod's serialization), the position was previously dropped from the new
  snapshot entirely, making `ChunkSnapshot.getBlockEntity()` return `null`
  — indistinguishable from "no BE here" to a caller, even though a BE
  physically exists. Now falls back to carrying the position's
  last-successfully-published tag forward instead of dropping it, so a
  transient serialization failure degrades to one extra tick of staleness
  (matching the ghost-zone contract's spirit) rather than a false "empty"
  read. See `BoundaryManager.java`'s `syncGhostZones()`.
- **Known, accepted, already-telemetried gap** (not a Stage-1 defect, a
  Tier-3 case per the taxonomy above): reads beyond `GHOST_DEPTH` (2 chunks)
  fall through to `RegionChunkView`'s deep-lock tier, which degrades to an
  unsynchronized best-effort read on lock timeout/interrupt. Already
  tracked via `RegionChunkView`'s `deepLockTimedOut` counter with an active
  warning above 10% of cross-region reads in a window. Left as Stage 3's
  job (classify + decide SNAPSHOT vs SYNC_REQUIRED per access pattern), not
  widened blindly — snapshotting every loaded chunk's BEs every tick
  (removing the `GHOST_DEPTH` filter) would multiply
  `saveWithFullMetadata()` calls by the full loaded-chunk count, an
  unmeasured and likely unacceptable cost on a real modpack; needs its own
  benchmark before being considered.

## Stage 2 — entity ownership transfer converted to a message — 2026-08-09

Added `RegionMessage` (`net.nestworld.region.RegionMessage`), a record
carrying `sourceRegion`/`destinationRegion`/`logicalTick`/`epoch`/
`messageType`/`payload`/`createdNanos` per the Tier-2 shape above, plus a
per-`WorldRegion` inbound mailbox (`nestworldPostMessage`/
`nestworldDrainMailbox`, backed by a `ConcurrentLinkedQueue`, safe to post
from any thread).

`BoundaryEntityTransfer.processEntity()` previously called
`correctRegion.addEntity(uuid)` directly, inline, during the per-entity scan.
It now posts a `RegionMessage.entityTransfer(...)` to the destination
region's mailbox instead; `checkAndReassign()` drains and applies every
region's mailbox in a new step at the end of the same method call.

**This is deliberately NOT a timing change** — the drain-and-apply step
still runs synchronously within the same `checkAndReassign()` call, at the
exact barrier-safe point (main thread, after every region thread has
parked) ownership changes have always been applied at, and completes before
`pool.tickAllRegions()` (step 4) starts. Verified no correctness regression:
each entity is processed at most once per `checkAndReassign()` call (full
scan visits each loaded entity once; the event-driven drain dedupes via its
own `seen` set), so there is no re-lookup of an entity's own
just-transferred ownership within the same pass that the deferred apply
could see stale. Only the *shape* of the interaction changed — a tagged
message instead of an ad-hoc direct field mutation — setting up Stage 4
(remove the barrier's wait-for-slowest dependency) and Stage 5 (independent
local time) without requiring this code to be reworked again.

Not yet converted to the mailbox: `BoundarySignalQueue`'s redstone-signal
queue (already message-shaped in spirit — see the precedents section above
— but not yet using the `RegionMessage` record) and any remaining unsafe
foreign reads found in a future Stage 3 audit.

### Validation (gen-spike-repro, 47.4.199, 2026-08-09)

- `NESTWORLD_MAILBOX_TEST=1` self-test (round-trip fidelity, detached-copy
  identity, malformed-id degrade-to-null): **PASS**.
- Live region-border-crossing test: 30 `armor_stand` entities (lootless, for
  an unconfounded count — see `owned-entity-ids-leak-fix` memory for why)
  spawned straddling the region #0/#1 border, teleported across in one bulk
  move, then through 5 rapid back-and-forth crossings. `execute if entity
  @e[type=armor_stand]` confirmed exactly 30/30 both times — no loss, no
  duplication. `/nestworld status` and the per-region tick log
  (`[NestWorld-Region-1] ... (36 owned, 30 ticked)`) confirmed ownership
  and active ticking both moved to the destination region, not just the
  bookkeeping count. Zero `ERROR`/`Exception` lines in the server log
  throughout. Server held 20 TPS the entire test.
- Deployed to ATM9 (419 mods, build 47.4.199): clean boot, `/nestworld
  status` 20 TPS (8 regions, tick cost settled to 8.3ms after warm-up), no
  new crash-reports (33, unchanged from before deploy), all startup
  ERROR/Exception log lines are pre-existing known mod noise (client-only
  mods refusing to load on a dedicated server, coremod transform warnings —
  same baseline as `real-modpack-test-2026-07` memory), none related to
  the Region Mailbox or entity-transfer mailbox changes.

### Wire-update mailbox conversion — validation level (2026-08-09)

`NestworldRegionSystem.deferredWireUpdates` (the real, actually-wired
"Deferred" mechanism — see the precedents correction above) was converted
from `Queue<BlockPos>` to `Queue<RegionMessage<BlockPos>>` the same way
entity transfer was: wrap on post (`RegionMessage.wireUpdate(...)`), unwrap
via `.payload()` on drain, identical trigger condition
(`WireHandler.NetworkOutOfBounds`) and identical apply call
(`overworld.nestworldWireHandler.onWireUpdated(...)`) — a mechanical
wrap/unwrap with no logic change to the AC wire handler, the abort
condition, or the timing.

**Not validated with a dedicated live cross-region redstone circuit** —
attempted one (lever → redstone wire → repeater → wire → lamp, spanning
region #0's interior across the #0/#1 border into region #1) but spent
significant time chasing what turned out to be test-rig-building issues
(a `/setblock`-ordering mistake caused wire to fail to place without a
floor block already present; a working power gradient reached the wire
immediately before a repeater, but the repeater never read it — root cause
not identified, possibly an AC-specific repeater-power-read quirk unrelated
to this change). Abandoned rather than sink further time into a test of the
harness rather than the code.

Confidence instead rests on: (1) this is the same wrap/drain/unwrap pattern
already proven correct end-to-end by the entity-transfer live test above,
(2) zero `ERROR`/`Exception` lines in either server's log across both
redeploys, (3) the change touches nothing AC-internal or timing-related.
**If a real cross-region redstone issue is ever observed on ATM9 after this
deploy, re-attempt the live circuit test — starting from a floor placed
FIRST, entirely `/fill`-built in one pass before any redstone component —
rather than assuming this validation gap is the cause without checking.**

Deployed to ATM9 (same 47.4.199 build, rebuilt content): clean boot, zero
`ERROR`/`Exception` lines related to `RegionMessage`/wire/mailbox code (89
startup ERROR/WARN lines total, all pre-existing known mod-recipe/loot-table
registry noise, e.g. KubeJS Thermal Series recipes, MaterialStatsManager,
LootModifierManager). `/nestworld status` briefly read 0.9 TPS 15s after
boot with high per-region `deferred` counts — this is the same post-restart
chunk-load warm-up transient seen on every prior redeploy this session (the
first Stage 1/2 deploy went 31.5ms → 8.3ms over ~20s the same way), not a
regression: `deferred` cleared and TPS settled to 20.0 (8.1ms/tick) within
30s, with no correlating error.

## Stage 3 audit — cross-region access classification (2026-08-09)

Scope, per an explicit decision: NestworldCore's own sources + already
`NestWorld:`-patched vanilla files (29 total, enumerated via `grep -rl
"NestWorld"`), not a fresh scan of unpatched vanilla — that full-vanilla
scan was already done once for the Local Tick research pass above and
isn't repeated here.

### HEADLINE FINDING (new, not previously documented): entity-tick-triggered
### direct block writes are NOT border-band-protected — a real, reachable
### cross-region write race, distinct from and larger than every previously
### known gap in this document.

Border-band routing (`NestworldRegionSystem.interiorRegionFor`/
`routeScheduledTick`) protects exactly three trigger sources: scheduled
block/fluid ticks, block events, and block-entity ticks — anything reaching
a chunk within `BORDER_BAND_CHUNKS` (2) of a region edge runs on main
instead of a region thread, specifically so its cascade (piston push,
comparator update, neighbour notify) cannot race a neighbouring region's
own thread.

**It does NOT cover block writes triggered directly from entity AI/tick
code**, which runs on whichever region thread owns the acting entity —
with no equivalent position check at all. Confirmed structurally, not
inferred:

- `Level.explode(...)` → `Explosion.finalizeExplosion(...)`
  (`Explosion.java:317`, called at `Level.java:567`) runs synchronously on
  the calling thread — i.e. the exploding entity's (TNT/creeper/bed/etc.)
  owning region thread — and writes blocks via `setBlock`/`removeBlock`
  with zero border-position check. A blast radius (up to several blocks)
  that reaches into a neighbouring region's territory races that region's
  own thread directly: a genuine concurrent write-write hazard on
  `PalettedContainer`, which is only declared safe for concurrent READS
  (single-writer volatile-snapshot design — see the class's own
  `nestworldContainerLock` comment), not concurrent writers.
- Same shape, same root cause, confirmed via the earlier fork research:
  `EnderDragon.java:454` (`level.removeBlock`), `WitherBoss.java:280-297`
  (`level.destroyBlock`, an area attack), `RemoveBlockGoal.java:92`,
  `BreakDoorGoal.java:51,66,71` — all direct `Level` block-mutation calls
  from entity AI/tick methods, none border-position-checked.
- `PistonBaseBlock.java` has **zero** `NestWorld:` patches — its
  `neighborChanged` → `moveBlocks` trigger path is only SAFE today because
  its usual trigger (a redstone/comparator update) is itself
  border-band-routed. But `neighborChanged` fires as a synchronous side
  effect of ANY adjacent block write, including the unprotected
  entity-triggered ones above — so a piston move can inherit the same
  unprotected-write race if it cascades from an explosion or mob block
  destruction near a border, even though the SAME piston move would be
  safe if triggered the normal (scheduled-tick) way.

**Why this wasn't caught earlier**: every previous session in this project
that stress-tested explosions/TNT (the whole `explosion-nav-race`,
`point-hotspot` line of work) was validated against ENTITY-TRACKING and
ENTITY-COUNT races (ChunkMap.entityMap, nav updates) — real, fixed bugs —
but not specifically against a blast radius straddling a REGION BORDER
while the neighbouring region is independently, concurrently ticking. The
existing `MIN_SPLIT_SEPARATION_SHARE`/border-band split-veto scoring in
`RegionSplitManager` optimizes cut placement around block-tick heat and
entity count, not around "could this entity's action radius reach past the
line" — so nothing in the split logic prevents a hot TNT cluster (like this
session's own sand-duper-adjacent falling-block incident) from ending up
near a border.

**Not fixed in this pass** — this needs a real design decision, not a
quick patch: extending border-band-style main-thread routing to
entity-triggered writes would need either (a) checking every entity's
action radius against border proximity before letting it write directly
(expensive per-check, and radius varies wildly by entity/effect type), or
(b) detecting an out-of-bounds write attempt at the `Level.setBlock`
call site and deferring it (same shape as `NestworldRedstone`'s
`NetworkOutOfBounds` handling, but for raw block writes — no existing
mechanism does this today). Flagging as the top-priority open item from
this audit; recommend design discussion before implementation, not an
inline fix.

### Full classification table

| Access point | Mechanism | Tier | Status |
|---|---|---|---|
| `Level.getBlockState()` | direct, `PalettedContainer` lock-free volatile-snapshot | 0 SAFE | done (vanilla's own design) |
| `Level.getBlockEntity()`, ghost zone (≤2 chunks of border) | `RegionChunkView` → `BoundaryManager` NBT snapshot | 1 SNAPSHOT | done (Stage 1) |
| `Level.getBlockEntity()`, beyond ghost zone | `RegionChunkView` deep-lock, timeout→stale fallback | 3 SYNC_REQUIRED | accepted, telemetried (`deepLockTimedOut`) |
| Entity ownership transfer | `BoundaryEntityTransfer` → `RegionMessage` mailbox | 2 MAILBOX | done (Stage 2, this session) |
| Redstone wire out-of-bounds | `NestworldRedstone` → `RegionMessage` mailbox (`deferredWireUpdates`) | 2 MAILBOX | done (Stage 2, this session) |
| `CrossRegionCapabilityBus` | designed, never wired to Forge's capability resolution | — | dead code, N/A |
| `BoundarySignalQueue` | designed, `enqueue()` never called | — | dead code, N/A |
| Explosion block writes (`finalizeExplosion`) | `RegionMessage<NestworldExplosionBatch>` mailbox | 2 MAILBOX | **done (2026-08-09) — see below** |
| Mob-AI direct block writes (dragon/wither/goals) | none | — | **UNSAFE — same root cause, NOT covered by the Explosion fix** |
| Piston `neighborChanged` cascades | none (relies on trigger-source protection) | — | UNSAFE when cascading from mob-AI writes; safe from Explosion now, and otherwise |
| Foreign-entity field reads (villager gossip, etc.) | none | — | UNSAFE, narrow (border-proximity required); see fork finding this session |
| `ChunkAccess`/`ProtoChunk` cross-chunk worldgen writes | `synchronized` for 3/7 fields | mixed | see open finding below (#12, `THREAD_SAFETY_PASSES.md`) |

## Stage 3 implementation — Explosion cross-region write isolation (2026-08-09)

Closed the headline finding for vanilla's `Explosion` class (TNT, creeper, bed/respawn-anchor,
and anything else that goes through `Level.explode()`). Does **not** cover mob-AI direct block
writes (`EnderDragon`/`WitherBoss`/goals) or mod code with its own block-destruction logic that
bypasses vanilla `Explosion` — see the Draconic Evolution finding below.

**Mechanism**: `Explosion.finalizeExplosion()` now classifies `toBlow` by owning region (only
when called from a `RegionThread` for the sharded overworld — a main-thread-triggered explosion
is unchanged, already safe). Positions owned by the triggering region are processed inline
exactly as before (`nestworldDestroyBlocks`/`nestworldSpreadFire`, extracted but byte-identical
loop bodies). Positions owned by a DIFFERENT region are batched per destination region into a
`NestworldExplosionBatch` (the `Explosion` instance + its foreign position subset + whether loot
drops apply) and posted via `RegionMessage.explosionApply(...)` — Tier 2, the same
mailbox-batch-per-destination shape the user's design doc specified, avoiding a message-storm
(one message per FOREIGN REGION, not per block).

**Applied**: `NestworldRegionSystem.tickAllRegions()` drains and applies every region's
`EXPLOSION_APPLY` messages in a new step 4b, right after the region-tick round (`pool
.tickAllRegions()`, step 4) finishes and before ghost-zone sync (step 5) — so freshly-destroyed
blocks are reflected in the SAME tick's ghost-zone snapshot, not lagging an extra tick. This
deviates from the user's sketch (which suggested applying on the DESTINATION region's own
thread) — applying on MAIN thread at this barrier-safe point was chosen instead for consistency
with the already-proven Tier-2 pattern (entity transfer, wire updates) and to avoid a new "when
does a region check its own inbox mid-tick" design question.

**Correctness fix found while implementing**: the mailbox was previously "drain everything,
filter by type" at both existing call sites (entity-transfer drain, and the new explosion drain)
— which would SILENTLY DROP a message of the type the OTHER site handles if it happened to still
be queued when the wrong drain ran. Fixed by making `WorldRegion.nestworldDrainMailbox` take a
`RegionMessage.Type` and only remove matching messages, leaving others for their own drain site.

**Validation**: gen-spike-repro — self-test PASS; live test: a TNT cluster straddling the
region #0/#1 border (with active zombies ticking on BOTH sides to force genuine concurrent
region-thread activity) destroyed a symmetric 9-block-wide floor section cleanly across the
border (x=-69 to -61, border at -65/-64), zero exceptions, entities on both sides took damage.
Deployed to ATM9 (419 mods): clean boot, 20 TPS, no new crash-reports, no
Explosion/RegionMessage-related errors.

**New finding while investigating a live user test**: Draconic Evolution's reactor meltdown
(`com.brandon3055.draconicevolution.lib.ExplosionHelper`/`ProcessExplosion`) does **not** use
vanilla `Explosion` at all — confirmed via bytecode disassembly, it calls `ServerLevel
.removeBlock()` directly (SRG `m_7471_`), the same unprotected pattern as `EnderDragon`/
`WitherBoss`. It also appears to be a multi-tick incremental process (`RemovalProcess implements
IProcess`), not a single-tick event — a longer window for the same race if triggered near a
border. **Not covered by this fix.** A live user-triggered reactor meltdown during this session
showed a temporary TPS drop to 3.8, diagnosed via `/nestworld lag` as MAIN-THREAD cost (not a
region), zero exceptions — this specific instance did not exercise the cross-region race (the
blast was not near a border), so it neither confirms nor disproves the risk; the mechanism gap
remains real and undemonstrated live. If Stage 3 continues, extending the same
classify-and-batch approach to `Level.removeBlock`/`setBlock` callers generically (not just
`Explosion`) is the natural next scope — see the "entity-triggered block-write race" memory.

## NW-CHUNK-FASTPATH — main-thread getChunk() fast path (2026-08-09)

Independent of Stage 4/Local Tick, but found while investigating a live user report
(Draconic Evolution reactor meltdown TPS collapse — see the "entity-triggered
block-write race" investigation above, which ruled OUT a cross-region cause and
traced the real cost to `ServerChunkCache.getChunk()`'s blocking path via a live
spark profile).

**Root cause (confirmed via spark, not guessed)**: `ExplosionHelper.RemovalProcess
.updateBlocks()` (Draconic Evolution / BrandonsCore, third-party mod code, not
patchable via genPatches) calls `Level.getChunk()` once per affected block position
via `LiquidBlock.neighborChanged()` → `FluidInteractionRegistry.canInteract()` →
`Level.getFluidState()`. Vanilla's own 4-slot `lastChunk`/`lastChunkPos`/
`lastChunkStatus` cache in `ServerChunkCache.getChunk()` is thrashed almost
immediately by this access pattern (`blocksToUpdate` is a `HashSet<Long>`, iterated
in hash order, spanning dozens of chunks with no spatial locality between
consecutive calls) — nearly every call falls through to the blocking
`managedBlock()`/`getChunkFutureMainThread()` path even though the chunk is already
fully loaded and ticking.

**Fix**: added a cheap fast path in `ServerChunkCache.getChunk()`'s main-thread
branch, checked before the existing 4-slot cache: for `ChunkStatus.FULL` + `load=
true` calls (the same scoping already used by `GETCHUNK_WAIT_TIMEOUT_MS`), look up
`nestworldLoadedFull` (the SAME lock-free, once-per-tick-refreshed snapshot region
threads already use via `nestworldGetLoadedChunk` — reused, not duplicated) — a hit
returns immediately with zero blocking machinery touched; a miss falls through to
the completely unchanged vanilla path. No memory-visibility risk: unlike the
region-thread consumer (cross-thread), this is the MAIN thread reading a volatile
field it (via `nestworldRefreshLoadedChunks()`, literally the first line of
`NestworldRegionSystem.tickAllRegions()`) just published on the same thread earlier
this same tick.

**Explicitly NOT done** (would have violated the design's own safety constraints):
no change to the blocking contract for not-yet-ready chunks; no global "make
getChunk non-blocking"; no mod-specific hack (the fast path helps ANY main-thread
code calling `getChunk(..., FULL, true)` repeatedly, not just Draconic Evolution).

**Instrumentation**: `ServerChunkCache` now tracks `nestworldChunkLookups`/
`nestworldFastPathHits`/`nestworldBlockingCalls`/`nestworldWaitNanos`/
`nestworldProxyCreations` (cumulative `AtomicLong`s, always on — negligible cost),
reported via the new `/nestworld chunkstats` command.

### Validation (gen-spike-repro, then ATM9, 2026-08-09)

- **Test A** (single already-loaded chunk, repeated): 2/2 calls fast-path, 0
  blocking. Pass.
- **Test B** (30 repeated lookups of the same loaded chunk): +30 fast-path hits,
  +0 blocking, wait time unchanged. Pass.
- **Test C** (20 different already-loaded-or-not chunks — the exact pattern that
  defeats the vanilla 4-slot cache): 14/22 fast-path, 8 correctly fell through
  (those chunks genuinely weren't FULL yet) — proves the fast path discriminates
  correctly rather than blindly hitting. Pass.
- **Test D** (a genuinely never-generated chunk far from spawn): took 1833ms,
  identical to pre-fix blocking behavior — the blocking contract for not-ready
  chunks is provably unchanged. Pass.
- **Test E** (unload/reload staleness): not live-tested (hard to reliably trigger
  via RCON in a short window) — covered by structural argument instead: the same
  1-tick-stale-at-worst bound already accepted for the region-thread consumer of
  `nestworldLoadedFull` applies identically here; a stale hit after an unload
  returns a detached-but-valid `LevelChunk` object (last known state), never a
  crash or type-mismatch — same accepted risk class as existing ghost-zone
  snapshots, not a new hazard.
- Zero exceptions across all tests, gen-spike-repro held 20 TPS throughout.
- Deployed to ATM9 (419 mods): clean boot, 20 TPS, 33 crash-reports (unchanged),
  89 startup ERROR/WARN lines (unchanged known noise). Baseline `/nestworld
  chunkstats` after normal boot+gameplay (no reactor test, no other unusual load):
  **18,119 calls, 100.0% fast-path hits, 1 blocking call**. A later passive check
  under heavier concurrent activity (10 active regions, likely another player or
  mod-driven load) showed **5,978,233 calls, 62.4% fast-path, 789,756 blocking —
  but only 39ms total cumulative wait time** across all those blocking calls
  (~0.00005ms average), meaning even the "miss" path is resolving almost
  instantly under this load, not truly parking.
- **Not yet measured**: a direct before/after reactor-meltdown A/B comparison
  (the original motivating case) — the user was unable to trigger it live during
  this session; `/nestworld chunkstats`'s cumulative counters are ready to capture
  it whenever the test is next run (read the counters immediately before and
  after triggering the meltdown to isolate that window's contribution).

### Live before/after A/B, finally captured (2026-08-09, later same session)

Two live reactor meltdowns were triggered with `/nestworld chunkstats` read
immediately before and after each:

- Meltdown 1: 94,783,725 → 140,759,378 calls (+46M), fast-path 48.0% → 51.9%,
  blocking +3,771,454 calls contributing +86,468ms cumulative wait. Server
  recovered fully to 20 TPS after ~85s of degraded TPS. No crash.
- Meltdown 2 (bigger): continued to 321,132,107 calls, fast-path 46.5%,
  blocking total 30,673,417, wait 182,980ms cumulative. **This one crashed the
  server** — see the new "RegionThread getChunk blocking crash" finding below.
  A tick-filtered spark profile (`--only-ticks-over 500`) of this same event
  showed the fast path is NOT the dominant cost here: `DistanceManager
  .runAllUpdates()` averaged ~228ms/tick and `ProcessExplosion.trace()` (the
  reactor's blast-shape raycasting) spent ~51.58ms/tick blocked in
  `getChunk()` — both because the reactor's raycast reached UNGENERATED
  terrain, triggering real chunk generation, which no caching mechanism can
  shortcut. NW-CHUNK-FASTPATH only helps for ALREADY-loaded chunks; this
  event's dominant cost was genuine synchronous chunk generation on the main
  thread during `Phase.START`.

### New finding, then fixed same session: RegionThread getChunk() blocking crashed the server

During meltdown 2's tail, a real crash occurred — see the dedicated
`regionthread-getchunk-blocking-crash` memory for full detail. Summary: a
`RegionThread` (region #48) got stuck for 60s+ inside `ServerChunkCache
.getChunk()`'s `CompletableFuture.supplyAsync(...).join()` fallback path
(used when a chunk is neither in `nestworldLoadedFull` nor read via
`NONBLOCKING_CHUNK_READS`), confirmed via the crash-report's thread dump
(every OTHER region thread was cleanly `WAITING on java.lang.Object`; only
this one was `WAITING on CompletableFuture$Signaller`). The main thread it
depended on was itself saturated by the same `DistanceManager`/chunk-gen
load above, so the round-trip outlived both the 30s region-round
abandon-wait and the 60s `ServerWatchdog` threshold. Checked
`user_jvm_args.txt`: `chunkGenAdmitBudget=4` and `chunkGenMaxConcurrent=4`
were ALREADY active on ATM9 at crash time — the existing chunk-gen pacing
did NOT prevent this, ruling out "just enable existing pacing" as the fix.

**Fixed**: `ServerChunkCache.getChunk()`'s RegionThread branch now bounds
the wait with `GETCHUNK_WAIT_TIMEOUT_MS` (`future.get(timeout, ...)`
instead of a plain `.join()`), falling back to the region's own empty
chunk on `TimeoutException` — the same fallback `NONBLOCKING_CHUNK_READS`
already uses, just scoped to "only when genuinely stuck" instead of every
miss. New `nestworldRegionGetChunkTimeouts` counter, reported via
`/nestworld chunkstats`. The dispatched main-thread task is not
cancelled — it keeps running, its result is just discarded by this
caller. Verified no behavioral change to the common (fast-resolving) path
via gen-spike-repro's self-test and chunkstats. Deploying to reproduce
the exact timeout branch live (spawning entities in far, ungenerated,
forceloaded territory) was attempted several times via RCON and did not
succeed within reasonable effort — itself consistent with this being a
genuinely rare edge case. Deployed to ATM9 on code-review confidence
(same pattern as the already-proven main-thread `GETCHUNK_WAIT_TIMEOUT_MS`
mechanism); the new counter will confirm real engagement if a similar
extreme event recurs.

## Anatomy of a tick — complete trace (2026-08-10)

Full, line-referenced trace of exactly what happens for one server tick,
read from `MinecraftServer` down through every phase, done as a
foundational research pass before further Stage 4/5 work. Nothing here is
new behaviour — this is documentation of what the code, read in full,
already does.

### 0. Entry: `MinecraftServer.tickChildren()`

A plain sequential `for` loop over `getWorldArray()` (overworld, nether,
end — dimension order is whatever that array returns, NOT concurrent):

```java
for (ServerLevel serverlevel : this.getWorldArray()) {
   ...
   if (NestworldRegionSystem.isInitialised() && serverlevel.dimension() == Level.OVERWORLD) {
      NestworldRegionSystem.get().tickAllRegions(p_129954_);
   } else {
      serverlevel.tick(p_129954_);   // vanilla path — nether, end
   }
}
```

Only the **overworld** is routed through the region system; nether and end
always take the plain vanilla `ServerLevel.tick()` path, single-threaded,
exactly as upstream Forge. Because this loop is sequential on the single
main thread, nether/end never tick concurrently with the overworld's region
threads — the overworld's ENTIRE tick (including its barrier wait) fully
completes before the loop moves on to the next dimension. This is also
today's de facto **inter-dimension sync point**: nothing dimension-specific
about Stage 5 (independent local time) can land without addressing this loop
too.

### 1. `NestworldRegionSystem.tickAllRegions(hasTime)` — the coordinator

Seven main-thread phases, timed individually (`phaseNanos[0..5]`, logged
every 200 ticks as "Tick phases avg ms"):

| # | Phase | What runs | Cost class |
|---|---|---|---|
| 0 | `nestworldRefreshLoadedChunks()` + `overworld.tick(hasTime)` | vanilla global tick — see breakdown below | "vanilla" |
| 1 | Flush `deferredWireUpdates` (redstone) | Tier-2 mailbox drain | "signals" |
| 2 | `entityTransfer.checkAndReassign()`, player ticking, pinned-entity ticking, `predictiveGen.tick()` | main-thread-only entity work | "entityXfer" |
| 3 | **`pool.tickAllRegions()`** | region-thread barrier — see §2 | "regionPool" |
| 3b | Drain `EXPLOSION_APPLY` + `BLOCK_WRITE` mailboxes per region | Stage 3 cross-region write application | (folded into regionPool's tail) |
| 4 | `boundaryManager.syncGhostZones()`, `drainDeferredRemovals()`, `drainDeferredTrackingAdds()`, `drainDeferredSpawns()`, `overworld.getChunkSource().chunkMap.tick()` | ghost-zone snapshot refresh + entity-tracking maintenance — ALL require every region thread parked | "ghostZones" |
| 5 | `splitManager.onTick()` | adaptive split/merge evaluation | "splitMerge" |
| 6 | `renderBorderParticles()` | optional debug visualisation | (untimed) |

Phase 4 is the phase most tightly coupled to the "all regions parked
simultaneously" safety invariant (see the Stage 4 decision's structural
blockers) — every operation in it either reads region-owned state that must
be stable, or mutates `ChunkMap`'s non-thread-safe `entityMap`/`visibleChunkMap`,
which region threads must never touch concurrently.

### 2. Sub-phases inside `overworld.tick(hasTime)` ("vanilla", phase 0)

Traced and instrumented this session (`ServerLevel.java`'s
`NW-VANILLA-SUBPHASE` counters, logged as "vanilla sub-phases avg ms"):

1. **preChunkSource**: world border, weather, sleep-skip check, `tickTime()`,
   scheduled-tick DISPATCH (see §3 — the actual ticks run on region threads,
   this bucket is just the vanilla `blockTicks`/`fluidTicks` collection call
   plus `raids.tick()`).
2. **chunkSource**: `ServerChunkCache.tick()` — itself broken into three
   further sub-phases (`NW-CHUNKSOURCE-SUBPHASE`, logged as "chunkSource
   sub-phases"):
   - `distanceManager`: `purgeStaleTickets()` + `DistanceManager
     .runAllUpdates()` — ticket-level propagation and the tiered chunk-gen
     promotion budget (`nestworldHasPlayerTicket()` classification loop over
     `chunksToUpdateFutures`, see the DistanceManager root-cause
     investigation above). Confirmed LIVE (2026-08-09) to climb from ~3.5ms
     to ~14ms proportionally with backlog size under a real reactor-blast
     event — the single most-instrumented sub-phase this project has, and
     still the best-supported candidate for "what's expensive when chunk-gen
     demand spikes."
   - `tickChunks`: natural-spawn counting, random-tick dispatch (interior
     chunks bucketed to region threads via `queueRandomTicksFor` — see §3;
     border/unowned chunks tick inline here), `customSpawners`.
   - `unload`: `chunkMap.tick(hasTime)` — the CHUNK (not entity) unload
     queue. Unrelated to the entity-tracker `chunkMap.tick()` no-arg overload
     called later in phase 4 — same class, two different methods.
3. **blockEvents**: `runBlockEventsPhase()` — see §3.
4. **entitiesAndBE**: the vanilla entity-tick loop is SKIPPED entirely for
   the overworld (patched out — region threads own it, see §4) — this
   bucket is only non-overworld-dimension entities (never reached when
   `dimension() == OVERWORLD`, since this whole call only happens for
   overworld) plus `tickBlockEntities()`'s dispatch into `runBlockEntityPhase`
   (see §3).
5. **entityMgmt**: `entityManager.tick()` — vanilla chunk-based entity
   storage bookkeeping (persistent/transient section housekeeping),
   untouched by NestWorld.

Live data point (2026-08-09, moderate load): `chunkSource` (0.7-16ms)
dominates `vanilla`'s total under load; `entitiesAndBE`/`entityMgmt` stay
consistently under 3ms even during a real player session. Confirms
`DistanceManager`+chunk-gen is the correct target for any further
"vanilla"-phase optimisation work, not entity/BE dispatch overhead.

### 3. The "border-band routing" pattern — shared by 4 independent phases

Scheduled block/fluid ticks (`runScheduledTicksPhase`), random ticks
(`queueRandomTicksFor`/`flushRandomTicksPhase`), block entities
(`runBlockEntityPhase`), and block events (`runBlockEventsPhase`) all
follow the IDENTICAL routing rule, implemented independently in each
(`routeScheduledTick`/`interiorRegionFor`/`queueRandomTicksFor`'s own inline
check): a position strictly inside a region's interior (outside
`BORDER_BAND_CHUNKS`, default 2 chunks / 32 blocks) is bucketed to that
region's thread and runs during the SAME `pool.runWorkRound()` this phase
triggers; a position in the border band, or not owned by any region, runs
inline on the MAIN thread instead, before region threads are ever invoked
for it. Rationale (stated once, applies to all four): vanilla update
cascades (pistons, comparators, hoppers reading neighbour-chunk inventories,
redstone) can reach a few blocks past their origin, and a cascade can't
travel the full 32-block band in one update — so anything within the band
is deliberately kept off region threads to avoid a same-tick cross-border
race, at the cost of that work not being parallelised.

**Two escape hatches from this conservative default**, both opt-in and
operator-asserted:
- **Pinned types** (entity or block-entity) always run on main regardless of
  position — mod-compat safety valve.
- **Cascade-safe block-entity types** (asserted to never write a
  neighbouring block or trigger redstone/pistons) skip the border-band inset
  check entirely and go straight to their TRUE owning region even inside
  the band — see [[border-band-cascade-safe-fix]].

Block-tick **heat** (`BlockTickHeat`) is recorded for split-scoring in both
cases, but only the band-protected (non-pinned, non-cascade-safe) load
counts toward the split VETO — a hot pinned/cascade-safe area should not by
itself block a split, since it doesn't create the race a split-avoidance
veto exists to prevent.

### 4. `RegionThreadPool.tickAllRegions()` / `awaitLatch()` — the barrier

Already the subject of the Stage 4 Gemini consultation above; summarising
the mechanism precisely: a fresh `CountDownLatch` sized to the number of
region threads with actual work this tick (`workers` — entity-less AND
work-less regions are skipped entirely, saving the wakeup/latch round-trip
for deep split trees with many idle leaves). Each worker's `requestTick()`
wakes it; the main thread then busy-waits (`awaitLatch`), parking 50µs at a
time and servicing `ServerChunkCache.pollTask()` between parks so a
RegionThread's own `getChunk()` dispatch to main can resolve without
deadlocking — up to a 30s abandon threshold (logged as an error, tick
proceeds anyway; this is what fired during the RegionThread getChunk
blocking crash, see above). This IS a genuine synchronous barrier — the main
thread does no OTHER useful tick work while waiting, confirmed unavoidable
under the current safety model (§ Gemini consultation above).

### 5. `RegionThread.run()` — per-region tick, and its OWN two independent budgets

Each region thread, once woken, acquires its region's `StampedLock`
write-lock for the whole round (`region.getChunkLock().writeLock()` —
nothing else may read/write this region's owned state until it releases),
clears its 16-slot direct-mapped chunk cache (chunks may have unloaded since
last tick), then runs ONE of two paths depending on what the pool requested:

- **`runWorkBudgeted(work)`** — scheduled/random ticks and block-entity
  batches routed to this region this tick (§3), PLUS any `carriedWork` left
  over from a previous round that hit its own budget. Runs runnables in
  order, checking the deadline every 8th item; whatever doesn't fit is
  carried forward to next tick's work round (or the start of the next entity
  round if no work round arrives first — scheduled ticks precede entities in
  vanilla ordering, and this preserves that even across the carry).
- **`tickEntities()`** — this region's owned-entity round-robin. Two
  layers, both real and measured, not hypothetical:
  1. An UNBUDGETED, unconditional `isRemoved()` cleanup pass over every
     owned UUID first (cheap relative to actually ticking; guarantees dead
     entities are pruned every tick regardless of round-robin position — see
     [[owned-entity-ids-leak-fix]] for why this was added).
  2. The BUDGETED round-robin proper: `REGION_ENTITY_BUDGET_NANOS` (default
     40ms) MINUS whatever this tick's work round + carried work already
     spent, floored at `MIN_ENTITY_SLICE_NANOS` (10ms) so entities always
     make some progress even under a saturated work backlog. Checked every
     4 entities (tightened from 16 specifically because a single dense-pack
     collision tick can cost ~2ms, and a 16-wide check window let the budget
     overshoot by tens of ms under a deliberate mob crush). Entities past the
     cutoff are deferred — `entityCursor` remembers where to resume next
     tick, so every entity eventually ticks, just possibly a tick later
     under sustained overload. If `REGIONALIZED_TRACKER` is on, each ticked
     entity's tracker/broadcast step (`ChunkMap.nestworldTrackOwned()`,
     i.e. `serverEntity.sendChanges()`) runs HERE, inside this same budgeted
     loop — see the Stage 4 section above for why enabling this was the
     concrete fix for the "sand duper" incident's second, previously
     unbounded half.

Both budgets share the region's `workRoundNanos` accounting so a heavy work
round correctly eats into the SAME tick's entity slice rather than the two
costs being invisible to each other — the two phases are "halves of the
same game tick" for accounting purposes (comment verbatim from the code).

On any uncaught exception: `handleCrash()` emergency-saves the region's
chunks, marks the thread stopped; `RegionThreadPool.respawnCrashedThreads()`
(called at the top of every `tickAllRegions()`) detects the dead thread next
tick and respawns with exponential backoff, permanently disabling a region
that crashes more than `MAX_CRASHES` (default 5) times in a 5-minute window
rather than crash-looping forever.

### 6. Cross-region communication — the four-tier model, concretely

Every mechanism found in this trace maps onto exactly one of the tiers from
the Stage 4 decision above:

- **Tier 0 (safe, lock-free, any thread)**: `Level.getBlockState()` — used
  BY the Stage 3 block-write guard itself to compute a synchronous return
  value without needing to touch Tier 2's actual mutation.
- **Tier 1 (snapshot)**: ghost-zone `BlockEntity` NBT snapshots
  (`BoundaryManager`), refreshed once/tick, read by neighbouring regions
  up to `GHOST_DEPTH` (2 chunks) with the round staying one tick stale.
- **Tier 2 (deferred mutation via message)**: `RegionMessage` mailbox —
  `ENTITY_TRANSFER`, `WIRE_UPDATE`, `EXPLOSION_APPLY`, `BLOCK_WRITE`. All
  four are POSTED from a region thread mid-tick and APPLIED on main during
  a barrier-safe window (phase 3b/4 above) — never applied cross-thread
  directly.
- **Tier 3 (synchronous rendezvous, rare)**: `RegionChunkView`'s deep-lock
  fallback beyond `GHOST_DEPTH`, and the border-band routing itself (§3) —
  which is really "avoid the need for Tier 3 by keeping cascade-reachable
  work off region threads in the first place," the cheapest possible form
  of synchronous safety.

### 7. A live example of a subtlety this architecture creates: the tracker skip-check counter

Found while checking on this session's `REGIONALIZED_TRACKER` deployment
(see task #32): `ChunkMap.java`'s `nestworldSkipDiagTickCounter`/`Total`/
`Skipped` are `static` fields, but `ChunkMap.tick()` is called once per
DIMENSION per real tick — once for the overworld via
`NestworldRegionSystem.tickAllRegions()`'s phase 4, and independently for
nether/end via their own plain `ServerLevel.tick()` → `ServerChunkCache
.tickChunks()` path (§0 confirms nether/end never go through the region
system at all). All three dimensions' calls therefore increment the SAME
shared static counters, so the "log every 200 ticks" threshold fires far
more often than once per 200 REAL server ticks, and the reported total/
skipped numbers are a cross-dimension aggregate, not a clean per-dimension
picture. Confirmed live on ATM9: log lines every ~250-300ms instead of the
expected ~10s. Not a correctness bug in the tracker mechanism itself (skip
rate and behaviour are unaffected) — purely a diagnostic-accuracy issue,
tracked as a separate deferred task (#32, P3) per explicit user instruction
not to touch tracker logic while fixing it.

## Stage 4 (revised) — Global Tick / Barrier Elimination Research

After a round of user-proposed designs converged (independently, twice this
session) on the SAME rejected "regions at different local tick counts"
model, Stage 4 was explicitly re-scoped to NOT touch `awaitLatch()`'s
semantics yet. The real target, stated precisely: **make `awaitLatch()`
unnecessary for correctness, not just make the main thread stop waiting on
it** — a materially different (and much harder, more honest) goal than
"remove the wait." Sub-stages:

1. **4.1 Scheduler abstraction** — an organisational layer over the
   EXISTING synchronous barrier (ready/running/overloaded/delayed
   classification, worker allocation, priority) — zero semantic change, the
   global tick stays lockstep.
2. **4.2 Global Tick dependency audit** — find every place that assumes
   `tickCount`/`getGameTime()` is one shared instant everywhere. See
   findings below.
3. **4.3 Cross-region mutation audit** — continuation of the Stage 3
   classification table above (TNT/explosions done; mob-AI/mod direct
   writes done via the generic block-write guard; open items:
   `WorldGenRegion`/structure-data race, already tracked as the carried-over
   finding below).
4. **4.4 Protocol replacement** — direct access → snapshot → mailbox →
   deferred mutation → ownership transfer, i.e. continuing what Stage 1-3
   already started, region by region, mechanism by mechanism.
5. **4.5 Barrier elimination prototype** — only after 4.2-4.4 are complete:
   replace `awaitLatch()`'s wait-for-slowest with a commit protocol that
   verifies/accepts already-prepared region results and messages, preserving
   the SAME global semantics rather than allowing regions to diverge. Not
   attempted yet.

### 4.2 findings — Global Tick dependency audit (2026-08-10)

Confirmed dependencies that WOULD break under any future regional drift:

- **`LevelTicks.java`** — pure vanilla, zero NestWorld patches, single
  global `triggerTick`. Already documented above as the primary structural
  blocker.
- **`DistanceManager` chunk tickets** (`Ticket.timedOut`,
  `chunksToUpdateFutures` promotion pipeline) — keyed to the single global
  tick. **`PredictiveChunkGen`** (`net.nestworld.region`) uses the same
  vanilla `TicketType` machinery — not a separate mechanism, inherits this
  finding rather than adding a new one.
- **Autosave** (`MinecraftServer.java`, `tickCount % 6000 == 0` gating
  `saveEverything()`) — **new finding, not previously documented.** A
  single global modulo check on main; under drift this would snapshot
  regions at different points in their own local progression, an
  inconsistent save.
- **Weather / day-night time** (`ServerLevel.advanceWeatherCycle()`,
  `tickTime()`) — **conceptual blocker, not just technical.** These are
  single per-DIMENSION values (one rain-state, one dayTime), not a
  per-region concept at all — a future local-time model must decide how N
  regions with independent local ticks reconcile into the ONE shared value
  every client observes, before it can exist, not as an implementation
  detail to patch afterward.

Confirmed ALREADY SAFE under drift (useful precedent — Mojang's own pattern
in most cases, not NestWorld's doing):

- **Chunk unload** (`ChunkMap.scheduleUnload`) — gated by a `hasTime`
  budget + count + backlog size, zero `tickCount`/`getGameTime` anywhere in
  the path.
- **Portal cooldown**, **item entity lifespan**, **mob despawn timer**
  (`Entity.portalCooldown`, `ItemEntity.age`/`lifespan`, `Mob.noActionTime`)
  — all plain per-entity local counters decremented/incremented in that
  entity's own tick call, never touching global time. Same safe pattern as
  `Entity.tickCount` (already noted above).
- **`RegionSplitManager.onTick()`** — confirmed via direct read: wall-clock
  `System.nanoTime()`-gated, zero tick-count references. Already a working
  precedent that "anchor to real time, done carefully" succeeds in this
  codebase (see [[split-merge-wallclock-gating-fix]]).
- **World border** (`WorldBorder.java`) — size lerp and damage-per-block are
  spatial/self-contained, no tick-count coupling found.

**Net picture**: most entity/lifecycle timers are already local-counter-safe
by construction. The real blockers stay concentrated exactly where already
suspected (`LevelTicks`, ticket expiry) plus two newly-documented ones:
autosave's modulo gate, and the per-dimension weather/time reconciliation
question — the latter being a design problem a drift model must solve
before it can exist, not an implementation detail.

## Stage 4.5 — barrier elimination prototype (2026-08-10)

Consulted Gemini again before writing any code, given the wait-for-slowest
property was already proven mathematically unavoidable. Precise question:
given that fact is fixed, what can "Stage 4.5" still safely deliver?
Answer, evaluated point by point:

- **(a) Overlapping commit-phase work with still-running regions for a
  wall-clock speedup — NOT safe, rejected.** The exact same border-band
  simultaneous-parking proof that blocks removing the wait also blocks
  starting ANY border-adjacent commit work before every region is parked.
- **(b) Unifying the scattered mailbox-drain call sites — checked, turns
  out largely ALREADY DONE.** Re-audited before attempting a refactor:
  `ENTITY_TRANSFER`, `EXPLOSION_APPLY`, and `BLOCK_WRITE` already share ONE
  mechanism (`WorldRegion.nestworldMailbox` / `nestworldPostMessage` /
  type-filtered `nestworldDrainMailbox`), built up across Stage 1-4 this
  session. Only two things remain genuinely separate, and both are separate
  for real reasons, not accidental scatter: `WIRE_UPDATE` (no single
  destination region — vanilla's own propagation determines which
  region(s) an update actually reaches, so it cannot be posted to one
  region's mailbox) and the `deferredSpawns`/`deferredRemovals`/
  `deferredTrackingAdds` queues (these are off-main-to-main `ChunkMap`-safety
  deferrals, not region-to-region messages at all — a different category,
  not an instance of the same pattern). Forcing these into one artificial
  shape for its own sake would risk a real bug for no real gain — not
  pursued. This item is closed as "already achieved" rather than
  "implemented this pass."
- **(c) Investigating the barrier's OWN overhead (the mechanism, not the
  wait-for-slowest property) — legitimate and pursued this session.**

### (c) findings: the "OS-scheduling contention" hypothesis does NOT hold

[[folia-barrier-investigation-initial]] (2026-08-08) left an unconfirmed
hypothesis: that OS thread-scheduling contention, not the dispatch/notify
mechanism, dominates the barrier's cost when active region count exceeds
available CPU cores. Tested directly this session using already-existing
telemetry (`regionPool breakdown`'s `dispatch`/`wait`/`avgWorkers` vs
`/nestworld status`'s per-region `cost`):

- On ATM9's actual dev hardware (20 cores, ~5 active workers — nowhere near
  the oversubscription regime the hypothesis needed): `wait` (10.6-11.0ms)
  tracked the single hottest region's own reported cost (10.2-10.9ms)
  within under 1ms across multiple samples. `dispatch` was negligible
  (~0.012ms).
- The REAL target deployment constraint is 2 CPUs (see
  [[target-deployment-cpu-constraint]]), not 20 — so the dev-hardware result
  alone doesn't settle the question. Simulated the actual constraint:
  launched gen-spike-repro under `taskset -c <2 cpus>`, then used
  `/nestworld split` (pinned, no auto-merge) plus targeted zombie spawns to
  force 4 simultaneously-busy regions — genuine 2x CPU oversubscription,
  the exact regime the hypothesis required. Result: `wait` (3.6-4.6ms)
  still tracked the hottest region's own cost (~4.1-4.4ms) closely.
  `dispatch` DID measurably grow under real oversubscription (~0.012ms →
  ~0.28ms, a real, ~20x increase) — but stayed a small fraction (~5-7%) of
  total wait, not the dominant, unexplained 15-25ms the original hypothesis
  speculated about.

**Conclusion (final wording, user-authored, 2026-08-10):**

> Barrier synchronization overhead is not a dominant performance bottleneck
> under tested CPU oversubscription conditions. Measured barrier wait
> closely tracks the execution time of the slowest region. Dispatch/
> scheduling overhead remains a small fraction (~5-7%) even under 2x
> oversubscription. Therefore, further optimization of the synchronization
> primitive itself is deprioritized. Future Stage 4 work should focus on
> reducing regional execution variance, improving adaptive sharding, and
> eliminating expensive cross-region work rather than attempting to bypass
> `awaitLatch()`.

No code change resulted — a negative result (confirms an existing design
has no hidden bug), which is itself the useful Stage 4.5(c) deliverable
Gemini endorsed, and eliminates a whole class of wrong future optimization
attempts from the roadmap.

### The reframe this result forces: barrier ≠ bottleneck, LONG-TAIL REGION = bottleneck

`awaitLatch()` doesn't cost 10-20ms by itself — it just reports "the
slowest region is still working." The real lever was never the
synchronization primitive; it's **regional execution variance**. A tick
where four regions cost 2-3ms each and a fifth costs 50ms still takes
~50ms — not because of the barrier, but because the barrier creates global
coupling to whichever region takes longest. Cut that one region's cost (via
a split, not via touching the barrier) and the WHOLE server tick drops
proportionally, with zero barrier-mechanism changes:

```
Before:  R1=2ms R2=2ms R3=3ms R4=17ms(bottleneck)  -> tick ~17ms
After:   R1=2ms R2=2ms R3=3ms R4a=5ms R4b=6ms       -> tick ~6ms
```

This directly explains the original motivating incident (the "sand duper"
region hitting 41-97ms dragging the whole server to ~5-10 TPS) more
precisely than "the barrier is slow": it was never the barrier — it was
one region's UNCAPPED long tail (since fixed at the region-budget/
tracker level, see [[stage4-budget-and-tracker-enabled]]) coupled through
a barrier that, correctly, cannot and should not let that region's tick be
ignored.

**Where this points Stage 4 next**: `RegionSplitManager`'s split heuristic
currently scores mainly on entity/chunk/block-tick-density signals. The
more precise target this result implies is tracking each region's own
**p95/p99 tick-cost and variance** directly (not just current-tick cost or
density proxies), and splitting proactively when a region's tail latency
grows relative to its siblings — before it becomes THE tick's bottleneck,
not after. Not implemented this session; a natural next increment for
whoever picks this back up.

## Finding carried over from this pass — FIXED 2026-08-10

`ChunkAccess`/`ProtoChunk`'s `structureStarts`, `structuresReferences`, and
`carvingMasks` (plus `ProtoChunk.entities`) were the SAME class of
cross-chunk-write race already fixed for `blockEntities`/`postProcessing`/
`heightmaps` (see `THREAD_SAFETY_PASSES.md` #8/#12) — `WorldGenRegion` can
resolve a write into a neighbouring chunk during concurrent feature/structure
generation, and these four fields were plain, unsynchronized collections
with no lock. Closed as Stage 4.3's remaining open item (same fix pattern as
#8: `A`, lock the structure) — see `THREAD_SAFETY_PASSES.md` #12 for the
full fix and validation.

## Stage 5 scoping — research only, not implemented (2026-08-10)

Explicitly NOT an attempt to implement independent local time — that would
repeat a mistake already caught and corrected twice this session. This is
genuine design progress on the specific, previously-identified blockers,
so a future attempt has real groundwork instead of starting from "it's
hard" each time. Nothing here is validated by testing; treat every proposal
below as a hypothesis to be reviewed (Gemini-consulted, at minimum) before
any code is written against it.

### Blocker 1: weather / day-night time — the most tractable of the four

Reframing as a Tier 1 (snapshot) problem, the same shape already proven for
ghost-zone `BlockEntity` reads: weather/dayTime don't need to be exactly
synchronized to any one region's local tick — a region reading a value that
is 1-2 real ticks stale is imperceptible to players (nobody notices "it
started raining one tick late in this specific region"). Proposal: promote
weather/dayTime to a main-thread-owned value, advanced on the MAIN
thread's own pace (today's global tick, unchanged), published via a
lock-free volatile snapshot (same ping-pong pattern as
`ServerChunkCache.nestworldLoadedFull`) that any region reads at whatever
point its own local tick needs it. Writes never come from a region thread.
This is the LOW-RISK end of Stage 5's blockers — it doesn't require solving
per-region local time at all, just decoupling one specific piece of global
state into an already-proven read pattern. Could plausibly be prototyped
independently of the other three blockers, since it doesn't require any
region to actually run on a different local tick yet.

### Blocker 2: `LevelTicks` — partial decoupling looks tractable, full independence does not

`LevelTicks`' COLLECTION phase (deciding which scheduled ticks are due)
already runs single-threaded on main, confirmed safe by
`THREAD_SAFETY_PASSES.md` #10 specifically because it runs to completion
BEFORE handing bucketed work to region threads — collection and region
execution never overlap in time today.

Observation: `RegionThread.runWorkBudgeted()`/`carriedWork` ALREADY defers
un-executed work to "next tick" when a region's budget is exhausted — this
is a real, working instance of "this region's view of its own backlog can
lag behind the global collection pace." The missing piece for genuine local
time is that "next tick" currently means "next GLOBAL tick" (the very next
`tickAllRegions()` call); true local time would need it to mean "next LOCAL
tick," decoupled from the global counter.

Candidate direction: keep COLLECTION global-paced (main thread, tied to
`tickCount`, unchanged — this is the part vanilla's `LevelTicks` internals
assume and rewriting IT specifically is the expensive, risky part) but let
EXECUTION become region-paced, building on the existing budget/defer
mechanism rather than replacing it. A region running behind its own local
clock would simply have a longer `carriedWork` backlog, executed as its own
pace allows. This does NOT by itself solve cross-region visibility (what
does Region B, at local tick 480, see of Region A's blocks if A is at local
tick 500?) — that's Blocker 3's problem, not this one's. Partial
conclusion: `LevelTicks`' EXECUTION side may not need a full rewrite if
built on infrastructure that already exists; its COLLECTION side keying to
the global tick may be able to stay exactly as-is. This narrows the
originally-assumed "rewrite LevelTicks entirely" scope, but does not
eliminate the harder problem below.

### Blocker 3: border-band's safety proof — the genuinely hard, unsolved one

Today's proof is simple: "when main touches border-adjacent state, every
region is parked, so nothing else can be mutating it." Under independent
local time, there is no shared instant where that's true — Region A and
Region B could be at different local ticks at any real-world moment.

This is NOT solved here. What's clear from this session's evidence: the
resolution isn't "wait for both regions to be at the same numeric local
tick" (that's disguised lockstep, defeating the point) but some notion of
"has each region PROVABLY passed the point where a border-adjacent mutation
from tick N could still be pending" — i.e. a per-region-PAIR
happened-before relationship, not a single global instant. This is the
shape of problem vector clocks / Lamport timestamps solve in distributed
systems — each region would need to track, for each NEIGHBOUR it borders,
some notion of "the latest neighbour-tick I've observed/acknowledged" and
only apply a border mutation once the neighbour has acknowledged reaching
an equivalent point. This is a genuinely new mechanism, not a refactor of
existing code, and needs its own dedicated design pass (with Gemini
consultation) before any implementation attempt — flagging the SHAPE of
the solution space is as far as this pass goes.

### Blocker 4: split/merge under no-global-pause

Today: `applyPending()` runs during the barrier window, with every region
parked — the BSP tree mutates atomically because nothing else can observe
an inconsistent intermediate state. Under independent local time, splitting
or merging two regions requires migrating entity ownership and chunk
territory between two ALREADY-INDEPENDENTLY-RUNNING threads — this needs
its own quiesce-migrate-resume protocol scoped to just the regions actually
involved (pause A and B specifically, not the whole world), which does not
exist today in any form. Not designed this pass — noted as a real,
separate piece of work, likely easier than Blocker 3 since it only needs
to coordinate 2 (or a handful of) regions at a time, not an open-ended
neighbour graph.

### Net assessment

Blocker 1 (weather/time) looks genuinely tractable as an independent,
low-risk first step — it does not require any other blocker to be solved
first. Blocker 2 (`LevelTicks`) is narrower than originally assumed IF its
collection phase can stay global-paced. Blockers 3 (border-band) and 4
(split/merge) remain the real, unsolved core of Stage 5 — 3 in particular
needs a genuinely new synchronization concept, not an extension of
anything that exists today. Recommended next step, if Stage 5 work
continues: prototype Blocker 1 alone (weather/time as a decoupled
Tier-1-style snapshot) as a self-contained, low-risk exercise — it
delivers no independent-time capability by itself, but validates the
"decouple one piece of global state safely" pattern Blockers 2-4 will all
eventually need in some form, with a much smaller blast radius than
attempting border-band or LevelTicks first.

## Stage 5 staging — Gemini review corrected the order (2026-08-10)

The user proposed a staged sequence for Stage 5's four blockers: 5.1
(weather/time snapshot) -> 5.2 (LevelTicks: global collection, regional
execution) -> 5.3 (cross-region causal ordering, vector-clock-shaped).
Consulted Gemini for independent review before any code — same discipline
that already caught two unsafe designs this session, and it caught a real
one again here, before a single line was written this time.

**5.1 — confirmed sound, independently prototypable, no changes needed.**

**5.2 — flagged UNSAFE in this position.** Letting LevelTicks execution
become region-paced while collection stays global-paced reintroduces
exactly the causal-consistency problem the barrier currently solves, just
less visibly: a LevelTick due at global tick N, executed by a region at
its own local pace, can mutate border-adjacent state with no guarantee a
neighbouring region's view is causally consistent with that mutation.
`RegionThread.carriedWork` was misapplied in the original proposal — it
balances load WITHIN one global tick's work, it does not grant causal
independence between regions' progress. Building 5.2 before 5.3's
protocol exists risks either an unsafe system or expensive rework once
5.3's actual requirements are known.

**5.3 — right conceptual shape (vector clocks / happened-before per
region-pair), but harder in practice than the framing suggested.** Key
grounding data point: **Folia itself does not achieve full independent
time for everything that matters cross-region** — it still runs a global
tick for entity movement and other globally-relevant state, deliberately
NOT solving the hardest version of this problem. The most successful real
prior art in this exact space works around full independence rather than
achieving it. Vector clocks correctly DETECT concurrent/conflicting
updates but do not RESOLVE them — Minecraft's mutable border-band block
state needs an explicit, separately-designed conflict-resolution layer on
top, and enforcing strong consistency for causally-dependent operations
(redstone, fluids) reintroduces a wait — expressed through a per-pair
protocol instead of a global barrier, but not eliminated, only localized
and (hopefully) narrower.

**Corrected ordering**: 5.3's conceptual protocol (even just a whiteboard-
level design — message shapes, what "has my neighbour passed this point"
means operationally, conflict resolution strategy) must be designed BEFORE
5.2 is implemented, not after. 5.1 remains safe to prototype independently
of this ordering constraint.

**Practical outcome**: 5.1 stays a viable, safe, isolated next step if
Stage 5 work continues. 5.2 is now explicitly BLOCKED on 5.3's design, not
just "next in line". Given the Folia comparison, 5.3 itself looks like a
genuine, open-ended research question rather than a scoped implementation
task — the honest expectation should be that full Stage 5 (independent
local time with strongly-consistent border-band) may not be achievable in
the form originally imagined, and a Folia-style deliberate partial
independence (some state stays global, matching what even the most
successful prior art settled for) may be the realistic target, not a
compromise to avoid.

## Stage 5.3 — causal protocol design (research, 2026-08-10)

Per Gemini's correction: this must exist, at least conceptually, before any
`LevelTicks`/5.2 code. Design only — nothing here is implemented or
validated by testing. Built by extending patterns already proven in this
codebase (`RegionMessage` mailbox, ownership transfer, adaptive splitting)
rather than inventing new machinery where existing machinery already fits.

### Goal, stated precisely

Give border-adjacent cross-region interactions (entity transfer, wire
updates, explosion/block writes — today's Tier 2 mailbox) a delivery and
ordering guarantee that does NOT require both regions to be at the same
numeric tick, while still being causally sound: a receiving region must
never observe messages from one sender out of the order that sender sent
them, and must apply them at a well-defined point relative to its own
local work on the same state.

### Why full vector clocks are the wrong tool here (scope correction)

Vector clocks solve "total causal order across N arbitrary participants."
NestworldCore doesn't need that: two regions only ever interact if they are
CURRENTLY adjacent (share a border), and the classification table this
project already produced (Stage 3/4.3) shows every real cross-region
interaction is a DIRECT region-to-region message, never a multi-hop chain
through a third region. This means the actual requirement is much narrower:
**pairwise FIFO ordering between adjacent regions**, not a global vector
clock — closer to how TCP orders one connection's stream than to a
distributed-database's multi-writer vector clock. Cheaper to implement,
cheaper to reason about, and matches the problem's real shape.

### Candidate mechanism: per-neighbour-pair sequence numbers

For each region `R`, and for each OTHER region `N` it currently borders (a
new concept — "region adjacency" doesn't exist as a first-class thing
today; it would need to be derived from the BSP tree's leaf-adjacency,
recomputed whenever the tree changes):

- `R` keeps an outgoing counter `seq(R->N)`, incremented once per message
  `R` sends toward `N`'s territory (reusing the EXISTING `RegionMessage`
  shape — `sourceRegion`/`destinationRegion`/`payload` already there; add
  a `sequence` field).
- `R` keeps, per neighbour `N`, the last successfully applied inbound
  sequence `applied(N->R)`.
- On receipt of `(N, seq, payload)`: apply immediately only if
  `seq == applied(N->R) + 1`; otherwise buffer it (out-of-order arrival is
  possible since messages travel through a `ConcurrentLinkedQueue`, not a
  literal wire) until the gap closes, then apply the run in order.

This gives per-sender FIFO — property (b) from the analysis: two messages
from the same region arrive and apply in the order that region sent them.
It does NOT give a total order across DIFFERENT senders to the same
region, but per the classification table nothing in this codebase needs
that (each Tier 2 message type already targets one specific destination
region for one specific reason — entity transfer, one wire update, one
explosion's foreign subset — never requires ordering against a message
from a DIFFERENT sender).

### Local application ordering (answers concern (a): message vs. the receiver's own local work)

Since `N`'s owned state is only ever locally mutated by `N` itself and
messages from OTHERS, `N` fully controls when in ITS OWN local tick it
drains its inbox — the natural choice, extending the CURRENT pattern
exactly (mailbox drain happens at a fixed point relative to a region's own
tick, today "after the global barrier / before the next region round"),
is: drain and apply all in-order-ready inbound messages at the START of
`N`'s own local tick, before `N`'s own tick logic touches the same
positions. This is a purely LOCAL decision each region makes about its own
execution order — no cross-region synchronization needed for this part,
only for the delivery/ordering guarantee above.

### Overload / backpressure semantics — feeds directly into existing split machinery

If `N` falls badly behind (chronically can't drain its inbox as fast as
`R` produces messages), `R`'s outgoing queue toward `N` would grow
unboundedly without a bound. Rather than inventing a new throttle
mechanism, this connects directly to [[p95-variance-aware-split]]'s
just-shipped infrastructure: track each region's outbound-queue depth per
neighbour as ANOTHER split-trigger signal (alongside p95 tick-cost) —
a region that can't keep its inbox drained relative to a neighbour's send
rate is, by definition, the kind of region the split heuristic already
exists to catch and break up. This avoids designing a second, parallel
overload-handling mechanism; it reuses the one already proven this
session. A hard cap (bounded queue, oldest-message-coalescing or
drop-with-log past some depth) would still be needed as a last-resort
safety valve, matching the existing `DEFERRED_SPAWN_QUEUE_CAP` precedent
(anti-grief bounded queue, already shipped and validated).

### What this does NOT solve (honest gaps)

- **Split/merge migration of in-flight sequence state**: when region `A`
  splits into `A1`/`A2`, any in-flight messages addressed to `A` and each
  neighbour's `seq(N->A)` counters need to be migrated/reconciled with
  whichever child now owns the relevant territory. Not designed here —
  this is Blocker 4's quiesce-migrate-resume protocol, and the two need to
  be designed together, not independently, since a split happening
  mid-flight is exactly the failure case that would corrupt sequence
  ordering if not handled.
- **Observable latency change vs. vanilla**: a redstone cascade that
  crosses a region border would take however long the destination region's
  local tick takes to catch up and drain its inbox, not "the same tick" or
  "next global tick" as today. This is the SAME class of tradeoff Tier 2
  already accepts (one tick of latency, today) — under independent time it
  becomes "some bounded but potentially larger latency" instead of a fixed
  one-tick cost. Whether this stays imperceptible or becomes an observable
  gameplay difference (a lever that visibly takes longer to light a lamp
  across a border) is an open question that needs live measurement, not
  just design reasoning.
- **`LevelTicks`' collection phase**: this design suggests collection
  should ALSO become per-region (each region collecting its own due ticks
  against its own local clock) rather than staying global as the earlier
  5.2 proposal assumed — a cleaner fit with this protocol than trying to
  straddle global collection with regional execution. Not designed in
  detail here; flagged as the natural next question once THIS protocol is
  reviewed.

### Status

Design only. Not reviewed by Gemini yet (unlike every other decision this
session) — that review should happen before treating any part of this as
settled, given the established discipline of this whole Stage 4/5 process.

## Stage 5.3 design — REJECTED after Gemini review (2026-08-10, same session)

The pairwise-FIFO design above was reviewed before any code was written
(same discipline applied throughout Stage 4/5). Verdict: **does not hold
up**, same "looks coherent, isn't actually safe once traced through" class
as the rejected Stage 5.2 proposal. Three concrete, specific flaws found —
not vague concerns, each with a constructible failure scenario:

**1. Non-deterministic game state (critical).** Per-sender FIFO only
orders messages FROM ONE sender. It gives NO guarantee between two
DIFFERENT regions (both adjacent to a third, but not to each other)
sending conflicting messages to the same target position — e.g. region A
sends `DESTROY_BLOCK(X,Y,Z)`, region B sends `PLACE_BLOCK(stone,X,Y,Z)`,
both targeting region C. The final block state depends on arbitrary queue-
arrival order — genuinely non-deterministic, unacceptable for block writes
and explosion-apply specifically (entity transfer and wire-update may be
less exposed to this exact shape, not yet analysed). This was missed
entirely in the original design.

**2. Unbounded receiver staleness is worse than today's model.** "Let the
receiving region choose its own inbox-drain timing" doesn't corrupt data
(sequence numbers still hold), but if a receiver falls behind, a sender's
messages can sit for an arbitrarily long, unpredictable REAL-WORLD delay —
not just slower, but variable and potentially extreme. This breaks the
"coherent single world" illusion at a border more severely than today's
fixed one-tick Tier-2 delay, which is at least predictable.

**3. Backpressure-via-sender-splitting is unsound, not just underspecified.**
High outbound-queue-depth from R toward N indicates N is slow, not that R
is overloaded — splitting R doesn't address the actual bottleneck at N.
Worse: if N is bordered by several healthy regions, ALL of them would
independently trigger unnecessary splits trying to "fix" a problem that
isn't theirs. Worse still: triggering a split specifically WHILE a region
has an in-flight message backlog is close to the worst possible timing
given the split/merge-migration gap is still unsolved — this compounds two
unsolved problems instead of avoiding one.

### What's now clearer (the useful output of a rejected design)

Matches the user's own framing: proving a specific approach doesn't work
is a real result, not a failure, when it narrows the design space with
concrete reasons. Three requirements a next attempt must satisfy, now
known precisely (not knowable before this review):

- Needs some mechanism for ordering/resolving CONCURRENT writes from
  different senders to the same receiver-owned position — not just
  per-sender ordering. Candidates not yet evaluated: per-position
  ownership/locking for the duration of an in-flight foreign write,
  deterministic conflict resolution (e.g. a well-defined "last globally-
  ordered write wins" rule requiring SOME global ordering primitive after
  all), or proving (not assuming) that this codebase's actual message
  types never really produce same-position conflicts in practice (would
  need evidence, not the assumption made this round).
- Needs a bound on receiver staleness — some region cannot be allowed to
  leave a neighbour's messages undrained indefinitely; this likely
  reintroduces SOME form of wait or forced prioritization, hopefully
  narrower than a global barrier but not zero.
- Backpressure/overload handling for this specific problem (a receiver
  falling behind its neighbours) needs its own mechanism, evaluated
  against the RECEIVER, not folded into the sender-side p95 split
  heuristic — and must be designed jointly with the split/merge-migration
  protocol (Blocker 4), not before it or independently of it.

Not re-attempted this session — this is exactly the kind of result the
project owner explicitly said is valuable even without a working design:
concrete evidence of what a working Stage 5.3 protocol must additionally
solve, obtained by review before implementation rather than by a live
failure after.

## Stage 5.3 design v3 — revised, all three findings addressed (2026-08-10, same session)

Immediately re-attempted (same session), addressing all three requirements
above one at a time, each independently reviewed before combining. Root
insight that unlocked this round: the rejected v1 design borrowed
sequence-number/vector-clock machinery from **distributed systems**, which
exists to handle an **unreliable network** (loss, duplication, reordering
in transit). NestworldCore is not distributed — it is a single JVM, and
cross-region messages already travel through a plain in-process
`ConcurrentLinkedQueue`, which already guarantees no loss, no duplication,
and preserves real-time insertion order. Importing distributed-systems
tools for a shared-memory problem was the actual mistake in v1, not the
general idea of per-neighbour tracking.

**Fix 1 (ordering/same-position conflicts).** Do not fragment into
per-neighbour-pair queues (that fragmentation is what destroyed ordering
in v1). Keep the existing single shared mailbox per *destination* region
(`WorldRegion.nestworldMailbox` already works this way today — every
sender posts into the same queue). A single `ConcurrentLinkedQueue`
already gives a genuine, non-corrupting total order across all senders
combined: whichever `add()` call physically executes first wins, every
message delivered exactly once, none lost or duplicated. This does not
make the winner of a same-position race *predictable in advance*, but it
is *well-defined and non-corrupting* — matching the level of determinism
vanilla Minecraft's own single-threaded tick already has (HashMap
iteration order, packet arrival timing are already not run-to-run
reproducible in vanilla). Gemini-reviewed and accepted: "the critical
aspect for a game is that world state remains internally consistent and
non-corrupting, even if the precise outcome of a highly concurrent race is
not perfectly reproducible run-to-run... typically fine."

**Fix 2 (bounded staleness + bounded per-tick drain cost).** Move
mailbox-draining from "main thread does it centrally at the barrier"
(today's model) to "each region drains its own inbox as the first step of
its own local tick," **budgeted** (at most N messages or T ms, whichever
first — same budgeted-work-slice pattern already used for entity/AI
processing). Bounds staleness to "at most a few of the receiver's own
local ticks" (exactly 1 in the common case, more only under real backlog,
never unbounded) *and* prevents a single anomalous inbox flood from
blowing that tick's entire work budget — the two failure modes Gemini's
first review pass flagged separately (macro/sustained via p95 vs.
micro/single-tick blowout) are both closed by this one budgeted-drain
mechanism.

**Fix 3 (backpressure without message loss — the part that took three
rounds).** Rejected twice before landing: (a) hard-capped queue with
`offer()` refusing when full — rejected, Gemini: "message loss is
catastrophic" for entity-transfer/block-write correctness, and
reintroduces exactly the retry/ack/reconciliation complexity that leaning
on `ConcurrentLinkedQueue`'s reliability was meant to avoid in the first
place; (b) unbounded queue depth treated as a pure diagnostic/health
signal (p95-style) — rejected, Gemini: this project has a documented
history of real OOM/GC-pressure incidents, and "an OOM error typically
leads to a hard server crash... far worse than a controlled slowdown,"
detection-only is too reactive for a failure mode that fast. **Landed on:
sender-side scheduler pause, not a queue cap and not a thread block.**
When a destination region's queue depth crosses a threshold, the
*sending* region's own local scheduler skips that region's next local
tick slot for producing new outbound messages (its own unrelated work,
and critically its own inbox draining, continue normally) until the
destination's depth drops. This bounds memory (no new messages get
produced while paused), causes zero message loss (nothing is ever
dropped, only delayed at the source), and cannot deadlock the way
thread-level blocking could (a paused sender's own inbox keeps draining,
so a receiver that's merely backlogged — not actually stuck — keeps making
progress on the very messages that would let the pause end; two mutually
adjacent regions each independently pausing their own outbound production
does not stop either one from continuing to drain what's already arrived).
Gemini, final verdict: "excellent and highly recommended... provides
bounded memory... ensures non-lossy delivery... avoids deadlock/blocking
threads... proactively prevents memory exhaustion... exactly the kind of
proactive, non-lossy, non-blocking backpressure needed to close this gap."

**Status: this is now a complete, Gemini-reviewed design for all three
previously-rejected gaps**, built entirely from patterns this codebase
already has proven in production (single shared ordered queue, budgeted
work-slices, p95-based health signals, backlog-tolerant scheduling like
`carriedWork`) rather than new synchronization primitives.

## Stage 5.3 v3, Fix 2 (budgeted drain) — IMPLEMENTED and validated (2026-08-10, same session)

The narrowest, lowest-risk slice of v3 — budgeted mailbox draining — was
implemented and shipped, still fully inside today's barrier model (does
NOT touch Blocker 3/4, does NOT remove `awaitLatch()`). This is a genuine
defensive improvement independent of whether full independent local time
is ever attempted: today's `EXPLOSION_APPLY`/`BLOCK_WRITE` drain at the
barrier was unbounded — a single flood-triggering event (chain-reaction
explosion crossing a region boundary) could apply an arbitrarily large
batch in one main-thread pass with no cap, structurally the same class of
risk `DEFERRED_SPAWN_BUDGET_NANOS` already exists to prevent for entity
spawns.

**What shipped**: `WorldRegion.nestworldDrainMailboxBudgeted(Type,
deadlineNanos, applier)` — applies queued messages of a type via a
caller-supplied handler until a shared deadline passes, removing from the
queue only what actually got applied; the remainder stays queued for a
later barrier pass (extends, doesn't violate, Tier 2's existing
"eventually applied" contract). `NestworldTuning.MAILBOX_DRAIN_BUDGET_NANOS`
(default 5ms, system-property tunable) — one deadline shared across every
region and both message types in a single barrier pass. `WorldRegion
.nestworldMailboxSize()` plus a `mailbox=N` field on `/nestworld status`
(shown only when non-zero) for observability — the fix 3 (backpressure)
design explicitly deferred pending evidence this ever fires live.

**Live validation on gen-spike-repro**: rebuilt (`:forge:installerJar`),
reinstalled, fresh boot — normal status unaffected (`mailbox` field never
shown, 0 depth as expected). Forced a genuine cross-region explosion flood
twice: a 1,377-block TNT cube straddling the region 0/1 boundary (z=0),
then a 13,175-block cube ignited from its center for maximum simultaneous
cascade. Second test peaked at 6,772 entities, TPS dipped to 15.5 under
the load and self-recovered to 20.0 as the existing p95/adaptive-split
mechanism auto-split 4 regions -> 6 to absorb it — same machinery this
session's earlier work (p95-variance-aware-split) already validated,
confirming it still works correctly alongside this change. No crash, no
new crash-report, no warning traceable to the new code (the one "Can't
keep up! ... 40 ticks behind" line is the same vanilla watchdog message
this kind of stress test always produces, from real entity/spawn cost —
matches the ATM9 mass-TNT precedent already documented in
`DEFERRED_SPAWN_BUDGET_NANOS`'s own javadoc). `mailbox=` never appeared
even under this load — the EXPLOSION_APPLY/BLOCK_WRITE volume from a real
explosion, even a very large one, apparently never actually exceeds a
5ms/tick apply budget in practice; the dominant cost in a TNT flood is
entity spawns (already budgeted separately), not cross-region block
writes. Deployed the same build to ATM9 (188-mod real pack) afterward:
clean reboot, 20.0 TPS, 19.2ms/tick, 8 regions, no regressions.

**Honest takeaway**: this shipped as a correctly-designed, low-risk
preventive improvement (reuses an already-proven pattern, verified not to
regress anything under real heavy load on both test targets) rather than
as a fix for an observed live problem — the "budget exceeded, message
deferred to next tick" branch was never actually exercised in either live
test, despite a deliberate attempt to trigger it with a genuinely large
cascade. That is itself informative: it suggests this specific risk
(mailbox-apply-cost stalling the main thread) may not be a real bottleneck
in practice at the scales tested so far, unlike the entity-spawn case the
budget pattern was originally built for. Fix 1 (ordering — no code needed,
already the existing single-mailbox design) and Fix 3 (sender-side
backpressure pause) remain **design-only, not implemented** — Fix 3
specifically deferred pending evidence of real queue-depth growth, which
this round's testing did not produce.

Next step if continued: either attempt Blocker 1 (weather/time) as
originally recommended, or continue watching `mailbox=` in production
under organic (non-synthetic) load for enough real ticks to decide whether
Fix 3's backpressure mechanism is worth implementing at all.

## Blocker 3 (border-band under independent local time) — reconsidered, partially closed (2026-08-10, same session)

User asked to keep working. Re-scoped Blocker 3 by tracing the ACTUAL
current choke points (not re-asserting the earlier, less rigorous "needs
vector clocks/Lamport timestamps" framing) — found the codebase already
funnels essentially ALL border-adjacent risk through a small number of
protected paths: 4 vanilla tick phases routed main-thread-inline (§3
above), `Level.explode()`'s own classify-and-batch mechanism (Stage 3),
and `Level.setBlock()`'s single choke point guard (Stage 3 EXTENSION).
That last one turned out to have a real, previously-flagged-but-unfixed
gap: it only checked OWNERSHIP (out-of-bounds), never BORDER-BAND
PROXIMITY — a write comfortably inside the calling region's own bounds but
within `BORDER_BAND_CHUNKS` of an edge applied directly, cascade and all,
unlike the other 3 phases which have always kept anything in the band off
region threads entirely. This is the exact "top-priority open item...
needs a real design decision" flagged earlier in this document and never
implemented (only vanilla `Explosion` got the full fix, not generic
entity-AI/mod writes near — but not across — a border).

**Gemini review** (same rigor as the Stage 5.3 rounds): proposed widening
the guard to close this gap, with a hypothesis that doing so might close
Blocker 3 ENTIRELY (no new synchronization primitive needed) by making
main thread the sole toucher of border-band state, decoupled from any
region's local tick pace. Verdict: **half right.** Confirmed sound for
WRITES — centralizing all border-band writes onto main thread genuinely
eliminates the region-thread-side write race, cheaply, reusing existing
mechanisms. **Found a real remaining flaw**: main thread's border-band
processing sometimes needs to READ a neighbouring region's INTERIOR state
to make correct decisions (e.g. a piston in the band pushing a block PAST
the band into a region's true interior needs to know if the destination
is free) — Tier-0/Tier-1 give read SAFETY (no crash) but not read
FRESHNESS/consistency for decision-making; decoupling main's pace from
region ticks reintroduces exactly the coordination problem the design
claimed to avoid for this specific case. Also flagged a real player-facing
staleness/consistency concern independent of correctness (border-band
effects visibly decoupled from a nearby region's own pace would look
"laggy"/inconsistent).

**What shipped** (the confirmed-sound write-side half): widened
`Level.setBlock()`'s guard — `WorldRegion.isInBorderBand(cx, cz)` (new,
reuses the existing `BORDER_BAND_CHUNKS` constant, same margin/rationale
`interiorRegionFor`/`queueRandomTicksFor` already use) — now deferred via
the SAME existing `BLOCK_WRITE` mailbox mechanism whenever a write lands
either out-of-bounds OR in-bounds-but-in-band (self-targeted message in
the latter case, applied at the same barrier-safe point). Closes the
historical gap under TODAY's model (independent of whether Stage 5 ever
ships) and removes the write-side half of the Stage 5 race.

**Live validation on gen-spike-repro**: functional test, not just
non-crash — a piston at a region boundary pushed an `iron_block` FROM one
region ACROSS the boundary INTO the neighbouring region's territory
(confirmed via reliable `execute if block ... run setblock <marker>`
checks before/after, not just visual inspection): piston head correctly
left behind, iron block correctly landed one block into the neighbour's
territory, no duplication, no loss. Re-ran the same 1,377-block TNT
cross-border flood from the earlier Fix 2 test as a regression check: TPS
dipped 18.0 momentarily, recovered to 20.0, auto-split 7->10 regions, no
crash, no new warnings. Same build deployed to ATM9 after: clean reboot,
20.0 TPS, no regressions.

**Honest status of Blocker 3**: narrowed, not closed. The write-side race
is resolved and shipped. The read-side gap Gemini found — main thread (or
whatever eventually replaces it under real independent local time) needing
a FRESHNESS-guaranteed read of a neighbour's interior state for cascades
that reach past the band — remains genuinely open, and is now much more
precisely scoped than the original "everything about border-band needs
vector clocks" framing: specifically, only cascades whose effect crosses
FROM the border band INTO a neighbouring region's true interior (piston
pushes past the band, fluid flow, redstone reaching that far) need this;
ordinary border-band-local reads stay Tier-0/Tier-1 safe as already
established. This is real forward progress (a materially smaller open
problem) even though the full blocker isn't resolved — matches this
session's established pattern of over-scoped original framings turning
out more tractable once traced through actual choke points, but this time
genuinely only PARTIALLY, not fully.

## Blocker 3 read-side — fully scoped (problem-statement-complete), fix intentionally NOT implemented (2026-08-10, same session, continued)

User asked to close it. Pushed the read-side gap to a precise, quantified
problem statement — this is now genuinely closed AT THE DESIGN LEVEL, per
Gemini's own explicit verdict ("you have definitively closed the problem
scoping for Blocker 3... a monumental step forward"), even though no new
code shipped from this round.

**The gap turned out worse than Gemini's own original example** (a
cross-region piston push) — traced against the ACTUAL vanilla constants,
confirmed by reading the code, not assumed:
- `PistonStructureResolver.MAX_PUSH_DEPTH = 12` — a single push chain
  moves blocks up to 12 positions.
- Redstone dust signal strength is 0–15, decaying 1/block from a
  full-strength source — confirmed via the propagation loop's own bound —
  so a single wire cascade can affect blocks up to 15 positions away.
- `BORDER_BAND_CHUNKS` is a FIXED 32-block width from a region's edge, but
  a trigger position can sit anywhere inside it, including its innermost
  point (31 blocks from the true edge). A cascade triggered there,
  propagating AWAY from that edge, reaches 31+15=46 blocks (redstone) or
  31+12=43 (piston) — both past the 32-block boundary, into genuine
  interior territory, of EITHER the originating region or a neighbour.

**Why the already-shipped write-side fix doesn't cover this**: it defers
the INITIAL border-band write via the mailbox, applied later on the main
thread. But main-thread-initiated `Level.setBlock()` calls bypass the
RegionThread guard entirely (by original design — main-thread writes were
always "trivially safe" under the barrier, true today ONLY because every
region thread is parked while main applies deferred writes). Whatever
cascade that reapplied write triggers — the rest of a piston chain, the
rest of a wire's propagation — inherits NO further region-boundary
checking, and per the numbers above, can genuinely reach past the band.
Gemini, confirming the analysis: "the risk is real, verified, and stems
directly from vanilla game mechanics... [it] execute[s] their full vanilla
logic without regard for region boundaries until they hit their natural
limits."

**Fix shape (design only, NOT implemented)**: a "cascade safety margin" =
band width + max single-cascade reach = 32 + 15 = 47 blocks, rounded to 3
chunks (48 blocks) from an edge. Applying a border-band-deferred write
would need to hold a lock covering every region whose territory falls
within this WIDER margin of the relevant edge (not just 2 immediate
neighbours — a corner can touch 3–4), acquired in a globally consistent
order (e.g. region ID ascending) to avoid deadlock between overlapping
cascades, held for the write's full reapplication. Each region's own
local-tick loop would need to respect the same lock before touching its
own territory within that margin of any edge.

**Gemini's assessment of the fix shape**: the *need* and the *quantified
radius* are both correct; the *specific mechanism* (dynamic per-write
region-set locking) is a plausible starting shape but has real, named
risks that are premature to resolve now — efficiently identifying which
regions fall within a dynamic 48-block radius needs real spatial indexing
(not just pairwise-neighbour lookups); lock granularity this coarse risks
enough contention to defeat the point of independent ticking; topology
changes (split/merge) mid-lock-acquisition need their own conflict
handling. Gemini's explicit recommendation, followed here: document the
PROBLEM and the REQUIRED radius/shape now (this genuinely should inform
Stage 5's eventual scheduling design, or Stage 5 risks being "built on an
unstable foundation, requiring significant, painful rework later"), but
do NOT design the precise locking mechanism further yet — it depends on
decisions about Stage 5's actual tick-scheduling architecture that haven't
been made.

**Why no code shipped this round, deliberately**: this piece has no
meaning under today's model — the existing global barrier already
provides exactly this "nothing else touches this territory right now"
guarantee for free, for every write, at every reach, today. There is
nothing for a new lock to protect against until independent local-tick
loops actually exist. Implementing it now would be the same trap already
identified and avoided once this session (Stage 5.3 v3's Fix 3,
backpressure-via-sender-pause, similarly deferred for having no meaning
without Stage 5's local-tick loops existing) — unvalidatable
infrastructure, built on guesses about a scheduling architecture that
doesn't exist yet.

**Blocker 3, final status**: problem-statement-complete (Gemini's own
words). Write-side: fixed and shipped, live-validated, independent of
whether Stage 5 ever proceeds further. Read-side: fully scoped, quantified
(48-block cascade safety margin), fix-shape identified, deliberately NOT
implemented — the correct next step, if Stage 5 continues, is either
Blocker 4 (split/merge under no-global-pause, "likely easier... only needs
to coordinate a handful of regions") or committing to Stage 5's actual
tick-scheduling architecture first, since that architecture is now known
to directly determine what the read-side fix's locking granularity even
should be.

## Stage 5 tick-scheduler architecture — DECIDED (2026-08-10, same session, continued)

User pushed back on the accumulating "narrowed, not closed / scoped, not
implemented" pattern ("я не вірю що 5 неможливо зробити") — a fair
challenge: nothing in this session's findings ever concluded Stage 5 is
IMPOSSIBLE, only that specific pieces (Blocker 3's read-side lock
granularity, Stage 5.3 v3's Fix 3 backpressure) had been deliberately left
unimplemented pending a decision about the actual tick-scheduling
architecture that didn't exist yet — a real distinction, but one that had
started to read as indefinite deferral. Asked directly what to do; user
chose: commit to the tick-scheduler architecture decision now, rather than
keep waiting for a more perfect moment.

**This section IS that decision** — reviewed by Gemini with the explicit
framing "this is a decision, not a hypothetical to defer," same rigor as
every other design this session. Verdict: **"This is a sound and
implementable Stage 5 tick-scheduler architecture. Commit to it as-is."**

### The architecture

1. **Region-local free-running ticking.** Replace `RegionThread.run()`'s
   `synchronized(tickSignal){wait()}` dispatch-driven loop (woken by
   `RegionThreadPool.requestTick()`, counting down a shared
   `CountDownLatch`) with a self-paced loop: each region times its own
   tick via `System.nanoTime()`, targets the same ~50ms cadence vanilla
   itself targets, parks the remainder if it finishes early, proceeds
   immediately if it's running behind (same "catch up, don't rubber-band"
   philosophy vanilla's own loop already uses). No shared latch or signal
   object across regions — each region's pacing becomes entirely its own.
   `WorldRegion` gains an instance `localTickCount`, distinct from
   `server.tickCount`.

2. **Main thread keeps its own, separately self-paced loop** for what
   Blocker 2's earlier scoping already said must stay global: `LevelTicks`
   COLLECTION (not execution), weather/day-time (already established safe
   via existing JMM happens-before, or an explicit periodic volatile
   snapshot), chunk ticket/loading management, players (already
   main-thread-ticked today), `ChunkMap.tick()`. Same ~50ms target, but no
   longer gated on `awaitLatch()` for any region's completion.

3. **Mailbox draining is already region-local by design** (Stage 5.3 v3
   Fix 2, shipped earlier today) — becomes the first, budgeted step of
   each region's own free-running local tick, exactly as designed, now
   actually exercised region-side instead of centrally at a barrier that
   no longer exists.

4. **Border-band write application (closes Blocker 3's read-side) — the
   one genuinely new mechanism, and it turns out not to be new machinery
   at all, just a wider use of an EXISTING lock.** `WorldRegion.chunkLock`
   (a `StampedLock`) is ALREADY what a region's own thread holds in write
   mode for its entire tick round today — already exactly the mutual-
   exclusion primitive the 48-block cascade-safety-margin problem needs.
   When region R drains a border-band-targeted message from its own
   inbox, it computes which OTHER regions' territory falls within 48
   blocks of the write's position (a cheap bounds-overlap scan over
   `WorldGrid.getAllRegions()` — tens of regions, not thousands), acquires
   THEIR `chunkLock`s (`tryWriteLock` with a bounded timeout — reusing the
   exact timeout philosophy `CROSS_REGION_READ_LOCK_TIMEOUT_NANOS` already
   established elsewhere in this codebase) in a GLOBALLY CONSISTENT order
   (ascending region ID; R's own lock excluded — it already holds it, and
   `StampedLock` isn't reentrant), applies the write (its cascade, however
   far within the 48-block margin, is now safe), releases the extra
   locks. On a timeout: leave the message queued, retry on a later local
   tick — same "eventually applied" Tier 2 contract every other deferred
   write already uses, no new failure mode. Ordinary border-band writes
   whose margin doesn't overlap another region (common case) skip this
   path entirely — zero added cost for interior-heavy play.

5. **Split/merge (Blocker 4, first designed this round) reuses the SAME
   lock**: a pending split/merge acquires the `chunkLock`s of every region
   whose territory changes (the splitting/merging region(s) plus any
   immediate neighbour whose adjacency bookkeeping updates), same
   consistent-order rule, mutates the BSP tree, releases — scoped to a
   handful of regions, matching the "likely easier than Blocker 3"
   expectation from the original scoping pass.

6. **Backpressure (Stage 5.3 v3 Fix 3) becomes meaningful for the first
   time** under this architecture — it was explicitly left unimplemented
   earlier today specifically because it protects against nothing while
   the barrier exists. Under free-running regions: before posting a new
   outbound message, check the destination's mailbox depth; over
   threshold, defer producing further outbound messages this local tick
   (retry next local tick) instead of posting — the same mechanism
   designed earlier, now with something real to protect.

### Gemini's review of the 5 named risks

1. **Deadlock** — sound, for the explicitly-defined scenarios (border-
   band application, split/merge), given both actually follow the same
   consistent-ordering rule. Caveat: the discipline must extend to ANY
   future code path that acquires more than one region's lock — this
   isn't automatically enforced by the type system, only by convention,
   so future changes touching multi-region locking need to be checked
   against this rule specifically.
2. **Contention** — real, not a flaw: holding `chunkLock` for a whole
   ~50ms tick round means a `tryWriteLock` from a neighbour CAN block for
   a meaningful duration in a busy multi-region corner (Gemini's own
   worst-case example: a redstone base built exactly at a 4-region
   intersection). The timeout+retry design handles this correctly
   (bounded wait, no deadlock, eventual progress) — it's a real, accepted
   trade-off (prioritizing eventual consistency + progress over lock-step
   sync), not a bug, but real-world tuning of the timeout value and
   profiling of actual contended-zone latency is needed once implemented.
3. **`RegionMessage.logicalTick` semantics** — Gemini flagged this needed
   clarification before code. Checked: `logicalTick()` (the accessor) has
   ZERO call sites anywhere in the codebase today — it's written on every
   message's construction but never read. Gemini's concern doesn't apply
   in practice; nothing depends on it as a scheduling/ordering signal.
   Documented going forward: it remains a coarse `server.tickCount`-at-
   creation-time diagnostic stamp only (matches its current behavior
   exactly, no change needed) — must NOT become a scheduling dependency
   if a future use is added, since regions no longer share a tick
   boundary under this architecture.
4. **Reusing `chunkLock` for both "own tick round" and "foreign cascade
   protection"** — sound and correct, per Gemini: "not an anti-pattern; it
   is precisely how you ensure mutual exclusion to the shared resource."
   The apparent "coupling" is just the real, intended contention over a
   genuinely shared resource, which the timeout+retry mechanism already
   manages correctly.
5. **Overall** — "sound and implementable... commit to it as-is." Noted as
   a future (not now) optimization if contention proves to matter in
   practice: a more granular lock than "whole tick round" could reduce
   contention further, a bigger refactor deliberately out of scope for
   this decision.

### Status: architecture DECIDED, not yet implemented

This is the keystone decision Blocker 3's read-side and Stage 5.3 v3's
Fix 3 were both correctly waiting on — both now have a concrete target to
implement against, no longer blocked on an undecided architecture. Actual
implementation (replacing `RegionThread.run()`'s dispatch loop, wiring
the region-set lock into the mailbox-apply path, main thread's own
decoupled loop, split/merge's lock reuse) is a large, high-blast-radius
change touching the core of the tick system — not done in this round;
this section records the decision itself, reviewed and validated, as the
deliverable the project owner asked for.

## Part 5 staging, in progress — mailbox audit, 5a, and a real P0 found (2026-08-10, same session, continued)

Executed the project owner's own staging order (Gemini-reviewed and
endorsed the day before): **0. mailbox audit instrumentation → 1. baseline
on the old barrier engine → 2. 5a structural refactor → [paused: Entity
Safety Layer audit] → a real bug found and fixed → Step 3 (Single
Free-Running Region) still pending.**

**Step 0/1 — `MailboxAudit`** (new class): every `RegionMessage` gets a
cheap sequence-number `auditId` (not a UUID — always-on, no cost when
audit mode is off); `sent`/`applied`/`duplicate`/`misrouted`/`pending`
counters, `/nestworld mailboxaudit` command. Baselined on TODAY's barrier
engine BEFORE any Stage 5 behavior change: 10,210 messages under a
synthetic TNT flood and 256 under real ATM9 organic play, both
`sent == applied`, zero anomalies — so any future divergence under
free-running regions is attributable to the new execution model, not a
pre-existing bug this audit would already have caught.

**Step 2 (5a)** — `RegionThread.run()` split into `awaitDispatch()`
(the wait-for-signal half) and `runOneTick()` (the tick-body half), zero
behavior change, validated the same way (16,711 messages sent==applied
under flood on repro, 232 on ATM9).

**Entity Safety Layer audit** (user-mandated pause on Step 3 until this
closes): forked out a systematic search for main-thread code reading live
Entity fields under an assumption the barrier no longer guarantees once
regions go free-running. Confirmed hazards: `BoundaryEntityTransfer`'s
scan (reads position/xo-yo-zo for transfer detection) and `ChunkMap.tick(
)`'s tracker fallback (flagged as the highest-frequency of the two —
would fire for every entity of a free-running region, every main-thread
tick, once `nestworldTrackedTick` stops aligning with `server.tickCount`)
— both **NEEDS-SNAPSHOT**, an EntitySnapshot (Tier 1 for entities,
mirroring the existing ghost-zone `WorldRegion.nestworldGhostBeSnapshot`
pattern) rather than the read-lock-per-callsite approach first proposed —
the project owner correctly rejected read-locking as too easy to miss a
call site and, more subtly, insufficient on its own: a `chunkLock`
read-lock spanning a multi-field read prevents TORN reads, but the real
fix needing centralization (one publish point, one safe-read artifact)
rather than trusting every future call site to lock correctly. Not yet
designed/implemented — deferred behind the P0 below.

### P0 found and fixed: `Explosion.explode()`'s entity-damage loop had zero ownership check — a live bug independent of Stage 5

The audit surfaced something more urgent than Stage 5 itself: `entity
.hurt(...)`/`entity.setDeltaMovement(...)` in the explosion entity-damage
loop mutate ANY nearby entity unconditionally, with no ownership check at
all — unlike `finalizeExplosion()`'s block-write path (already
Stage-3-protected). Since all regions already tick in PARALLEL today
(not just under a hypothetical free-running future), a TNT/creeper owned
by region A exploding near a border directly mutates an entity owned by
region B **from region A's thread**, while region B's own thread may be
concurrently ticking that exact entity in the same barrier round — a
genuine, reachable, PRE-EXISTING data race, confirmed by reading the code
(this loop is in `explode()`, a completely different, earlier method than
the one Stage 3 already protects). The project owner: "поточна бар'єрна
модель не дає права регіону A мутувати entity, власником якої є B, навіть
якщо всі регіони стартують тик одночасно" (today's barrier model never
gave region A the right to mutate an entity owned by B, even though every
region starts its tick at the same moment) — correctly identifying this
as an ownership violation, not a performance issue, and directing it be
fixed immediately, ahead of continuing Stage 5's own staging.

**Design, per the project owner's explicit direction**: not a lock — the
entity's OWNER is the only thread that ever mutates it, matching this
project's established single-writer discipline for chunk data. A new
`RegionMessage.Type.ENTITY_IMPACT` (payload: the whole triggering
`Explosion` object + the target entity's UUID) is posted to the foreign
entity's owning region instead of applying directly; `RegionThread` gains
`applyPendingEntityImpacts()`, draining and applying this message type as
the first step of every entity round, on that region's own thread — the
entity's damage/knockback (and the exposure raycast that needs its own
live position) are computed FROM SCRATCH by the owner, via a safe
same-thread read, never shipped pre-computed across threads.

**Two real implementation bugs found and fixed during testing, not just
designed away on paper:**

1. **A genuine mod-compatibility regression.** The first version extracted
   the local-entity loop body into a shared helper method (reused by both
   the local and replay paths) — this changed `Explosion.explode()`'s
   bytecode shape enough to break a real mod's Mixin on ATM9
   (`amendments-common.mixins.json:ExplosionMixin`, a cancellable
   knockback hook using MixinExtras local-variable capture at a specific
   point inside that exact method) — the server failed to boot entirely
   (`MixinTransformerError`/`InjectionError`, 0/1 injection succeeded).
   Fixed by keeping the local-entity path byte-for-byte inline in
   `explode()`, unmoved, and giving the replay path (`Explosion
   .nestworldApplyEntityImpact(Entity)`) its OWN independent, deliberately
   duplicated copy of the same maths — a small, intentional code
   duplication chosen specifically to protect third-party mod
   compatibility, confirmed fixed by a clean ATM9 boot afterward.
2. **A genuine new thread-safety bug, caught live, not by inspection.**
   `Explosion`'s two per-detonation caches (`nestworldCache` — block/fluid
   lookup memoization; `nestworldExposureCache` — seenPercent memoization)
   were plain instance fields, explicitly documented as safe because "one
   Explosion = one detonation = one region thread" — an invariant this
   exact fix breaks, since a foreign entity's replay now touches the SAME
   Explosion instance from a SECOND thread, potentially while the
   original thread is still processing its own local entities. Live-
   testing (not review) caught it: `ArrayIndexOutOfBoundsException`
   inside the replay path under a real cross-region TNT flood, logged
   (not crashed — the existing per-message try/catch in
   `applyPendingEntityImpacts()` contained it) but a real correctness bug.
   Fixed by making both caches `ThreadLocal` — restores the original
   "exactly one thread touches this instance" invariant per-thread rather
   than per-Explosion, at trivial memory cost.

**Validated**: a piston-scale unit test wasn't enough here (a single TNT
at typical range produced too little signal to distinguish "worked" from
"too weak to notice") — the decisive test reused Stage 3's own original
validation shape (a TNT cluster straddling a region border with entities
on both sides), scaled up (729-block cube) specifically to force real,
heavy cross-region entity-impact traffic. Both test zombies died (correct
— a 729-block detonation is lethal at that range), and mailbox audit
showed 41,454 sent == 41,454 applied, zero duplicates, zero misrouted,
zero pending once settled, on gen-spike-repro. Same build, same test,
clean on ATM9 (419 mods): 231 sent == 231 applied under organic load, zero
mixin errors, zero crash-reports, TPS 20.0 stable throughout.

**Status**: this P0 is closed. The broader Entity Safety Layer audit
(BoundaryEntityTransfer + ChunkMap tracker fallback NEEDS-SNAPSHOT design;
`EntityGetter`/broad entity queries and world-save entity serialization
still unaudited) remains open, and Step 3 (Single Free-Running Region)
stays paused behind it, per the project owner's explicit direction.

## Entity Safety Layer, continued — both confirmed NEEDS-SNAPSHOT hazards closed (2026-08-10, same session, continued)

The project owner: "Cross-region entity mutation → тільки потік-власник
сутності" — correctly generalizing the P0 fix's own shape (single-writer
ownership, not a lock) into the standing rule this Entity Safety Layer
work is actually enforcing. Directed closing both remaining confirmed
hazards before resuming Step 3, plus a `NO_FOREIGN_ENTITY_MUTATION`
invariant test (not yet built — see below).

### `WorldRegion.EntitySnapshot` — Tier 1 for entities

New mechanism, mirroring the already-proven ghost-zone BlockEntity NBT
pattern exactly: `WorldRegion.EntitySnapshot(x, y, z, xo, yo, zo, removed)`
record, published via an `AtomicReference<Map<UUID, EntitySnapshot>>` by
`RegionThread.publishEntitySnapshot()` — a new step run AFTER
`tickEntities()` completes each entity round, iterating
`region.getOwnedEntityIds()` (the FULL owned set, not just entities
actually processed this round — a budget-deferred entity's unchanged
position is still correctly captured, see below) and capturing a plain-
data copy per entity. Readers on any other thread get a detached,
internally-consistent snapshot (never partially populated — one
`HashMap` built, then published as a single unit), same "up to one cycle
stale" tolerance the ghost-zone snapshot already accepts — though in
practice, since both consumers run strictly AFTER the region round
completes (same tick), the data is typically fresh, not a full cycle
stale.

Gemini-reviewed before implementation (separately for each of the two
consumers below, given very different risk profiles).

### Consumer 1: `BoundaryEntityTransfer` — LOWER risk, fixed first

`processEntity()` restructured: `findOwner(uuid)` (already safe — only
checks `ownedEntityIds` set membership) moved earlier, so the method
knows which snapshot to consult before touching any position field. Owned
entities read x/y/z/xo/yo/zo from the owner's snapshot; unowned entities
(not yet assigned; players are already excluded earlier in the method) —
still safe to read live, since no region thread races an entity nothing
owns. Miss policy (owned but not yet in this cycle's snapshot — a startup
transient): skip this pass, rely on the class's own EXISTING periodic
full-scan safety net — Gemini confirmed this self-heals within one tick,
never permanently misses a real transfer.

Live-validated: a functional cross-region walk test (teleport an entity
across a real region boundary, confirmed via `/nestworld status` entity
counts before/after — ownership moved correctly), plus the same 729-block
TNT-flood regression (38,587 sent == applied on repro; clean ATM9 boot,
228 sent == applied organic load, zero mixin errors after the earlier P0
fix's mod-compat lesson was already learned).

### Consumer 2: `ChunkMap.tick()`'s entity-tracker fallback — HIGHER risk, fixed second, deliberately separate round

Explicitly scoped as its own design/review pass, not bundled with
BoundaryEntityTransfer: this exact optimization ("REGIONALIZED_TRACKER")
has a documented history of real incidents in this project (a "sand
duper" unbounded-broadcast-cost bug, a phase-ordering double-tracking
bug, an "invisible items" client-desync saga) and is this codebase's most
heavily performance-tuned hot path (a parallel-stream variant exists
specifically because profiling found this the dominant per-tick cost at
scale). Three near-duplicate fallback locations needed the same fix: a
parallel-stream detection variant, a serial fallback variant, and the
shared broadcast phase (unchanged — `sendChanges()`'s own SynchedEntityData
handling was already documented thread-safe elsewhere in this class).

**Gemini's first-pass review found the initial design (a per-entity
`WorldGrid.findOwningRegion()` scan, same O(active-regions) helper the
Explosion.java P0 fix already added) was NOT acceptable for this specific
path** — "any non-O(1) operation added to this inner loop will be
problematic at scale" given the file's own history. Required a cheaper
mechanism. Rather than Gemini's own suggested fix (proactively push
cache updates from every ownership-changing call site — `BoundaryEntityTransfer`'s
transfer/pinned-unassign, split/merge's entity handoff, `unassign()` —
easy to miss a spot, exactly the class of bug this path's past incidents
already showed it's prone to), landed on a simpler, self-correcting
alternative: `ChunkMap.TrackedEntity` gets a `nestworldCachedOwner` field
plus `nestworldResolveOwner()` — cache-then-validate (`cached.ownsEntity
(uuid)`, an O(1) `HashSet.contains()`) falling back to the O(active-regions)
scan ONLY on a stale-cache miss, with no hook-based proactive-update code
needed anywhere. O(1) in the overwhelming common case (ownership unchanged
since the entity's last check), self-heals on any staleness without
depending on catching every ownership-change call site.

Miss policy re-examined against Gemini's second concern (silently
"treat as still" risking a repeat of the "invisible items" desync): traced
through the actual mechanism and found the concern doesn't materialize —
`publishEntitySnapshot()` captures the FULL owned set every region tick,
including budget-deferred entities (whose position, correctly, genuinely
didn't change since they weren't ticked) — so a currently-owned entity's
snapshot is essentially ALWAYS present by the time `ChunkMap.tick()`'s
fallback runs (same tick, strictly after the region round). "Missing from
snapshot" is a true rarity (a region that hasn't completed even one tick
yet), not a routine budget-deferral occurrence as first assumed — kept the
original "treat as still, skip this cycle" policy rather than Gemini's
suggested (and rejected) `xo/yo/zo`-direct-read fallback, which would have
silently reintroduced the exact hazard being fixed.

**Validated specifically against this path's own incident history, not
just generic load**: the same 13,175→729-block TNT-flood tests (this
session's standard stress scenario) plus explicit monitoring of the
`REGIONALIZED_TRACKER skip-check` diagnostic THIS project already built
after the original phase-ordering incident — stayed in a healthy range
throughout (repro: 83% once the post-flood entity population stabilized,
explained by a genuinely static test world — NoAI mobs, settled item
drops — producing identical 200-tick-window totals each pass, not a stuck/
regressed count; ATM9: a stable, organically-VARYING ~77% under real
mixed player/mob load, the kind of live variance that would be absent if
tracking were broken). Zero exceptions, zero new crash-reports, 20.0 TPS
throughout, on both servers.

**An operational mistake during this round, corrected, not hidden**: mid-
redeploy, killed the wrong process (`kill -9` on a stale-remembered PID
without re-verifying its `cwd` first) — accidentally terminated the
RUNNING ATM9 server instead of a genuinely stuck gen-spike-repro process
(a separate, already-known "Shutdown Hang JVM" issue, unrelated to this
session's code changes). Caught immediately via the log's own "Killed"
line, disclosed to the project owner in the same turn, both servers
restarted cleanly with `cwd` re-verified before any further destructive
command. No data loss (test servers, no real players), but a real
process lapse worth naming: verify a PID's live identity immediately
before `kill -9`, never rely on an identity check from earlier in the
same turn once enough has happened in between.

### Status

Both confirmed NEEDS-SNAPSHOT hazards from the original audit are closed
and deployed. Remaining, per the project owner's own priority list: a
`NO_FOREIGN_ENTITY_MUTATION` invariant test (instrumentation that fails
loudly if region A ever directly mutates an entity owned by region B —
not yet built) and the two still-unaudited areas from the original fork
pass (`EntityGetter`/broad entity queries; world-save entity
serialization). Step 3 (Single Free-Running Region) remains paused
pending these.

**Update (later same session — see memory `entity-safety-layer-and-
explosion-p0.md` for full detail, kept there rather than duplicated here):**
`EntityOwnershipGuard` (the invariant test) was built and immediately paid
for itself — caught a real, live `Entity.push` collision race (293
violations on ATM9), fixed. The full `EntityGetter`/broad-query audit
(~130 files, not the ~28 originally estimated) found and closed a hot
read-hazard (`getEntityCollisions`) plus a whole "Bucket A" of AOE/attack
mutation hazards via a Gemini-reviewed generic `EntityMutationOp`
primitive, applied across melee-attack goals, potion/AOE effects, and the
projectile hit-chain — all validated live on both servers. World-save
confirmed SAFE today (a genuine barrier), flagged as a Step 3 design
input, not a bug. Bucket B (50+ read-hazard AI files) deliberately
deferred per Gemini's explicit recommendation (cost/risk of touching the
hottest, most-Mixin'd entity accessor methods far outweighs the benefit
of fixing a self-correcting, non-corrupting read hazard) — accepted
residual risk, not a Step 3 blocker. **Entity Safety Layer is now fully
closed; Step 3 has no more known prerequisites from this line of work.**

## Step 3 (Single Free-Running Region) — implemented (2026-08-10/11, autonomous overnight session)

Project owner went to sleep, explicitly authorized autonomous work
("працюй афтономно"). Standing discipline for consequential/uncertain
design decisions (independent review before implementation) applied here
specifically BECAUSE it's the single highest-blast-radius change in the
project — not relaxed just because no one was awake to ask; if anything,
more load-bearing with no one available to catch a bad call mid-flight.

### A new design gap found before any code was written

Re-reading the already-reviewed 6-point Stage 5 architecture (see
"Stage 5 tick-scheduler architecture — DECIDED" above) while planning
Step 3's actual implementation surfaced something that review round
DIDN'T cover: point 3 of that architecture ("mailbox draining becomes the
first step of each region's own free-running local tick") only addresses
CROSS-REGION messages — it says nothing about how a free-running region
still receives its DUE SCHEDULED BLOCK/FLUID TICKS (redstone, farmland,
ice/fire spread…), which are collected once per MAIN-THREAD tick from
vanilla's `LevelTicks` (deliberately kept centralized — architecture
point 2, for good reason) and today dispatched to every region via a
latch-blocking `RegionThreadPool.requestWork()` call the main thread
WAITS on. A free-running region can't wait on that the same way without
re-coupling its pace right back to the main thread — defeating Step 3's
whole point — but it still needs those ticks to happen at roughly the
right cadence or redstone/crops/etc. inside it visibly break.

Traced all 4 call sites that funnel through this (`runScheduledTicksPhase`,
`flushRandomTicksPhase`, block-entity ticks, block events — all via
`RegionThreadPool.runWorkRound`/`runWorkRoundDropIfBacklogged`). Wrote up
the full design (self-paced loop structure, the work-distribution gap and
a proposed fix, the world-save hazard already flagged above, toggle
design) and sent it for independent review before writing any code — same
practice used for every other consequential design this session, treated
as non-negotiable specifically because of the "no one awake to sign off
on a tradeoff mid-implementation" constraint (explicitly asked the
reviewer to weigh in on whether ANYTHING here needed the project owner's
own judgment call before proceeding at all).

**Review verdict**: "sound and implementable... proceed with
implementation tonight" — with two things flagged for extra care during
implementation: (1) the save-rendezvous "safe point" must be precise
(between local ticks, never mid-tick), and (2) the "drop if backlogged"
policy the work-distribution design inherits means a severely-behind
free-running region SILENTLY SKIPS old scheduled ticks rather than
delaying them — an accepted, explicitly-named behavioral-fidelity
tradeoff for THIS validation step (same policy this codebase already
applies elsewhere, `runWorkRoundDropIfBacklogged`, extended to 3 more
call sites that didn't previously use it), not a correctness bug, but
something to observe/document, not silently ship unremarked.

### Implementation

1. **`WorldRegion`**: `freeRunning` (volatile boolean) + `isFreeRunning()`/
   `nestworldSetFreeRunning()`. `pendingFreeRunningWork`
   (`AtomicReference<List<Runnable>>`) — main thread posts, region's own
   loop drains non-blockingly (`nestworldPostFreeRunningWork`/
   `nestworldPollFreeRunningWork`). Save rendezvous
   (`nestworldRequestSaveRendezvous`/`nestworldCheckSaveRendezvous`/
   `nestworldReleaseSaveRendezvous`) — a `CountDownLatch` pair (one for
   "confirmed paused", one for "resume"), written in an order that makes
   the check-side read safe without extra locking (the "paused" flag is
   published LAST by the requester and checked FIRST by the region, so by
   the time the region sees it non-null the resume latch is already there
   too — a standard safe-publication pattern, not accidental).

2. **`RegionThread`**: `run()` branches on `region.isFreeRunning()`
   (gated by `NestworldTuning.FREE_RUNNING_REGIONS_ENABLED` — dead code
   when off) to a new `runFreeRunningTick()` instead of `awaitDispatch()`
   — exactly the extension point the earlier "Part 5a" structural refactor
   was built for. Order inside one free-running iteration, deliberately:
   (1) save-rendezvous check FIRST, the one point guaranteed to be a safe,
   consistent, between-ticks state; (2) drain any pending main-thread work
   batch, non-blocking; (3) the self-paced entity tick itself, gated on a
   `System.nanoTime()` deadline, catches up without rubber-banding if
   badly behind (resyncs to now rather than burning through a backlog of
   missed deadlines back-to-back), parks a short bounded interval (capped
   at 5ms) otherwise so the thread stays responsive to shutdown/toggle-off
   rather than sleeping long stretches. `runOneTick()`'s tail
   `latch.countDown()` made null-safe — a free-running local tick has no
   shared latch (nobody's waiting on it); every barrier-dispatched caller
   still always passes a real latch, unchanged.

3. **`RegionThreadPool`**: `tickAllRegions()` (entity round) and
   `runWorkRound()` (work round; `runWorkRoundDropIfBacklogged` inherits
   this for free since it calls `runWorkRound` internally) both skip a
   free-running region from the latch-dispatch entirely — `runWorkRound`
   posts its bucket via `nestworldPostFreeRunningWork` instead, without
   waiting.

4. **`ServerLevel.save()`**: wrapped the actual chunk/entity save calls
   (not `saveLevelData()` or the Forge save event) in
   `NestworldRegionSystem.nestworldPauseFreeRunningRegionsForSave(this)`
   / `nestworldResumeFreeRunningRegionsAfterSave(paused)` — gathers every
   currently free-running region belonging to the sharded overworld
   (guarded so other dimensions and the disabled-by-default case are
   zero-cost), requests rendezvous, awaits each with a bounded timeout
   (`FREE_RUNNING_SAVE_RENDEZVOUS_TIMEOUT_NANOS`, default 2s) — logs and
   proceeds anyway on a timeout rather than risking hanging the whole
   save indefinitely (a timeout here means that region's save might
   reflect a slightly newer tick than intended, not corruption — its own
   thread is what's writing that data regardless).

5. **Toggle + selection**: `NESTWORLD_FREE_RUNNING_REGIONS=1` (or
   `-Dnestworld.freeRunningRegions=true`) is the hard kill-switch, off by
   default. `/nestworld freerun <id>` toggles free-running for one
   specific region — explicit, observable, command-driven for this
   validation phase rather than an automatic heuristic. `/nestworld
   status` shows `FREE-RUNNING localTick=N (+delta)` for any toggled
   region — `localTickCount` (already existed, Part 1) diverging from
   `server.tickCount` is exactly the signal to watch, now visible.

### A real bug found by testing, not by review (worth naming explicitly)

First live test exposed something neither design review round could have
caught — a low-level execution-ordering detail, not an architectural
question. Toggling a region free-running while its thread was already
blocked inside the OLD barrier-dispatch wait (`RegionThread
.awaitDispatch()`'s `tickSignal.wait()` — the normal, expected state for
any region between main-thread ticks) left it stuck forever: the pool
stops dispatching to a free-running region by design, so nothing was left
to ever wake that `wait()`. Symptom was unambiguous and easy to catch —
`/nestworld status`'s new `localTick=N (+delta)` display, built specifically
to make this class of problem visible, immediately showed the region's
`localTick` frozen (`316`, unmoving) while `server.tickCount` kept
climbing (`delta` going from -153 to -849 across two checks a few seconds
apart) — a clean, unambiguous "this region stopped ticking" signal,
exactly the kind of thing the whole `/nestworld status` line was added to
surface.

**Fix**: `awaitDispatch()`'s wait condition now also breaks when the
region is toggled free-running mid-wait (checked alongside `running` and
`tickLatch`), and `nestworldSetFreeRunning()` explicitly wakes the owning
thread (`RegionThread.nestworldWakeForFreeRunningToggle()`,
`synchronized(tickSignal){ notifyAll(); }` — same pattern `shutdown()`
already uses) so a currently-parked thread doesn't have to wait for
something else to eventually nudge it. `run()`'s loop now distinguishes
"woken because of shutdown" from "woken because of a free-running toggle
with nothing actually dispatched" (both return `null` from
`awaitDispatch()`, but only the first should `break`).

Re-tested after the fix: `localTick` delta went from a frozen/growing gap
to a small CONSTANT offset (`+2`, then `+1` later) that tracked
`server.tickCount` 1:1 as both advanced — exactly the expected healthy
signal (a free-running region under light/no contention should tick at
essentially the same rate as the barrier-synchronized rest of the world,
diverging only when something actually pushes it off pace).

### Validated (gen-spike-repro only, tonight — ATM9 deployment deliberately left for the project owner's own decision)

- **Toggle-off baseline (regression check)**: booted with the feature
  flag entirely absent — behavior byte-for-byte identical to before this
  work (no `FREE-RUNNING` markers, 20 TPS, 0 `EntityOwnershipGuard`
  violations, mailbox `sent==applied`). Confirms the hard kill-switch
  really does make the new code path dead when off.
- **Toggle-on, command gating**: `/nestworld freerun <id>` correctly
  refuses with a clear message when `NESTWORLD_FREE_RUNNING_REGIONS` isn't
  set, matching the `mailboxaudit`/`entityguard` commands' existing
  disabled-feature pattern.
- **Free-running region ticks correctly and stays in sync** (after the
  toggle-wake fix above): `localTick` delta held at a small constant
  offset across multiple checks and after a cross-region stress event —
  never grew, never froze.
- **Cross-region entity mutation across a free-running border**: a 20-TNT
  cluster straddling region #1 (free-running)/region #2 (barrier-
  synchronized)'s shared border, with zombies on both sides — both died
  (lethal at that scale, matches every prior TNT-cluster test this
  session), `EntityOwnershipGuard` held at 0 violations throughout,
  mailbox `sent=161 applied=161, 0 pending/duplicates/misrouted`. The
  cascade-guard chunkLock mechanism (built earlier this session, Part 3)
  worked correctly with a REAL free-running region actually on one side
  of the lock exchange, not just the barrier-synchronized case it was
  originally validated against.
- **Scheduled block ticks reach a free-running region** (design point 2,
  the queue-based work-round distribution): placed an unsupported sand
  block deep inside region #1's territory (`-50,105,-100`, away from any
  border) — confirmed it fell (block became air) via the reliable
  `execute if block ... run setblock <marker>` check pattern established
  earlier this session (`say` doesn't return usable RCON feedback). Proves
  vanilla's scheduled-tick collection-and-dispatch reaches a free-running
  region through the new `WorldRegion.pendingFreeRunningWork` queue, not
  just the entity round.
- **Save rendezvous**: `save-all` completed without hanging or error while
  region #1 was free-running; `localTick` kept advancing normally
  immediately after (no stuck/frozen state, same healthy constant-delta
  signal). **Full round-trip verified**: stopped the server (still
  free-running at shutdown — an additional, slightly different code path
  than the periodic in-game autosave, since shutdown itself triggers a
  save), relaunched, booted clean (no corruption/exception), region #1's
  entity count and the fallen-sand block's state (still air, not reverted
  to sand) both persisted correctly across the restart.
- **Re-toggle in the same session**: turned free-running off then back on
  again later in the same server run — confirmed the toggle isn't a
  one-shot mechanism, works repeatedly.

## ATM9 deployment + Step 4 adversarial testing (2026-08-11, owner authorized "Atm9 можна трогати", then "продовжуй ... працював афтономно")

Deployed the same build to ATM9 (419 mods). Clean boot, 0 Mixin errors.
Toggled free-running on region #6 (deliberately the lowest-activity active
region — narrowest blast radius for the first real-pack test). `localTick`
delta held stable across multiple checks under live organic modded load,
including through an unrelated natural split/merge elsewhere in the tree
(19->18 regions) that didn't affect region #6 at all. 0 `EntityOwnershipGuard`
violations, 20 TPS throughout — first confirmation Step 3 holds up on the
real modpack, not just the clean repro world.

**Step 4 adversarial battery** (both gen-spike-repro and ATM9):
- **Split a free-running region**: no crash/hang. Both children reset to
  barrier-synchronized mode (the `freeRunning` flag is NOT inherited —
  fresh `WorldRegion` objects default to `false`) — confirmed this is
  safe-by-default, not a hazard: the split manager's `chunkLock` acquisition
  (Part 4, already designed with this exact scenario in mind — its own doc
  comment predicted "becomes load-bearing only once regions go free-
  running") correctly serializes against the free-running region's own
  `runOneTick()`, which holds that same lock for its whole tick body.
- **Merge back**: clean, no issues.
- **Rapid toggle stress** (10x on/off back-to-back, both servers): no
  crash, ended in the correct final state, 0 violations — directly
  stresses the toggle-wake fix found and shipped earlier the same night.
- **Save under heavy load**: a 49-TNT cluster detonated INSIDE the free-
  running region's own territory (repro) / `save-all` triggered on ATM9
  under normal organic load — save completed without hanging in both
  cases, zero "did not confirm save rendezvous" timeout log lines, mailbox
  `sent=625 applied=625` (repro), `localTick` delta stayed EXACTLY constant
  across the save (no lag introduced by the rendezvous pause).
- **Chunk-gen spike inside the free-running region** (repro): force-loaded
  24 chunks of unexplored territory within the free-running region's own
  bounds. Server briefly hit the ALREADY-KNOWN, ALREADY-DOCUMENTED
  "Can't keep up" gen-spike class (main-thread chunk-gen budget — see
  [[chunkgen-gc-pressure-bottleneck]]/GEN_SPIKE.md), self-recovered to 20
  TPS as always — NOT a new Step 3 bug. Notably, region #13's `localTick`
  advanced FASTER than `server.tickCount` during the spike (680 local
  ticks vs. 581 server ticks in the same window) — the free-running region
  wasn't dragged down by the main thread's chunk-gen congestion, exactly
  the kind of decoupling benefit Stage 5 is supposed to provide, observed
  live for the first time.

All of the above: zero exceptions in either server's log across the entire
battery, 0 `EntityOwnershipGuard` violations throughout, 20 TPS maintained
on both servers.

**A first, narrow look at Step 5 territory** (repro only, NOT a Step 5
validation pass): the cascade-guard/split-merge lock mechanism is
participant-count-agnostic by construction (ascending-region-ID ordering
doesn't care how many participants are free-running), so tried enabling
TWO adjacent regions free-running simultaneously and flooding their SHARED
border with 20 TNT + zombies on both sides — the new case being a MUTUAL
cascade guard (both sides potentially wanting the other's lock at once,
not one free-running side vs. one parked side). Clean: 0 violations,
mailbox `sent=767 applied=767`, both regions' `localTick` deltas held
exactly constant, no deadlock. Encouraging, but one scenario is not Step
4-level breadth — real Step 5 (more regions, sustained load, split/merge
with multiple free-running regions, proper soak duration, ATM9 too)
remains its own not-yet-started increment.

### Deliberately not done tonight

- **ATM9 deployment** — left entirely for the project owner. This is by
  far the highest-blast-radius change of the whole session; gen-spike-
  repro validation, however clean, is not a substitute for the owner's
  own judgment call about testing this on the 419-mod pack, especially
  overnight with no one able to react to a problem in real time.
- **Extended/adversarial soak testing** (Step 4's own scope) — tonight's
  testing was targeted (specific scenarios chosen to exercise each design
  point) rather than prolonged unattended load. A real soak run (hours,
  varied load, possibly N free-running regions) is Step 4's job, not
  something to rush into the same night Step 3 first went green.
- **Toggling free-running back off before ending the session** — left ON
  for region #1 on gen-spike-repro intentionally, so the project owner
  can inspect the live state directly (`/nestworld status`) rather than
  finding everything already reset when they check in.
