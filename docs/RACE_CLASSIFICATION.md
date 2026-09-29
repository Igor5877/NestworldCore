# Race Classification — third-party mod races under region-parallel ticking

Standing taxonomy for the recurring bug class this project keeps finding: a mod's shared,
non-thread-safe collection or state object, safe under vanilla's single server tick thread,
exposed once NestworldCore's region-parallel ticking lets two regions touch it at once. Every
confirmed race found from here on gets logged in this file using the record format below
**before** a fix is written — the fix class determines the tool (coremod, Mixin, dispatcher
change), not the other way around. See `docs/CONCURRENCY_FINDINGS.md` for the earlier
(2026-06, narrative-style) stress-test findings — those predate this taxonomy and are not
retrofitted into it; this file is where new findings go.

## Fix classes

| Class | Name | What it means | Tooling |
|---|---|---|---|
| **C1** | Concurrent container swap | Writer-vs-iterator (or concurrent-writer) race on ONE collection, no compound check-then-act, no cross-field invariant. Swapping the allocation site for a concurrent-safe equivalent is a complete, correct fix by itself. | `static_collection_race_transformer.js` (generic, config-driven — see below) |
| **C2** | Atomic compound operation | The race isn't the container alone — it's a `containsKey`-then-`put` (or similar) sequence that needs to become one atomic op (`computeIfAbsent`, `compute`, CAS). A plain concurrent container swap alone doesn't fix a torn compound op. | targeted coremod/Mixin per site, not the generic C1 transformer |
| **C3** | Immutable snapshot | The reader needs a consistent point-in-time view while writers keep mutating — a concurrent container removes the crash but can still hand the reader a half-updated, logically-wrong state. Fix is publishing an immutable snapshot (volatile reference swap, copy-on-read) for the reader to consume. | targeted, per-site (needs understanding what "consistent" means for that specific reader) |
| **C4** | Serialized main-thread handoff | The actual business logic (not just a collection) has invariants that must be observed by one thread at a time — mutation queued from any thread, applied serially on main (or one designated owner thread). | dispatcher/adapter-level change, not a coremod |
| **C5** | Region-ownership dispatch | Fix belongs in NestworldCore's OWN region/ownership model, not the mod — e.g. routing the call to the owning region's thread instead of letting it run wherever the caller happens to be. | Core-side dispatcher change |
| **C6** | Lock | A narrow critical section genuinely needs a lock (not a lock-free structure) — usually because the invariant spans multiple fields/collections that must move together. | targeted, per-site; prefer C1-C4 first, C6 is a fallback |
| **C7** | Redesign required | The bug is symptomatic of a design that doesn't have a bolt-on fix — full write-up, no fix attempted until scoped separately. | N/A until scoped |

## Required record format

Every confirmed race gets this block (in this file, and cross-linked from project memory):

```
Race: <Mod> <Class>.<field>
    writers: <call path(s), thread(s)>
    readers: <call path(s), thread(s)>

Vanilla invariant:
    <what single-threaded guarantee the mod's original code relied on>

Why vanilla is safe:
    <why that invariant held true before NestworldCore>

NestWorld violation:
    <what NestworldCore does that breaks the invariant>

Fix class: C<n>

Required guarantee:
    <what the fix must actually guarantee — not just "no more CME">

Live proof:
    <what validates the fix — decompile diff / ASM verify / stress test / not yet run>
```

## C1 transformer — PRODUCTION-READY (2026-09-09)

