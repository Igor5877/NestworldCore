# API-1B-CLOSURE — integrated server and multi-server lifecycle in one JVM

**Status (2026-10-02): lifecycle matrix RUN.** API-1B semantics PASS on a real integrated server
and across two sequential servers in one JVM. Two product defects were found and fixed (W1, W2).

**API-1B is NOT yet marked CLOSED/FROZEN.** Two verified findings are open and need an owner
decision:
- **MODULE-LIFECYCLE:** blocks any second world in a client.
- **CORE-PARKED-REGION-001:** delivery liveness; per the owner's rule it comes before PERF-1.

Invariant under test:
`ServerLifetime(A) ∩ MutableRuntimeState(ServerLifetime(B)) = ∅`. Statics need not be physically
null, but no mutable state of A may influence the semantics of B.

## Harness
- **Real production client.** Installed with the NestWorld installer (`--installClient`) and
  launched headless (portablemc, Xvfb + Mesa llvmpipe, offline user). SRG runtime, jar-in-jar Mod
  API provider, ABI gate PASS.
- **Driver.** `net.nestworld.client.ClientLifecycleTestDriver` is test-only. It runs only on the
  client dist and only with `-Dnestworld.clientLifecycleTest=true`. It drives:
  1. title screen;
  2. create world A;
  3. phase A on the IntegratedServer;
  4. quit to title (server A fully stopped; `clearLevel` waits for `isShutdown`);
  5. **boundary snapshot**;
  6. hold at title (`titleHoldMs`, 100 s in the evidence runs);
  7. create world B;
  8. phase B;
  9. quit;
  10. `Minecraft.stop()`.
- **Server side.** `IntegratedLifecycleTests` reuses the API-1B fixtures. Respawn actions are
  served in-JVM: kill the player, with `doImmediateRespawn` on.
- **Relogin/offline fixtures** cannot run in single-player, because logging out ends the
  integrated server. The respawn transition is the in-world ServerPlayer removal; the world stop is
  the logout.
- **Boundary snapshot.** It reads every JVM-global API-1A/1B and region-runtime state (reflection,
  test-only), classified as:
  - **SEMANTIC** (must be empty/null);
  - **CONFIG** (operator opt-ins);
  - **METRIC**.

  It also lists live threads (region threads, forced-exit watchdog).
- **Leak check.** Weak references to A's server, region system and player must clear after GC
  while B runs. On failure a heap dump is taken and analysed with Eclipse MAT (`path2gc`).

## Results (final build 1.20.1-47.4.249, regenerated vanilla tree, see W3)

**Phase A**, on `net.minecraft.client.server.IntegratedServer`, with the region system active:

| setSlot fixture | Result |
|---|---|
| snapshot isolation (A) | PASS |
| equals trap (C-SIM) | PASS |
| worker direct / routed (foreign thread) | PASS / PASS |
| respawn direct / routed | PASS / PASS |
| cross-boundary direct / routed (V1000 vs V1) | PASS / PASS |
| publication window, respawn routed | PASS |
| slots, invalid, reroute limit, generation | PASS |
| **ordering (B), LATEST_INTENT_WINS** | **FAIL on liveness; semantics held.** V2 applied once, V1 later STALE_INTENT_DROPPED, final = diamond. V1 was not delivered within 5 s: see CORE-PARKED-REGION-001 |
| A reaches version 1000 for (UUID, slot 7) | PASS |

**Boundary** (A fully stopped, B not started):

| State | Kind | Result |
|---|---|---|
| `NestworldRegionSystem.INSTANCE`; Velocity/Inventory backend + `installedFor` | semantic | null — PASS |
| SetVelocityIntentTracker and InventorySlotVersionTracker counters / lastApplied; PlayerIncarnationRegistry | semantic | empty — PASS |
| MailboxLifecycle orphans / retired registry / exit watch; MailboxAudit in-flight | semantic | empty — PASS |
| ModCallbackRouter, Chunk/PairingOutboundQueue, Connection/PairingAdmissionGate, PlayerInputDiagnostics | semantic | empty — PASS |
| test hooks | semantic | disarmed — PASS |
| region threads alive | semantic | none — PASS |
| forced-exit watchdog thread | semantic | **none after W1** (before: alive → JVM halt) |
| **`ModuleRegistry.MODULES` / `FAILURES`** | semantic | **2 / 1 — FAIL (MODULE-LIFECYCLE)** |
| experiment opt-ins | config | 0 (UUID-keyed; would carry into B for the same UUID if set) |
| RegionTimeCompatibility EMA, SplitMergeEventLog, telemetry | metric | reported |

**Phase B** (after the harness module-registry workaround, see below):

| Check | Result |
|---|---|
| new server object; new region system | PASS |
| both backends `installedFor` = B; new inventory backend object | PASS |
| same UUID, new ServerPlayer object; fresh incarnation token (8 → 9) | PASS |
| no domain from A (`lastApplied(A)` = null, `domainCount` = 0) | PASS |
| **B's first setSlot on the same (UUID, slot) is version 1 and applies** (A had reached 1000) | **PASS** |
| setVelocity counter for the same (dimension, UUID) starts fresh (0 → 1); setVelocity applies | PASS |
| fixtures: snapshot, ordering, respawn-routed, crossboundary-routed, worker-routed | PASS |
| server A, region system A, player A garbage-collected | **PASS after W2** (before: FAIL) |
| client JVM alive after server A stopped | 124 s, normal exit |

Final run: 55 pass, 3 fail. The 3 failures are ordering (CORE-PARKED-REGION-001) and the two
`ModuleRegistry` checks (MODULE-LIFECYCLE).

## Defects fixed in this gate

**W1 — The forced-exit watchdog halted the client JVM.**
- `MinecraftServer` armed `NestworldShutdownWatchdog` (`Runtime.halt(1)` after 90 s) on every
  graceful or crash stop. An integrated server stops while its client deliberately keeps running.
- Reproduced: the client halted exactly 90 000 ms after leaving world A, at the title screen.
- Fix: arm only if `isDedicatedServer()`. Dedicated behaviour is unchanged. After the fix the
  client survived 123–130 s at the title screen in every run.

**W2 — Main-thread identities outlived their server.**
- `NestworldRegionSystem.nestworldMainThreadIdentity` and `NestworldTickOwnership.ownerThread` are
  static and were never reset. Server A's dead `Server thread` kept its run lambda
  (`Thread.FieldHolder.task`), and through it IntegratedServer A and its whole world, for the life
  of the JVM (MAT `path2gc`, two separate paths).
- In about 30 s of world B the stale identities caused:
  - 4 638 false LevelTicks ownership-violation reports (a thrown exception on B's main thread in
    STRICT mode);
  - 180 per-tick ERRORs;
  - 882 chunk-load ERRORs with stack capture.
- Fix: reset both on `ServerStoppedEvent`. `ServerStoppingEvent` is too early: `stopServer()` still
  unloads chunks through LevelTicks on the old thread and re-captured it, which was reproduced.
- After the fix: 0 / 0 / 0, and A's objects are collected.

**W3 — Build provenance: the generated vanilla source tree was stale (recorded; nothing to commit).**
- `projects/forge/src/main/java` had last been extracted on 2026-08-31 / 2026-09-03.
- Committed patches from `a7550a80f` (2026-09-22) and `8733411c8` (2026-09-23) for Entity,
  ChunkMap, ServerChunkCache and TheEndGateway were therefore absent from every build compiled here
  since then.
- The tree was backed up and regenerated (`:forge:extractMapped`). A `genPatches` round trip now
  differs only by this gate's MinecraftServer change. One remaining difference in
  TheEndGatewayBlockEntity is a hunk re-layout of identical content; the committed patch was kept.
- All final evidence of this gate, and the regression below, is on the regenerated tree.

**Test-only defects:**
- The CORE-MAILBOX fixture l2a left `EntityMutationTestHooks.ONLY_OP = Ignite`, which silently
  disabled the API-1A barriers whenever they ran afterwards.
- The mailbox fixtures assumed chunks (-1..1, 0) were loaded (true only when the random spawn is
  near the origin). They now force-load them synchronously.
- A fixture `ACTION_HOOK` serves respawn actions in-JVM.

## Open findings (owner decision)

**MODULE-LIFECYCLE (blocker for a second world in any client).**
- `ModuleRegistry.MODULES` and `FAILURES` are JVM-global and survive a server stop (`quiesceAll`
  only moves modules to DISABLED).
- World B's module discovery throws `ModuleId already discovered: example-module`, and the world
  fails to load ("Failed to load datapacks, can't proceed with server load").
- Same root as MODULE-RELOAD-001. Not fixed here: MODULE-1 lifecycle is its own gate.
- Phase B evidence was collected with a **test-harness** reset of those two collections
  (generation counters untouched), clearly logged as `HARNESS WORKAROUND`.

**CORE-PARKED-REGION-001 — verified root cause (was F2 in STRESS-001).** Captured at the moment
V1 missed its window:
```
r1{mailbox=1, pending=0, ownedEntities=0, deferredWork=0, freeRunning=false,
   dispatchEligible=false, oldestAgeMs≈5000}
```
- With no external trigger, the message drained only after a further 10–11 s (three runs: 11 170,
  10 048, 10 035 ms).
- Mechanism: a stale-owner message is posted to a region whose last entity has left. A
  non-free-running region with no owned entities and no deferred work is never dispatched by
  `RegionThreadPool.tickAllRegions`, so its mailbox waits for an unrelated event (an entity entering
  or a periodic reassignment).
- Every criterion from the owner's definition is met: mailboxDepth > 0, ownedEntities == 0, the
  scheduler considers the region idle, and the region is not dispatched.
- On the dedicated test worlds, region 1 always had other entities, which is why the fixture passed
  there and the stress misses were intermittent.
- Semantics are preserved: no loss, `unaccounted=0`, correct stale drop. Latency is unbounded by
  the scheduler itself.

## Dedicated regression on the final build (scratch instance, barrier mode unless noted)

| Check | Result |
|---|---|
| CORE-MAILBOX fixtures | 39/39 (L2c skipped); free-running 43/43 |
| API-1A deterministic set, run after the mailbox fixtures | migration REROUTE→APPLIED; removed TARGET_NOT_FOUND; reroutelimit 5×REROUTE→REROUTE_LIMIT_DROPPED; ordering APPLIED + STALE_INTENT_DROPPED |
| API-1B fixtures | all 19 PASS (incl. relogin, offline, window-login, which need a dedicated server) |
| stress churn / pinned / unpinned | MISSING_TERMINAL 0, invariants 0, `unaccounted=0`, C10 0 violations, 0 `[API1A7]` lines, nothing queued at end |