The original C1 config entries (exdeorum, forestry) cleared the full deployment pipeline
(source proof, bytecode proof, clean startup, targeted reproduction, ownership churn, live
stress with entityGuard, zero CME, zero ownership violations — see each race's own "Live
proof" section below). Per the user's own stated gate: two independent C1 fixes PASS ->
the generic `static_collection_race_transformer.js` mechanism itself is now validated, not
just the two specific fixes. A future C1-shaped race can be added to
`static_collection_race_sites.json` with high confidence the MECHANISM works; each new site
still needs its own source proof + bytecode proof + live stress before shipping (the
mechanism being proven doesn't exempt a new site from proving it's genuinely C1-shaped, per
this file's own scope-discipline section above).

Still NOT deployed to the actual SkyBlock production server -- validated on the
`/root/nestworld-pack-test` rig only. Deploying to SkyBlock is a separate decision.

## The generic C1 transformer

`src/main/resources/coremods/static_collection_race_transformer.js` +
`static_collection_race_sites.json`. One config-driven ASM coremod, not a hand-written coremod
per mod. Each JSON entry names a target class and a list of `{in: <method>, methodDesc:
<descriptor>, kind: <edit kind>, from: <type>, to...}` edits. A site must resolve exactly once
before any copy of the class is edited. Two edit kinds are implemented so far:
- `ctor-swap` — `new X()` → `new Y()` at a `<clinit>` (or any method) allocation site, same
  no-arg constructor shape (e.g. `WeakHashMap` → `ConcurrentHashMap`).
- `ctor-to-factory` — `new X()` → a static factory call with the same net stack effect (e.g.
  `new HashSet<>()` → `ConcurrentHashMap.newKeySet()`).

**Scope discipline**: only C1-class races belong in this file's config. A race that needs an
atomic compound op (C2), a snapshot (C3), main-thread serialization of real logic (C4), region
dispatch (C5), a lock (C6), or a redesign (C7) needs its own fix — adding it to the C1
transformer's JSON would be a silent no-op or, worse, a fix that removes the crash symptom
(`ConcurrentModificationException`) while leaving the actual semantic bug in place. That
substitution — a clean-looking fix that trades a loud crash for silent wrong behavior — is the
single most dangerous outcome available in this whole investigation. Every C1 candidate below
was checked against this before being added to the JSON config.

---

## Confirmed races (2026-09-07 → 09 sweep)

### toms_storage — `StorageTerminalBlockEntity.items` — RECLASSIFIED C4 → C3 (2026-09-10)

```
Race: toms_storage tile.StorageTerminalBlockEntity.items (read via getStacks(), consumed by
      util.TerminalSyncManager.update())
    writer: StorageTerminalBlockEntity.updateServer() -- this.items.clear(); then a stream of
            .forEach(s -> this.items.merge(s, s.getQuantity(), (a,b) -> a+b)) rebuilding from
            the connected itemHandler. Runs on this BE's own tick (region thread, once
            unpinned).
    reader: StorageTerminalMenu.broadcastChanges() (vanilla m_38946_(), main thread, fires
            every tick a menu is open) -> getStacks() returns the LIVE items reference (no
            defensive copy) -> TerminalSyncManager.update() does items.forEach(...) directly
            over that live reference (line 117 of the decompiled source).

Vanilla invariant:
    Only the single server tick thread ever touches items, so clear()+merge() and the menu's
    forEach() could never interleave.

Why vanilla is safe:
    No second thread exists in vanilla to run updateServer() and broadcastChanges() at once.

NestWorld violation:
    Once toms_storage is unpinned, this BE's own tick (region thread) can run updateServer()
    while a different thread (main) is mid-iteration of the exact same live HashMap inside
    TerminalSyncManager.update() -- fail-fast CME risk during clear()+merge(), or, if it
    doesn't throw, a torn/partial view diffed into real "item removed" delta packets sent to
    the client (transient but real, player-visible GUI flicker).

Fix class: C3 (Consistent Snapshot / Atomic Publication) -- corrected from an earlier C4
    classification made during the original static-analysis sweep (see prior contract text,
    preserved below for the record). The reclassification rests on evidence gathered this
    session, not just re-reading the same code differently:

    1. items is a live, unsynchronized HashMap; getStacks() hands out that exact reference
       (return this.items;, no copy) -- confirmed by decompile.
    2. updateServer() mutates it in place (clear() then repeated merge()) -- confirmed.
    3. TerminalSyncManager.update() iterates that live reference from a different execution
       context (main thread vs. the BE's region thread) -- confirmed; this is the whole race.
    4. EntityMutationDispatcher (NestWorld's existing region-dispatch mechanism) does not fit:
       it's a write-side, fire-and-forget mutation-forwarding primitive (a closed enum of ops,
       applied later on the owning region's own thread) with no read/request-response channel
       at all -- confirmed by reading the class in full. Grepped the whole net.nestworld.region
       package for any CompletableFuture-style cross-thread read-with-result pattern: none
       exists. Nothing to reuse here; also nothing new needed, per point 6.
    5. Full mutation-site audit of items, not just the two methods already known: exactly 3
       references to the field inside StorageTerminalBlockEntity itself (the getter, clear(),
       merge()) and exactly ONE call site for getStacks() anywhere in the entire jar
       (StorageTerminalMenu.broadcastChanges(), confirmed by extracting and grepping every
       class file in toms_storage-1.20-1.7.1.jar for "getStacks", not just the two classes
       already read) -- that call site only ever reads the returned map (passes it straight
       into TerminalSyncManager.update(), which itself only reads it: items.forEach(...) and
       this.items.putAll(items) both read FROM the parameter into update()'s own SEPARATE
       cache field, never writing back into it). getStacks() is a pure read view everywhere in
       this mod; nothing anywhere does getStacks().put(...)/.merge()/.remove() externally.
    6. updateServer()'s rebuild is a full re-scan of the itemHandler's actual current state,
       not an incremental sync of one specific triggering action (e.g. one player's pull) --
       the updateItems flag just marks "rebuild wanted," consumed once per BE tick. There is
       no "pull must be reflected in the very next broadcast" contract to preserve; the
       existing design is already "next rebuild reflects whatever the network's true state
       is," already effectively one-tick-latent even in vanilla. This removes the one thing
       that would have forced true C4 (synchronous handoff into the reader's execution
       context) -- a same-tick causal-ordering requirement was the only thing that could have
       kept this C4, and the rebuild's own "full re-scan of authoritative state" shape rules
       it out structurally. (Not re-verified by tracing the actual click/pull interaction
       handler path -- deliberately not chased further, since it isn't load-bearing for this
       conclusion per point 6's reasoning; left as a post-implementation regression check, not
       a blocker on the classification.)

Required guarantee:
    A reader must always observe a fully-built-or-fully-old items map, never a partial one
    mid-rebuild -- exactly what a single atomic reference publish gives (volatile field +
    build a new HashMap locally + one assignment, instead of clear()+merge() in place). No
    cross-thread dispatch/handoff machinery needed; the fix is self-contained inside
    StorageTerminalBlockEntity's own two methods.

Live proof: **SHIPPED (2026-09-10).** Full deployment pipeline PASS on `/root/nestworld-pack-test`:

- Fix implemented as a purpose-built ASM coremod
  (`coremods/tomsstorage_terminal_atomic_publish.js`, own key in `META-INF/coremods.json` --
  deliberately NOT added to the C1-only `static_collection_race_transformer.js`, since this is a
  build-then-swap shape, not a bare container swap). NOT Mixin -- this repo has zero Mixin
  infrastructure at all (confirmed by grep before considering it), so introducing a whole new
  build toolchain for one fix was rejected in favor of staying on the existing ASM-coremod
  mechanism.
- Bytecode proof: independent ASM harness (`ClassNode`/`ClassWriter`, not the JS engine)
  reproduced all 4 edits (add `items_building` field, flip `items` to `volatile`, replace
  `clear()` with a fresh-`HashMap` build, insert the atomic publish unconditionally before both
  branches converge) against the real `toms_storage-1.20-1.7.1.jar`. `CheckClassAdapter.verify`:
  0 issues. Decompiled-diff-confirmed the transformed source matches the design exactly, with
  the atomic publish (`this.items = this.items_building;`) firing on BOTH the
  `invTile != null` and `invTile == null` branches -- catches a real edge case: publishing only
  inside the non-null branch would have left a stale non-empty `items` forever once a connected
  inventory disappeared.
- Clean startup: PASS, coremod log confirmed live twice (`4/4 edit(s) patched`, once with the
  test-only bypass active, once on a final clean restart without it).
- **Targeted reproduction was unusually hard** for this race compared to every other C1/C3 fix
  this session: `updateItems` is ONLY ever set by `getStacks()`, which is ONLY ever called from
  `StorageTerminalMenu.broadcastChanges()` -- neither side of the race can fire at all without a
  genuinely open menu on a connected client (no RCON/NBT shortcut exists, unlike Mekanism's
  passive transporter). Getting a real bot to open the menu hit and required fixing 5 separate
  blockers (`enforce-secure-profile`, a real latent NPE bug in the existing
  `BYPASS_FORGE_HANDSHAKE` mechanism itself -- fixed in source via `genPatches`, not test-only --
  a mineflayer protocol-parsing crash on a modded recipe packet, a mineflayer inventory-plugin
  crash on the custom window type, and a flying-anticheat kick from floating with physics
  disabled) -- see memory `tomsstorage-live-repro-blockers-solved-2026-09-10` for the full
  chain. Once resolved: a real bot sent a genuine interact packet, server accepted it with no
  exception, connection stayed open and stable.
- Ownership churn + live stress (combined, matching this race's actual constraint of needing a
  connected client throughout): 44 cycles over 11 minutes, refilling/varying the connected
  chest's contents every 15s (so `updateServer()`'s rebuild had genuinely changing state to race
  against) + zombie spawn/despawn churn near the structure. **0 exceptions, 0 CME, 0 crash
  reports.**
- `/nestworld entityguard`: `0 violations (NO_FOREIGN_ENTITY_MUTATION holds)`.
- Post-fix structural regression check (re-run against the same verified transformed class):
  `items.clear()`/`items.merge()` -- 0 occurrences; `items_building` used correctly for build +
  merge; exactly ONE publication point (`this.items = this.items_building;`); `items` field
  confirmed `volatile`.
- Test-only exceptions (`-Dnestworld.bypassForgeHandshake=true`,
  `enforce-secure-profile=false`) fully removed after the test -- confirmed via
  `grep -R "bypassForgeHandshake" /root/nestworld-pack-test/` returning 0 matches, and a final
  clean restart without them re-confirmed the fix still fires normally.

**Still not deployed to the actual SkyBlock production server** -- test-rig only, matching the
same discipline as the earlier C1 races. `/nestworld pinmod toms_storage` remains the production
mitigation until a production deploy decision is made separately.

--- superseded original C4 text, kept for the record ---
Previously classified C4 on the theory that "the terminal's view must reflect a real, ordered
sequence of network mutations relative to what the GUI sends the player," which would have
required serializing the mutation path against (or handing it off to) the GUI's own execution
context. Re-examined this session against the actual decompiled contract (point 6 above): the
mod's own design never provided that ordering guarantee even in vanilla, so requiring NestWorld
to invent and enforce it would be solving a problem the mod itself doesn't have.
```

### exdeorum — `VisualUpdateTracker.UPDATES`

```
Race: exdeorum network.VisualUpdateTracker.UPDATES
    writers: sendVisualUpdate(), called from ANY block entity's capability callback
             (a neighboring mod's pipe), any thread
    readers: syncVisualUpdates(), TickEvent.ServerTickEvent, main thread only

Vanilla invariant:
    Nothing else could be mid-write to UPDATES while the tick-event handler iterates it.

Why vanilla is safe:
    Single tick thread; capability calls from a neighbor and the tick-event handler could
    never overlap in time.

NestWorld violation:
    A neighbor's capability call can run on that neighbor's own region thread while the main
    thread is mid-iteration of the same map/sets in syncVisualUpdates().

Fix class: C1

Required guarantee:
    Iteration must never observe a torn/mid-mutation state and must never throw CME — no
    ordering guarantee needed between a given write and the NEXT sync (losing/delaying one
    visual-update packet by a tick is cosmetically harmless, unlike toms_storage's inventory
    view). A weakly-consistent concurrent collection fully satisfies this.

Live proof:
    ASM-verified (ClassWriter.COMPUTE_FRAMES + CheckClassAdapter.verify, 0 issues) and
    decompiled-diff-confirmed against the real exdeorum-1.50.jar. **DEPLOYMENT PIPELINE
    COMPLETE (2026-09-09)**: source proof -> bytecode proof -> installerJar rebuild ->
    clean startup (coremod log confirmed "2/2 allocation site(s) patched" live, on a real
    boot, twice) -> targeted reproduction (a real exdeorum:oak_crucible + hopper, fed
    minecraft:apple every 15s for 24.5 minutes combined across two runs, confirmed via
    /data get block actually melting apples into water the whole time) -> ownership churn
    (continuous zombie swarm spawned near the test structure every 15s, region actively
    split/merged, 19 active regions throughout) -> live stress (15min run1 + 9.5min run2,
    second run with -Dnestworld.entityGuard=true) -> **PASS: 0 ConcurrentModificationException
    across both runs, 0 EntityOwnershipGuard violations ("NO_FOREIGN_ENTITY_MUTATION holds"),
    0 new crash reports.** Test rig: /root/nestworld-pack-test (real 200+ mod pack incl. both
    exdeorum and forestry, neither pinned).
```

### forestry — `TileStreamUpdateTracker.UPDATES`

```
Race: forestry core.blocks.TileStreamUpdateTracker.UPDATES
    writers: TileForestry.sendNetworkUpdate() -> sendVisualUpdate(), incl. bee pollination
             (TreeUtil.tryMate) firing from a pollinating ENTITY's own tick
    readers: ModuleCore.onLevelTick(TickEvent.LevelTickEvent, Phase.END), main thread

Vanilla invariant / Why vanilla is safe / NestWorld violation:
    Identical to exdeorum above -- confirmed by direct decompile (own pass, not just report):
    byte-for-byte the same class shape (WeakHashMap<LevelChunk,Set<BlockPos>> + plain HashSet
    values), same allocation sites, same two methods.

Fix class: C1

Required guarantee:
    Same as exdeorum -- no ordering guarantee needed, weakly-consistent iteration is sufficient.

Live proof:
    ASM-verified (own pass: ClassWriter.COMPUTE_FRAMES + CheckClassAdapter.verify, 0 issues)
    and decompiled-diff-confirmed against the real forestry-1.20.1-2.10.2.jar. Both exdeorum
    and forestry now share one config-driven transformer
    (static_collection_race_transformer.js) instead of two hand-written coremods.
    **DEPLOYMENT PIPELINE COMPLETE (2026-09-09)**, run in parallel with exdeorum's (same test
    session, same server): a real forestry:engine_clockwork, wound via direct NBT
    (`tension: 8.5f`, re-topped every 15s -- confirmed via decompile that `burn()` runs
    unconditionally every server tick for this engine type, decaying tension and calling
    sendNetworkUpdate() on every resulting piston-speed change, with zero dependency on an
    adjacent energy-accepting block). Confirmed live via /data get block that tension was
    actively decaying tick-over-tick (8.5 -> 7.45 in 5s) the whole run. Same ownership-churn
    + live-stress + entityGuard conditions as exdeorum above. **PASS: 0 CME, 0
    EntityOwnershipGuard violations, 0 new crash reports**, across 24.5 combined minutes.
```

### sophisticatedcore — `RecipeHelper$RecipeCache.compactingResults`

```
Race: sophisticatedcore util.RecipeHelper$RecipeCache.compactingResults
    writers: CompactingUpgradeWrapper.onAfterInsert() -- a capability callback, fires on ANY
             insert into ANY compacting-upgrade-equipped storage, any thread

Vanilla invariant:
    Only one insert-and-cache sequence could ever be in flight at a time.

Why vanilla is safe:
    Single tick thread; two different storages could never be inserted into "simultaneously".

NestWorld violation:
    Two compacting-upgrade storages in two different regions, both fed by pipes in the same
    tick, both call containsKey-then-put on the SAME shared serverCache instance concurrently.

Fix class: C2

Required guarantee:
    The check-then-act (containsKey/put) must become one atomic operation
    (computeIfAbsent-shaped) -- a bare concurrent-Map swap alone does not make
    "if (!cache.containsKey(k)) cache.put(k, calculate())" safe; two threads can both pass
    the containsKey check before either puts. Also needs the cached RESULT type itself
    confirmed immutable/thread-safe once published, not just the outer map.

Live proof:
    Fixed from the core side for Sophisticated Core 1.3.40.1925. Cache creation, lookup,
    publication, and invalidation share the RecipeHelper class monitor; listener callbacks run
    after it is released. Consumers copy the cached result stack before mutation and create new
    remaining-item lists. The transformed methods pass ASM verification and a forced
    compute-versus-reload ordering test.
```

### sophisticatedcore — `ServerStorageSoundHandler.worldStorageSoundInfos`

```
Race: sophisticatedcore upgrades.jukebox.ServerStorageSoundHandler.worldStorageSoundInfos
    writers: JukeboxUpgradeWrapper.tick() -> updateKeepAlive()/putSoundInfo()/removeSoundInfo(),
             a storage upgrade's own tick, region-threadable
    readers: ServerStorageSoundHandler.tick(LevelTickEvent, Phase.END), main thread,
             iterates + removeIf's the same nested map directly

Fix class: C1 (tentative -- confirm no compound check-then-act before adding to transformer JSON)

Required guarantee:
    Weakly-consistent iteration sufficient if the write side is simple put/remove; needs one
    more read of updateKeepAlive()'s exact body to rule out a C2-shaped compound op before
    this is safe to add to the generic transformer.

Live proof:
    Not yet fixed.
```

### Mekanism — `TransporterManager.flowingStacks` — FULL CONTRACT RECOVERED (2026-09-10)

Superseded the earlier tentative-C3 entry below. User required the full writers → readers →
vanilla-invariant → consistency-model sequence, with an explicit ban on proposing a fix before
that sequence completed, before touching this race at all -- this section is that sequence's
output. Produced by a dedicated research-only fork (no fix code permitted), then
**independently re-verified by direct decompile** on the single claim the whole conclusion
hinges on (see "Independently re-checked" below) before being trusted.

```
FLOWING_STACKS CONTRACT

Writers:
    TransporterManager.add(Level, TransporterStack) / .remove(Level, TransporterStack) --
    add() does flowingStacks.computeIfAbsent(Coord4D, k -> new ObjectOpenHashSet()).add(stack);
    remove() does flowingStacks.get(Coord4D).remove(stack). Both called from exactly one place,
    TransporterStack.setPath(...), itself called from TransporterStack.recalculatePath()/
    recalculateRRPath()/calculateIdle() -- each of which first calls into
    TransporterPathfinder (the actual pathfinding pass, which READS flowingStacks) and only
    calls setPath() with the result afterward. This whole sequence runs from
    LogisticalTransporterBase's own per-tile tick loop -- under NestworldCore, whichever
    region thread owns that tile's chunk. Two different transporter tiles in two different
    regions can independently reach add()/remove() in the same real-time window; if both
    resolve to the same destination Coord4D, both mutate the same outer-map key (and, for a
    shared destination, the same inner Set) concurrently.
    TransporterManager.reset() (flowingStacks.clear()) is the only other writer --
    **independently confirmed this session (own decompile, not the fork's)**: its one and only
    caller is Mekanism.serverStopped(ServerStoppedEvent), i.e. after the server has already
    stopped ticking. Not a live concurrency concern.

Readers:
    predictFlowing(Coord4D position, ...) -- a SEQUENTIAL SIMULATION, not a snapshot read: for
    the one Coord4D key `position`, it iterates every TransporterStack already flowing there,
    and each iteration's simulateInsert() call depends on the cumulative simulated-inventory
    state left by the PREVIOUS iterations in the same loop. It needs its own iteration over
    THIS ONE KEY's Set to be internally consistent -- it never reads any other key or the
    outer map's overall shape. getPredictedInsert() calls predictFlowing() twice (against the
    global flowingStacks and a caller-supplied additionalFlowingStacks map), both scoped to
    the same single position key. InventoryNetwork.calculateAcceptors() calls
    getPredictedInsert() once per candidate destination BlockPos -- many single-key reads per
    pathfinding pass, never a read spanning multiple keys at once.

Vanilla invariant:
    (1) Within one predictFlowing() call, the Set at that Coord4D key never structurally
        changes mid-iteration.
    (2) Between one stack's recalculatePath()-driven READ (via predictFlowing) and that same
        call's later setPath() WRITE, no other stack's add()/remove() lands in between.

Why vanilla is safe:
    Exactly one thread ever runs any tile's tick, including the whole
    recalculatePath->pathfinding->setPath sequence for one stack -- sequential execution on
    one thread makes both invariants true by construction, no second thread exists to
    interleave into an in-progress prediction-then-commit sequence.

What NestWorld changes:
    Two region threads can now run two different tiles' recalculatePath->predictFlowing->
    setPath->add() sequences concurrently. If both resolve to the same destination, invariant
    (1) can break directly (a concurrent structural mutation mid-iteration) and invariant (2)
    breaks in the TOCTOU sense (thread A's read is stale by the time its own write commits,
    a window that never existed with one thread).

Required consistency:
    Single-key (one Coord4D) internal consistency for the duration of one predictFlowing()
    call. No cross-key/whole-map consistency is ever needed -- confirmed by tracing every
    reader, not assumed.

Is stale data acceptable?
    YES -- traced to the physical consequence, not asserted. Real delivery is a SEPARATE,
    later event: LogisticalTransporterBase's own tick loop does
    `TransitRequest.simple(stack.itemStack).addToInventory(tile, ...)` against the REAL
    destination inventory, entirely independent of predictFlowing()'s earlier simulated
    InventoryInfo. **Independently re-verified this session** (own decompile of
    LogisticalTransporterBase.java, lines ~167-181, not just the fork's report): if the real
    insert is fully accepted, TransporterManager.remove() fires and the stack is deleted
    normally; if PARTIALLY rejected, `stack.itemStack = rejected` (shrinks to the unaccepted
    remainder, does NOT delete the stack) and falls straight into
    `this.recalculate(stackId, stack, prevSet)` to compute a new path. A stale/wrong
    prediction therefore never duplicates or silently loses an item -- worst case is a
    partial delivery that gets rerouted, the exact mechanism Mekanism already relies on for
    its own ordinary multi-tick transit staleness (an item predicted-accepted at tick N can
    find the destination already full by the time it physically arrives, purely from elapsed
    real time, with zero threading involved -- this fallback path is not new, concurrency
    just adds one more way to reach it). What is NOT survivable is a torn/corrupted
    ITERATION (CME, or silent fastutil bucket corruption) -- a distinct failure mode from
    "stale but structurally intact," and nothing in the reject/reroute logic protects
    against that; only against staleness.

Can ConcurrentHashMap preserve the invariant?
    YES for invariant (1) (all that's actually required): a weakly-consistent concurrent map
    (outer) + weakly-consistent concurrent set (per-key value) makes predictFlowing()'s loop
    immune to CME and immune to bucket corruption under concurrent add()/remove(), by
    construction. It does NOT restore invariant (2) (the TOCTOU window) -- but per the
    stale-data finding above, invariant (2) does not need restoring: the delivery-time
    reject/reroute path already exists specifically to absorb a prediction that turned out
    wrong, and a concurrency-induced staleness window is indistinguishable, from that
    fallback code's point of view, from the ordinary multi-tick staleness Mekanism already
    tolerates by design.

Recommended fix class:
    C1 -- concurrent container swap (outer map AND the per-key value Set both need to become
    concurrent-safe types -- a partial fix swapping only one of the two would leave the other
    exposed) is a complete, correct fix. No compound atomic op beyond a concurrent map's own
    computeIfAbsent is needed (single-key, not cross-key), no snapshot, no
    serialization/dispatch/lock. This conclusion rests entirely on the delivery-time
    reject/reroute code -- now independently confirmed twice (fork + this session's own
    decompile), not resting on the fork's word alone.

UNKNOWN:
    - Whether NestworldCore's region model can split a SINGLE Mekanism network's own tiles
      across two regions/threads (as opposed to two separate networks racing on a shared
      destination) was not re-derived from NestworldCore's own region-assignment code this
      pass. Architecturally plausible given regions are chunk-based and player-built pipe
      networks routinely span many chunks (the same precondition behind every other race
      found this session) -- treated as a real possibility, not confirmed with a citation.
    - additionalFlowingStacks' real-world producer/purpose (who constructs it, what scenario
      populates it) was not traced to its origin -- confirmed it's read the same single-key
      way as flowingStacks, not confirmed whether it has its own separate thread-safety
      profile as a per-call-constructed map.
```

**Independently re-checked this session (not just accepted from the fork):**
1. `LogisticalTransporterBase.java` lines ~167-181 (decompiled directly, own pass) — confirms
   the reject/reroute fallback exactly as the fork described. This is the single claim the
   entire "stale data acceptable" conclusion depends on.
2. `TransporterManager.reset()`'s only caller — confirmed via `javap -c` bytecode search
   across every class in the jar referencing `TransporterManager` (not just the fork's
   narrower trace): `mekanism.common.Mekanism.serverStopped(ServerStoppedEvent)`, i.e.
   post-shutdown only. Resolves one of the fork's own flagged UNKNOWNs.

**Status: SHIPPED, deployment pipeline PASS (2026-09-10).**

Before adding to the config, the nested two-level allocation shape was checked against the
transformer's actual capabilities (not assumed): `<clinit>` allocates the outer map as a plain
`NEW it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap; DUP; INVOKESPECIAL <init>()V`, and
the inner `computeIfAbsent` lambda desugars to its own synthetic method `lambda$add$0` (NOT
inline in `add()`) containing the exact same `NEW/DUP/INVOKESPECIAL` shape for
`ObjectOpenHashSet`. Both are plain no-arg `()V` constructors. `static_collection_race_transformer.js`
matches on allocated-class name and method name with no filter excluding synthetic/lambda
methods, so both sites are patchable with the existing two edit kinds -- **zero changes to the
transformer engine needed.** (A dispatched fork did this verification first; independently
re-confirmed myself via `javap -p -c` on the real jar before trusting it, since this was the
load-bearing claim for the whole "existing mechanism suffices" conclusion.)

Config entry added to `static_collection_race_sites.json` (mod: mekanism, class:
`mekanism/common/content/transporter/TransporterManager`):
- `<clinit>` ctor-swap: `Object2ObjectOpenHashMap` -> `ConcurrentHashMap`
- `lambda$add$0` ctor-to-factory: `ObjectOpenHashSet` -> `ConcurrentHashMap.newKeySet()`

**Full deployment pipeline run on `/root/nestworld-pack-test`, PASS on every criterion:**
- Bytecode proof: independent ASM harness (not the JS engine) reproduced both edits with plain
  `ClassWriter`/`ClassNode`, ran `CheckClassAdapter.verify` with the real classpath (deobfuscated
  Minecraft + Mekanism + fastutil jars) -- 0 issues.
- Coremod log (real pipeline, real jar): `static-collection race fix applied to
  mekanism/common/content/transporter/TransporterManager (mekanism): 2/2 allocation site(s)
  patched` -- fired from `NestWorld-Region-100` (a region thread, not main), confirming the
  class loads under genuine region-parallel execution.
- Clean startup: PASS, no VerifyError/LinkageError/ClassCastException.
- Targeted reproduction: two real `mekanism:basic_logistical_transporter` setups built in two
  different active regions (#100, #99), each with a chest configured via the transporter's own
  `connection<N>` NBT field (found via decompiling `Transmitter.write()`/`read()`, not guessed)
  set to `PULL` -- genuine `TransporterManager.add()` activity, not simulated.
- Ownership churn: 52 cycles over 13 minutes, refilling both source chests + spawning/despawning
  zombies near both structures every 15s to force region-boundary crossings. 0 violations.
- Live stress: 13 minutes, 0 exceptions, 0 crash reports, 0 CME, transporter state coherent
  throughout (items still correctly in-flight at different progress values at the end, no
  stuck/duplicated stacks).
- `/nestworld entityguard` final check: `0 violations (NO_FOREIGN_ENTITY_MUTATION holds)`.

Mekanism was temporarily unpinned (main-thread pin) for this test only, since it was pinned for
*other*, still-open Mekanism races (`PathfinderCache`, `FrequencyManager`) -- leaving it pinned
would have prevented the region-thread contention this test needed to exercise, producing a
false pass. Re-pinned immediately after the stress run; Mekanism stays pinned overall until its
remaining races are closed too. `additionalFlowingStacks` (separate `Map` parameter of
`getPredictedInsert`, not this field) intentionally left UNKNOWN, out of scope for this fix.

### Mekanism — `PathfinderCache.cachedPaths`

```
Race: Mekanism content.transporter.PathfinderCache.cachedPaths
    writers: TransporterPathfinder.getPath() -> getCache()/addCachedPath(), from
             TransporterStack.recalculatePath(), a transporter's own per-tick pathing

Fix class: C2 -- computeIfAbsent-shaped per-network cache; also needs the cached
    value type (Map<PathData,CachedPath> per network) checked for its own thread-safety
    separately from the outer map, and an explicit invalidation-safety check (does removing a
    stale path race a concurrent read of it?).

Live proof:
    Fixed from the core side for Mekanism 10.4.16.80. The transformer puts get/add/remove/reset
    behind the same class monitor. The real transformed methods pass ASM verification and a
    concurrent add/invalidate test followed by 1,000 deterministic cache hits.
```

### Mekanism — `FrequencyManager.frequencies`

```
Race: Mekanism lib.frequency.FrequencyManager.frequencies
    writers: TileComponentFrequency.tickServer() -> validateAndUpdate(), region-threadable
             (Teleporters, QIO, Quantum Entangloporter)
    readers: CommonWorldTickHandler.onTick -> FrequencyManager.tick() -> tickSelf(), main thread

Fix class: C4 (tentative) -- frequency systems typically have a register/unregister/lookup/
    tick/remove invariant set that needs one consistent view across all five operations, not
    just safe individual map mutations. Likely needs a mutation queue drained on main by the
    FrequencyManager itself, not a container swap.

Live proof:
    Not yet fixed. Needs FrequencyManager's full register/unregister/lookup contract read
    before committing to a fix class.
```

### Draconic Evolution — `CrystalUpdateBatcher.batchQue`

```
Race: Draconic Evolution network.CrystalUpdateBatcher.batchQue
    writers: ENetFXHandlerServer.queData(), tied to a crystal tile's own tick, region-threadable,
             fans out to every tracking player
    readers: DEEventHandler.serverTick(ServerTickEvent) -> tickEnd(), main thread,
             iterates then clears

Fix class: C3 for the outer HashMap<ServerPlayer,List<...>> access pattern and per-player
    List<BatchedCrystalUpdate> value also needs to become concurrent-safe AND the
    iterate-then-clear needs to become an atomic drain (e.g. swap in a fresh empty list per
    player and hand back the old one in one step, not two) -- a straight ConcurrentHashMap
    swap on the outer map alone does not fix the inner List race or the non-atomic drain.
    Implemented as atomic batch detachment; do not add to the plain C1 transformer.

Required guarantee:
    Every enqueued update gets delivered exactly once, no update lost or duplicated across a
    concurrent enqueue-during-drain window.

Live proof:
    Fixed from the core side for 3.1.2.621. Regression coverage verifies 20,000 concurrent
    updates with no loss, duplicate, or packet-count mismatch, plus restore after send failure.
```

### Draconic Evolution — `CrystalUpdateBatcher.ID_CRYSTAL_MAP`

```
Race: Draconic Evolution network.CrystalUpdateBatcher.ID_CRYSTAL_MAP
    writers: unguarded containsKey+put/remove directly in TileCrystalBase's load/unload
             lifecycle -- concurrent WRITERS if two crystal tiles in different regions
             load/unload the same tick

Fix class: C2. The registry allocation becomes ConcurrentHashMap, registration becomes
    putIfAbsent, and unload becomes remove(hash, position). The conditional removal is required:
    an old tile unloading after a hash collision must not remove the position that actually won
    registration.

Live proof:
    Fixed from the core side for 3.1.2.621. The real transformed classes pass ASM BasicVerifier.
    Regression coverage verifies collision ownership and 10,000 concurrent load/unload pairs
    without lost registrations or stale entries. Clean-server stress is still pending.
```

### AE2 — `GridEventBus.EVENTS`

```
Race: appliedenergistics2 me.GridEventBus.EVENTS
    writers: postEvent(), called from plain per-node methods on ANY AE2 machine's own tick
             (GridNode.setIdlePowerUsage(), ControllerBlockEntity, EnergyCellBlockEntity,
             ChestBlockEntity confirmed callers) -- NOT confined to AE2's own serialized
             TickHandler loop

Fix class: C1. Bytecode inspection resolved the reentrancy question: getSubscriptions()
    completes computeIfAbsent and returns before Subscriptions.invoke() calls any listener.
    Replacing only EVENTS' HashMap allocation with ConcurrentHashMap therefore protects
    concurrent first publication without holding a map operation across mod callbacks.

Live proof:
    Fixed from the core side for AE2 15.4.10. The transformer resolves exactly one field-bound
    allocation in the real jar, rejects missing/duplicate shapes without partial edits, and the
    transformed class passes ASM BasicVerifier. Clean-server stress is still pending.
```

### Flux Networks — `ServerFluxNetwork.mToAdd` / `mToRemove` / `mConnectionMap`

```
Race: FluxNetworks connection.ServerFluxNetwork.{mToAdd,mToRemove,mConnectionMap}
    writers: TileFluxDevice.onServerTick() -> enqueueConnectionAddition()/
             enqueueConnectionRemoval(), region-threadable
    readers: onEndServerTick() -> handleConnectionQueue(), main thread

Vanilla invariant / Why vanilla is safe:
    The author's own enqueue-now/drain-at-tick-end pattern is a REAL concurrency guard --
    just one that only covers races within one vanilla tick thread (e.g. not mutating a
    device list mid-iteration in the same thread's same tick).

NestWorld violation:
    A genuinely different thread (a region thread) can call the enqueue methods concurrently
    with the main thread's drain -- a case the author's own guard was never built to cover.

Fix class: C6 because queue cancellation and the connection map form one lifecycle invariant;
    a container-only replacement does not preserve it.

Live proof:
    Fixed from the core side for 7.2.1.15. Regression coverage exercises concurrent registration,
    stable reader snapshots, unloaded phantom replacement, deletion, and callbacks outside locks.
```

### industrial-foregoing — `ExplosionTickHandler.processExplosionList` / `removalProcessList`

```
Race: industrial-foregoing utils.explosion.ExplosionTickHandler.{processExplosionList,removalProcessList}
    writers: InfinityNukeEntity.tick(), region-threadable entity tick
    readers: ExplosionTickHandler.serverTick(ServerTickEvent), main thread, iterates+removes

Fix class: C1, plain active ArrayList -> CopyOnWriteArrayList. These are persistent active-process
    lists, so an atomic drain would incorrectly discard live processes after one tick.

Live proof:
    Fixed from the core side for 3.5.22. Regression coverage verifies persistence, dead-process
    removal, phase behavior, and concurrent registration.
```

### Vanilla Minecraft (Core) — `Scoreboard` + `SimpleCriterionTrigger.players` — unowned global singletons (NEW, 2026-09-28, NOT YET FIXED)

Found via static analysis during unrelated Mod API primitive-boundary work
(`docs/NESTWORLD_API_ARCHITECTURE.md`, "API-1A.4 — Primitive Boundary Inventory"), not from a
stress-test sweep like every entry above. **Different in kind from every race above this line**:
every prior entry is a THIRD-PARTY MOD's shared state, and every hazard has TWO identifiable
owners (e.g. `awardKillScore`'s target vs. actor, ride-linkage's mount vs. rider) — region-
ownership dispatch (Fix class C5) is at least conceptually available because there is always a
right region to route the call to. This entry is neither: the racing state is VANILLA
Minecraft's own code, and the structures are GLOBAL, SERVER-WIDE SINGLETONS with no owning
region at all — N region threads write into ONE unowned structure, not two threads racing over
which of two owners should win. Filed here per the audit's own recommendation since the
underlying bug mechanism (single-tick-thread invariant broken by region-parallel ticking) is the
same one this file already tracks. Tagged `GLOBAL_SHARED_UNOWNED` to name this distinct shape —
not a replacement for the C1-C7 fix-class axis (a fix class still applies once one is chosen),
but a note that **C5 (region-ownership dispatch) is categorically unavailable here**, unlike
every mod race above.

```
Race: vanilla Scoreboard (server-wide singleton) + SimpleCriterionTrigger.players (per-trigger-
      type singleton, one instance per registered advancement-criterion type)
    writers:
      - ServerPlayer.awardStat(Stat, int) (ServerPlayer.java:1054-1058) and resetStat
        (ServerPlayer.java:1061-1064) call this.getScoreboard().forAllObjectives(...), which
        resolves to MinecraftServer.getScoreboard() (MinecraftServer.java:1637-1639) returning
        the ONE `private final ServerScoreboard scoreboard = new ServerScoreboard(this);` field
        (MinecraftServer.java:232) — one instance for the whole server, not per-dimension/per-
        region. Scoreboard.forAllObjectives() (Scoreboard.java:65-69) calls
        getOrCreatePlayerScore() (Scoreboard.java:81-90), a COMPOUND nested
        playerScores.computeIfAbsent(...).computeIfAbsent(...) over plain `Maps.newHashMap()`
        fields (objectivesByCriteria line 30, playerScores line 31 — plain HashMap, not
        ConcurrentHashMap, not synchronized; confirmed by reading the field declarations
        directly, Scoreboard.java:29-34). Fires from whichever region thread currently owns the
        awarding player — every player-tick/kill/craft/sleep/drop path that calls awardStat
        (ServerPlayer.java:566,609,643,646,662,885,1553,1556), i.e. any region, concurrently,
        any tick.
      - SimpleCriterionTrigger.addPlayerListener/removePlayerListener
        (SimpleCriterionTrigger.java:19-34) do compound computeIfAbsent(...).add(...) and
        get()-then-remove()-then-conditional-remove() over
        `private final Map<PlayerAdvancements, Set<Listener<T>>> players =
        Maps.newIdentityHashMap();` (SimpleCriterionTrigger.java:17 — plain IdentityHashMap, not
        thread-safe). One SimpleCriterionTrigger subclass instance exists PER trigger type
        (CriteriaTriggers registers dozens as static singletons), each with its own `players`
        map shared by every player on the server for that one criterion type.
    readers:
      SimpleCriterionTrigger.trigger() (SimpleCriterionTrigger.java:47-72) does
      this.players.get(playeradvancements) then iterates the returned Set directly (no
      defensive copy) — fires from CriteriaTriggers.*.trigger(player, ...), called from
      wherever that player's own game-logic path currently executes (region thread).

Vanilla invariant:
    Only the single server tick thread ever calls awardStat/resetStat/trigger for any player, so
    the compound computeIfAbsent chains on Scoreboard's HashMaps and the add/remove/iterate
    sequence on SimpleCriterionTrigger's IdentityHashMap could never interleave with each other
    or with themselves.

Why vanilla is safe:
    No second thread exists in vanilla to run two players' awardStat/trigger calls (or a
    register/unregister and a fire) at the same real-time instant.

NestWorld violation:
    Under region-parallel ticking, Player A's stat/criterion path can run on Region A's thread
    at the same instant Player B's runs on Region B's thread. Both go through the SAME single
    MinecraftServer.scoreboard instance and the SAME per-trigger-type players map — unlike every
    other race in this file, there is no "owning region" for either structure to route through;
    every region thread has equal, simultaneous, legitimate reason to touch them.

Fix class: NOT YET DETERMINED (tentative only — no full contract trace done yet, matching this
    file's own precedent for FrequencyManager above: "needs full register/lookup contract read
    before committing to a fix class"). C5 is ruled out categorically (no owning region exists
    for either structure). C2 (atomic compound op) is the closest fit for the nested
    computeIfAbsent chains and the addPlayerListener/removePlayerListener compound ops, but
    Scoreboard alone has 5 interacting HashMap fields (objectivesByName, objectivesByCriteria,
    playerScores, teamsByName, teamsByPlayer — Scoreboard.java:29-34) with team-assignment/
    score-lookup cross-field invariants not traced this pass — C6 (lock) may be required instead
    of a bare per-map compound-op fix if those invariants turn out to span fields. Not evaluated
    deeply enough yet to commit either way.

Required guarantee:
    Not yet derived — needs the same writers→readers→vanilla-invariant→consistency-model
    sequence this file required for Mekanism's flowingStacks before any fix class is finalized.

Live proof:
    Not yet run. This entry is STATIC ANALYSIS ONLY — found while tracing awardStat/
    CriteriaTriggers for an unrelated Mod API primitive-boundary audit (API-1A.4), not
    reproduced under load, not stress-tested, not mitigated. No existing NestworldCore mechanism
    touches either structure today (grep -rn "Scoreboard\|CriterionTrigger\|awardStat"
    src/main/java/net/nestworld/ and src/main/resources/coremods/ — zero matches, confirmed
    this pass). **Explicitly NOT blocking the separate NestWorldEntities.setVelocity Mod API
    primitive work** (API-1A's actual recommended first primitive) — different, unrelated
    subsystem (entity velocity vs. global stat/scoreboard singletons). Filed here for
    tracking/future-prioritization only, per the source audit's own recommendation; no fix
    attempted, no priority decision made.
```

---

## Priority order (user-set, 2026-09-09)

**P0 — real production risk, needs semantic design work before any fix:**
1. Mekanism `TransporterManager.flowingStacks` (global scope)
2. toms_storage `TerminalSyncManager` (already a confirmed live crash)
3. sophisticatedcore `RecipeCache.compactingResults` (concurrent writers, corruption risk)
4. Mekanism `PathfinderCache.cachedPaths`

**P1 — mechanical, well-isolated, C1-shaped:**
5. exdeorum `VisualUpdateTracker` — DONE, in generic transformer
6. forestry `TileStreamUpdateTracker` — DONE, in generic transformer
7. Draconic `CrystalUpdateBatcher.batchQue` — DONE, atomic detached batches
8. industrial-foregoing `ExplosionTickHandler` — DONE
9. Flux Networks queues — DONE

**P2 — needs semantic/design work:**
10. Mekanism `FrequencyManager.frequencies`
11. AE2 `GridEventBus.EVENTS` — DONE, pending clean-server stress
12. Draconic `CrystalUpdateBatcher.ID_CRYSTAL_MAP` — DONE, pending clean-server stress
13. sophisticatedcore `ServerStorageSoundHandler.worldStorageSoundInfos`

**Untriaged — new hazard shape, not yet assigned a P0-P2 bucket (2026-09-28):**
14. Vanilla `Scoreboard` + `SimpleCriterionTrigger.players` (`GLOBAL_SHARED_UNOWNED`) — found via
    static analysis, not yet fix-classified, not yet prioritized against the mod races above; see
    its own entry above for why it doesn't cleanly fit the P0-P2 mod-race ordering (no owning
    region to route through, unlike every item 1-13).
15. `RegionThread.shutdown()` does not drain in-flight `EntityMutationDispatcher` mailbox
    messages before exiting (`RegionThread.java:236-239,251-253`,
    `NestworldDimensionRegion.java:1125-1130` — `shutdown()` sets `running=false` and notifies,
    `NestworldDimensionRegion.shutdown()` calls `saveLayout()` then `thread.shutdown()` per
    thread, with no drain/apply/log step anywhere in that path). Not a data race in the usual
    sense — no two threads touch the same memory concurrently here — but a confirmed **runtime
    reliability gap**: any queued mutation of ANY `EntityMutationDispatcher` op type
    (`Damage`, `Ignite`, a future `SetVelocity`, etc.) still sitting in a region's mailbox at
    shutdown is silently dropped, with no crash and no log. Found 2026-09-28 during the
    `setVelocity` (API-1A.5) contract verification pass — confirmed pre-existing, Core-wide,
    not introduced by that work. `docs/contracts/SetVelocityPrimitive.md` already documents this
    as an accepted best-effort/at-most-once delivery characteristic for that one primitive; this
    entry exists so the underlying dispatcher-wide debt has its own tracked record, independent
    of any single primitive's contract, and doesn't get treated as fixed the day one contract
    stops mentioning it. NOT blocking any current API-1A work. No fix scheduled.

## Deployment discipline (standing rule, not per-fix)

No coremod/fix from this file ships without, in order: source proof (own decompile, not just
a report) → bytecode proof (ASM `CheckClassAdapter.verify` clean) → clean startup → a
targeted reproduction of the original race → an ownership-churn/region-split stress test →
10-15 minutes of live stress with zero new violations → only then deployment. Never batch-ship
every confirmed fix at once. `ConcurrentHashMap` removing a `ConcurrentModificationException`
is not proof the underlying race is actually fixed at the semantic level — see Mekanism
`TransporterManager` above for exactly why that distinction matters.
