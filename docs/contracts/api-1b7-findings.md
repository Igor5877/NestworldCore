# API-1B.7 — `setSlot` runtime verification findings

Runtime: NestWorld dedicated, isolated scratch instance (session scratchpad `api1b7/server`, ports
25591/25592, fresh installer build `47.4.236`, its own world). Production/test deployments were
not touched; the only JVMs touched were confirmed by `/proc/<pid>/cwd`. Target: one mineflayer bot
(`invbot`, offline mode). Fixtures: `Api1B6DeterministicTests` via
`/nestworld testmutate invdet <scenario> invbot`. Telemetry: `[API1B6] SETSLOT/BUMP/LIFECYCLE`
lines, `[API1A7] opId=…` phase trace, and `InventorySetTelemetry` invariant counters (computed
independently of the trackers they audit).

**Final status (2026-10-01)**: API-1B SET-SLOT — PRIMITIVE VERIFIED / DISTRIBUTION & TRANSPORT BLOCKED. Primitive correctness VERIFIED (NestWorld direct + routed, genuine
stock Forge; deterministic fixtures with real respawn/relogin; 3 stress runs, all primitive
invariants 0). Delivery reliability BLOCKED by CORE-MAILBOX-MERGE-001 (171/171 missing terminals
attributed under forced churn). Distribution BLOCKED by API1B7-PKG-002. PKG-001 fixed in
`a8a2567b2`. This file is chronological: earlier sections record the event-based incarnation model
and its refutation (fixture E, run 2) before the identity model replaced it.

---

## Finding API1B7-PKG-001 — the deployed Mod API was unusable on NestWorld since API-PKG-1 (FIXED in working tree)

First live `setSlot` call:
```
Exception in thread "API1B6-det-controller-snapshot" java.lang.NoSuchMethodError:
  'java.util.UUID net.minecraft.server.level.ServerPlayer.getUUID()'
    at net.nestworld.modapi.inventory.NestWorldInventory.setSlot(NestWorldInventory.java:89)
```
`javap` on the deployed universal jar: every `net.nestworld.modapi.*` class referenced vanilla by
**MCP** names (`Entity.getUUID`, `MinecraftServer.isSameThread`), while Core classes use **SRG**
names (`m_20148_`, `m_18695_`) as the runtime requires. Cause: API-PKG-1's fix merged
`project(':modapi').sourceSets.main.output` into `universalJar`, i.e. *after* `reobfJar`.
Consequence: `setVelocity` (`ForgeVelocityExecution`, `NestWorldEntities`) was equally broken on
the deployed NestWorld runtime since `7123ef366`; nothing exercised the Mod API on a deployed
NestWorld jar since then (MODULE-1's live pass never called it), and the canary build check only
tested class *presence*.

Fix (committed as `a8a2567b2`, `build.gradle`): merge `:modapi` output into `:forge`'s `jar` task — `reobfJar`'s input —
so it is remapped together with the full patched-vanilla hierarchy (needed for inherited members:
`ServerPlayer.getUUID` → `Entity.m_20148_`); remove the post-reobf merge from `universalJar`;
extend the canary to fail the build if `ForgeVelocityExecution`/`NestWorldInventory` lack
`m_20148_` or still contain `getUUID`. Verified: rebuilt jar's `javap` shows SRG names in all four
checked `:modapi` classes; the server booted and every fixture below ran on it.
Not yet done: negative test of the new canary (deliberately reintroduce the bad merge, expect
BUILD FAILED); re-check of the published `:modapi` Maven artifact's consumer story (it is
MCP-named by design for ForgeGradle `fg.deobf` consumers — untouched by this fix).

## Finding API1B7-CORE-016 — merge loses mailbox messages silently

Recorded as `docs/RACE_CLASSIFICATION.md` item 16. Live trace (fixture D, first attempt):
`OWNER_RESOLVED owner=3` → region 3 merged into 5 → `ENQUEUE to=3` → no APPLY, no TERMINAL,
ever. At-most-once loss (not corruption), Core-wide, not introduced by API-1B. Consequence for
these fixtures: a fixture whose intent is lost reports FAIL/INCONCLUSIVE ("arrived=false"), never
a false PASS.

---

## Fixture A — snapshot independence (run 1: PASS, ordering not yet proven deterministic)

```
submitted: count=5 nw.inner.v=1 cap.value=111
caller mutated original after return: count=64 nw.inner.v=999 cap.value=999
applied (landed=true): knowledge_bookx5 nw.inner.v=1 cap.value=111 lastApplied=1
identity: stackShared=false tagShared=false capShared=false
INVARIANTS applied=1 ... wrongIncarnationMutation=0 duplicateApply=0 staleIntentApply=0
```
Capability state is a real Forge capability attached via `AttachCapabilitiesEvent<ItemStack>`
(serializable, so it travels through `serializeCaps()`/`deserializeCaps()` exactly as `copy()`
does). Gap in run 1: the apply landed ~30 ms after submission and the fixture did not prove the
caller's mutation preceded it. Run 2 (pending) gates the main-thread task queue so the apply
cannot run until all mutations finish, and makes `applyCount==0 at mutation time` part of PASS.

## Fixture B — same-incarnation LATEST_INTENT_WINS across a real reroute: PASS

```
[API1A7] opId=2000000001 OWNER_RESOLVED owner=2        (V1, held at barrier A)
migrated owner 2 -> 3 while V1 held
[API1A7] opId=2000000002 ENQUEUE to=3 -> APPLY owner=3
[API1B6] SETSLOT outcome=APPLIED slot=1 intentVersion=2 incAtSubmission=1 incNow=1
[API1A7] opId=2000000001 ENQUEUE to=2 -> REROUTE from=2 to=3 hop=1 -> APPLY owner=3
[API1B6] SETSLOT outcome=STALE_INTENT_DROPPED slot=1 intentVersion=1 versionTrackerTouched=YES lastApplied=2
V1.tryAccept=1 V1.applyCount=0 V2.applyCount=1 final=diamondx1
INVARIANTS applied=1 staleDropped=1 tryAcceptCalls=2 | wrongIncarnationMutation=0 duplicateApply=0 staleIntentApply=0
```

## Fixture C-SIM — bump between ownership re-check and mutation (simulated transition): PASS

```
intent inc=1 v1 held at barrier C
[API1B6] BUMP incarnation -> 2 versionStateReset=YES entriesRemoved=5
[API1B6] SETSLOT outcome=INCARNATION_MISMATCH slot=2 incAtSubmission=1 incNow=2 versionTrackerTouched=NO
tryAccept=0 applyCount=0 lastApplied=null before=EMPTY after=EMPTY
INVARIANTS applied=0 incarnationMismatch=1 tryAcceptCalls=0 | wrongIncarnationMutation=0 ...
```
Proves the check placement (inside `applyDirect`, after routing) and that a rejected
stale-incarnation intent never reaches `tryAccept`. Does NOT prove real-event timing.

## Fixture D — run 1: NOT RUN (harness defect)

Fixture lines were buffered until the end, so the driver never saw `ACTION_REQUIRED respawn`;
the fixture timed out without a respawn. Also produced Finding CORE-016 above. Fixed: fixture
output now streams line by line.

---

# Run 2 (fresh instance, build with streaming fixtures + deterministic A)

## Fixture A — snapshot independence, ordering now proven: PASS
```
main-thread task queue gated: true
applyCount at the moment all caller mutations completed=0 (must be 0: mutation strictly precedes apply)
[API1B6] SETSLOT outcome=APPLIED slot=0 item=knowledge_bookx5 thread=Server thread
caller mutated original after return: count=64 nw.inner.v=999 cap.value=999
applied: knowledge_bookx5 nw.inner.v=1 cap.value=111   identity: stackShared=false tagShared=false capShared=false
INVARIANTS applied=1 | wrongIncarnationMutation=0 duplicateApply=0 staleIntentApply=0
```

## Fixture D — cross-boundary, REAL respawn: PASS on the direct path (routed path NOT yet exercised)
```
08:21:40.506 incarnation 1: V1000 submitted, assignedHighWater=1000; held at barrier A
08:21:42.108 LIFECYCLE event=RESPAWN playerObj=39311ec9
08:21:42.109 BUMP incarnation -> 2 versionStateReset=YES entriesRemoved=1
08:21:42.130 after reset: assignedHighWater=0 lastApplied=null
08:21:42.131 incarnation 2: V1 submitted
08:21:42.137 V1000 SETSLOT outcome=INCARNATION_MISMATCH intentVersion=1000 incAtSubmission=1 incNow=2 versionTrackerTouched=NO
             tryAcceptCallsDelta=0 mutationCount=0 lastApplied=null
08:21:42.155 V1 SETSLOT outcome=APPLIED intentVersion=1 incAtSubmission=2 playerObj=39311ec9  applyCount=1 lastApplied=1 slot=apple
INVARIANTS applied=1 incarnationMismatch=1 tryAcceptCalls=1 | wrongIncarnationMutation=0 duplicateApply=0 staleIntentApply=0
```
Required ordering bump/reset → old reject → new accept: observed. V1000 never touched the version
tracker (per-intent tryAccept count 0, global tryAccept delta 0, `lastApplied` stayed null until V1).
**Qualification**: `OWNER_RESOLVED owner=null` — in this world the player was not region-owned, so
both arrivals took `dispatch()`'s direct branch (V1000 was checked against the OLD object
`2f7a387a`). The path that matters for this hazard — enqueue → region thread reacquires by UUID
→ gets the NEW object → incarnation check — still needs a routed run (player pinned to a region).

## Fixture C — REAL respawn: PASS (direct path, same qualification)
`INCARNATION_MISMATCH incAtSubmission=2 incNow=3 versionTrackerTouched=NO`, new inventory slot EMPTY.

## Fixture R — REAL logout → relogin: PASS (direct path, same qualification)
`observed offline: incarnationWhileOffline=3 assignedHighWater=1` (logout does not bump; version
state survives the offline period) → `LIFECYCLE event=LOGIN` → `BUMP -> 4 versionStateReset=YES`
→ `INCARNATION_MISMATCH incAtSubmission=3 incNow=4`, relogged-in inventory untouched.

## Fixture E — publication-before-bump window: GAP CONFIRMED (respawn AND relogin)
Method: real transition; a HIGHEST-priority listener on the same Forge event runs before the
bridge's NORMAL-priority `bump()` and, inside that window, runs the unmodified production apply
path for an intent stamped with the pre-transition incarnation. The interleaving is forced (a
region thread draining concurrently is replaced by running the same code on the main thread at
that instant); the code path is not.
```
respawn:
08:22:35.103 SETSLOT outcome=APPLIED slot=5 incAtSubmission=4 incNow=4 item=tntx13 playerObj=767f0599 (NEW object)
08:22:35.104 LIFECYCLE event=RESPAWN playerObj=767f0599
08:22:35.104 BUMP incarnation -> 5 versionStateReset=YES entriesRemoved=2
IN WINDOW: playerList->767f0599 level.getEntity->767f0599 isOldObj=false incarnationNow=4 (bump not yet run)
after transition: newSlot=tntx13 lastApplied=null
wrongIncarnationMutation(by object identity)=1   (InventorySetTelemetry counter: 0 -- it cannot see this, as documented)
relogin:
08:22:40.404 SETSLOT outcome=APPLIED slot=5 incAtSubmission=5 incNow=5 item=tntx13 playerObj=682dc2c1 (NEW object)
08:22:40.404 LIFECYCLE event=LOGIN playerObj=682dc2c1 -> BUMP -> 6
after transition: newSlot=tntx13  wrongIncarnationMutation(by object identity)=1
```
Under the adopted definition (incarnation = one live ServerPlayer object's lifetime) this is a
wrong-incarnation mutation, and the subsequent reset erases the tracker's trace of it.
Reachability today: needs the apply to run off the main thread concurrently with
`respawn`/`placeNewPlayer` — i.e. a region-owned player (routed path). With `owner=null` players
and both peers applying on the main thread, the window is not reachable through
`NestWorldInventory.setSlot` in the current default configuration; it is a latent hazard for the
routed configuration, and it shows the event-driven counter does not implement the definition.

---

# Decision (architecture owner, 2026-10-01) after run 2

GO: object-identity incarnation. NO-GO: event-based incarnation as a correctness mechanism.
Version domain keyed by (player, slot, incarnation); bump/reset removed. Forge lifecycle events
kept only as `LIFECYCLE(telemetry)` log lines. Packaging reobf fix stays in this milestone with
regression tests. Merge/mailbox loss (CORE-016) is a separate Core gate, a blocker for the final
routed/stress PASS, not fixed inside API-1B.

# Packaging fix — regression tests (PKG-001)

- **Negative canary test: PASS.** Reintroduced the API-PKG-1 post-reobf merge into
  `universalJar` → `BUILD FAILED`: `'net/nestworld/modapi/entity/ForgeVelocityExecution.class'
  references vanilla by MCP names (expected SRG m_20148_ ...)`. `build.gradle` restored
  byte-identical (`cmp`).
- **Real installer + boot: PASS** (fresh `--installServer`, `Done (49.632s)`), `javap` shows SRG
  names in `:modapi` classes.
- **setVelocity smoke via the public facade (`NestWorldEntities.setVelocity`): PASS**, both
  paths: player `opId=131 OWNER_RESOLVED owner=null → TERMINAL APPLIED`; armor stand
  `opId=203 ENQUEUE to=2 → NestWorld-Region-2 APPLY → TERMINAL APPLIED`. Zero
  `NoSuchMethodError` in the whole run.

# Run 3 — identity incarnation build (fresh instance, real bot, real transitions)

| Fixture | Path | Result | Decisive line |
|---|---|---|---|
| A snapshot | facade | PASS | `applyCount at mutation completion=0`; count 5, nw.inner.v 1, cap 111; nothing shared |
| B ordering | routed + real reroute 1→2 | PASS | V1 `STALE_INTENT_DROPPED lastApplied=2` after `REROUTE from=1 to=2` |
| C-SIM equals-trap | main thread, no transition | PASS | `X.equals(A)=true sameId=true tokenA=1 tokenX=2`; A's request on X → `INCARNATION_MISMATCH versionTrackerTouched=NO`; X's own → APPLIED v1 |
| C respawn | **routed** | PASS | `NestWorld-Region-1 INCARNATION_MISMATCH requestInc=5 objectInc=6` |
| R relogin | **routed** | PASS | `NestWorld-Region-1 INCARNATION_MISMATCH requestInc=6 objectInc=7` |
| D cross-boundary | **routed** | PASS | see trace below |
| E respawn window | **routed (applyQueued)** | PASS | see trace below |
| E relogin window | UUID re-acquire, `owner=null` at window time | PASS | `requestInc=4 objectInc=5` before `LIFECYCLE(telemetry) LOGIN` |
| C / R / D | direct | PASS | old reference re-checked → `TARGET_REMOVED versionTrackerTouched=NO` |
| E respawn / relogin | direct | PASS | `INCARNATION_MISMATCH requestInc=10/11 objectInc=11/12` |

Invariants in every fixture: `wrongIncarnationMutation=0 duplicateApply=0 staleIntentApply=0`.

**D-ROUTED** (the NestWorld execution path):
```
08:40:02.960 object A=56f50090 token=1: V1000 submitted, domain(A).assigned=1000
08:40:02.963 opId=2000000001 OWNER_RESOLVED owner=1 ; held at barrier A
08:40:04.110 LIFECYCLE(telemetry) event=RESPAWN playerObj=3c49804f
08:40:04.119 INCARNATION minted token=3 playerObj=3c49804f        (first observation of B)
08:40:04.122 object B token=3: V1 submitted | domain(A).assigned=1000 domain(B).assigned=1
08:40:04.122 opId=2000000001 ENQUEUE to=1
08:40:04.153 NestWorld-Region-1: SETSLOT INCARNATION_MISMATCH intentVersion=1000 requestInc=1 objectInc=3
             versionTrackerTouched=NO playerObj=3c49804f        (UUID re-acquisition returned B)
             tryAcceptCallsDelta=0 mutationCount=0 lastApplied(A)=null lastApplied(B)=null B.slot=EMPTY
08:40:04.201 NestWorld-Region-1: opId=2000000002 SETSLOT APPLIED intentVersion=1 requestInc=3 objectInc=3
             applyCount=1 lastApplied(B)=1 B.slot=apple
```

**E-RESPAWN-ROUTED** (event timing removed from the correctness model):
```
08:40:10.623 intent stamped against object A=3c49804f token=3
08:40:11.742 [Server thread] opId=2000000003 APPLY owner=1        (production applyQueued, inside the window)
08:40:11.742 INCARNATION minted token=4 playerObj=b14ea7f          (B observed for the first time)
08:40:11.742 SETSLOT INCARNATION_MISMATCH requestInc=3 objectInc=4 versionTrackerTouched=NO
08:40:11.744 LIFECYCLE(telemetry) event=RESPAWN playerObj=b14ea7f  (Forge event fires AFTER the decision)
after: B.slot=EMPTY tryAccept(A-domain)=0 lastApplied(A)=null wrongIncarnationMutation(by object)=0
```
Compare run 2 (event model), same window: `APPLIED ... incNow=4 playerObj=767f0599` → TNT in the
new inventory.

Qualifications, kept explicit:
- E's interleaving is forced (production apply code run on the main thread inside the window);
  the code path is unmodified.
- In this configuration players are not region-owned by default; routed variants assign the
  player to a pinned region. After a relogin the region assignment is gone, so E-RELOGIN-ROUTED
  exercised UUID re-acquisition + `applyDirect`, not `applyQueued`.
- Direct variants reject the stale request as `TARGET_REMOVED` (the old reference they hold is
  removed), not `INCARNATION_MISMATCH` — expected for that path.
- Stock Forge: closed by construction only; not yet run on a real stock-Forge server.

Still open for API-1B DoD: stock-Forge runtime run, worker-thread submission, logout-before-apply
(offline at apply), different-slot independence, invalid slot, reroute limit, lifecycle reset
across server generations, stress — plus CORE-016 as a separate gate before the final routed/stress PASS.

---

# Stock-Forge gate (genuine upstream Forge 1.20.1-47.4.20, no NestWorldCore)

Setup: fresh `--installServer` from the upstream installer (zero NestWorld entries in it), isolated
scratch dir, ports 25593/25594. Consumer: a real ForgeGradle mod (`nwinvprobe`, built from the
in-repo MDK, official 1.20.1 mappings) depending on the PUBLISHED artifact
`net.nestworld:nestworld-mod-api-forge-1.20.1:1.0.0-SNAPSHOT` from `mavenLocal()`, with `:modapi`
classes embedded into the mod jar before its own reobf (the API-PKG-1 proven route); `javap` on the
mod jar shows SRG names in `ForgeInventorySetExecution`. Every scenario first checks
`Class.forName("net.nestworld.region.NestworldRegionSystem")` → absent, so the only possible
execution path is `setSlot → ForgeInventorySetExecution → main-thread marshal → fresh
getPlayer(uuid) → identity check → version check → Inventory.setItem`.

Method: no Core barriers exist on stock Forge. A request is held in flight by parking the main
thread's task queue behind a latch; packets (respawn, disconnect) keep arriving on Netty threads
and queue behind it, so after release the server runs queued tasks FIFO. The Forge peer emits no
telemetry, so outcomes are proven by end state, the version tracker's read-only diagnostics
(`peekAssigned`/`peekLastApplied`), and a FIFO sentinel task (`server.submit(() -> {}).get()`)
guaranteeing every earlier-queued apply has run. A rejected request whose apply did run and found
a non-null player can only have returned at the identity check (`lastApplied == null` proves
`tryAccept` was never reached).

| Scenario | Result | Decisive line |
|---|---|---|
| STOCK-A snapshot + worker-thread submission | PASS | gated main: `lastApplied at caller-mutation time=null` (marshaled, not applied on caller thread); applied `knowledge_bookx5 nw.inner.v=1 cap=111`, nothing shared |
| STOCK-C request in flight across REAL respawn | PASS | A submitted while A published (respawn packet queued, not executed) → applied after `event RESPAWN newObj=31969352` → `tokenB=3`, `domain(A).lastApplied=null`, `B.slot=EMPTY` |
| STOCK-D cross-boundary | PASS | see trace |
| STOCK-STALE caller holds pre-respawn reference | PASS | `setSlot(A-ref)` after respawn: `tokenA=3 tokenB=4`, `domain(A).lastApplied=null`, B untouched |
| STOCK-LOGOUT request submitted during logout, relogin after | PASS | apply after removal → no-op (`online=false`, `domain(A).lastApplied=null`); relogin `tokenB=5`, B untouched |

STOCK-D:
```
11:00:53.568 object A=4c293b54 token=1
11:00:54.222 A is dead and still the published object: true
11:00:54.223 main task queue gated
11:00:54.999 respawn packet sent by the bot (queued behind the gate)
11:00:55.009 A's request submitted while A is still published: token=1 V1000 (queued behind the respawn task)
11:00:55.085 [Server thread] event RESPAWN newObj=6ef1692
11:00:55.089 in respawn task: B=6ef1692 token=2 -> V1 (apple) submitted in domain(B) from a worker
11:00:55.145 after FIFO drain: B.slot=applex1 | domain(A): assigned=1000 lastApplied=null | domain(B): assigned=1 lastApplied=1
RESULT STOCK-D: PASS
```
Queue order after release: respawn task (publishes B; probe's own event listener only schedules
V1, it is test scaffolding, not part of any correctness path) → A's V1000 apply → B's V1 apply.

Harness defect found and fixed during this gate (not a primitive result): first STOCK-D attempt
gated the queue right after `/kill`; the death packet was not flushed to the client while gated,
so mineflayer believed it was alive and `respawn()` sent nothing → TIMEOUT. Fix: the driver waits
for the bot to observe its own death before the probe gates.

**Conclusion**: the stock-Forge incarnation blocker is closed with runtime evidence — the
identity model protects respawn/relogin semantics on stock Forge with no lifecycle signal at all.

## Finding API1B7-PKG-002 — a consumer mod that embeds `:modapi` cannot boot on NestWorld (OPEN, packaging decision needed)

Tested immediately after the stock gate: the SAME probe jar (`:modapi` embedded, the route
API-PKG-1 proved and documents) dropped into the NestWorld scratch server (47.4.236):
```
Exception in thread "main" java.lang.module.ResolutionException:
  Modules nwinvprobe and forge export package net.nestworld.modapi.entity to module minecraft
```
NestWorld's own `forge` module carries `net.nestworld.modapi.*` (Core calls it directly), so any
mod shipping its own copy creates a split package and the server does not start. This has been
true since the 2026-09-30 merge put `:modapi` into the deployed jar — it is not caused by
PKG-001 (which only fixed naming). API-PKG-1's "same consumer jar, NestWorld PASS" was on 47.4.234,
before that merge, and is no longer valid evidence. Consequence: today there is no single
consumer jar that runs on both stock Forge and NestWorld. This is a distribution-architecture
decision (who provides `net.nestworld.modapi` on each platform, and how consumers depend on it),
outside setSlot's primitive correctness; it is not fixed here.
**Follow-up (same day)**: distribution model investigated and proven by experiment (E1–E6) —
`docs/contracts/PKG-002-distribution-model.md`. Implementation pending two owner decisions.

---

# Deterministic DoD cases (NestWorld, build 47.4.237, real bot)

| Case | Result | Decisive line |
|---|---|---|
| W-DIRECT worker-thread submission via public facade | PASS | `callerThread=API1B6-worker-submitter applyThread=Server thread` |
| W-ROUTED | PASS | `applyThread=NestWorld-Region-1` (owning region thread) |
| SLOTS per-slot independence | PASS | arrival slot9 v2, slot9 v1, slot10 v1 → slot9 v1 `STALE` in its own domain; slot10 v1 applied although slot9 had reached v2; `lastApplied slot9=2 slot10=1` |
| INVALID slots | PASS | -1, 41, 45, 1000, MIN_VALUE: no exception, no version assigned, nothing dispatched; boundary 0 and 40 (offhand) applied |
| REROUTE limit | PASS | `hopLimit=5 barrierBHits=6 rerouteLimitDropped=1 maxRerouteHop=5 tryAccept=0 applied=0` |
| GEN server-generation reset | PASS | after the exact `onServerStopping` resets: same live object `token 1 -> 2`; pre-reset request `INCARNATION_MISMATCH`, tracker untouched. Scope: in-JVM state boundary; a second `MinecraftServer` in one JVM (integrated restart) not exercised |
| OFFLINE-DIRECT | PASS | `owner=null` → `TARGET_REMOVED`, nothing applied, reconnected inventory untouched |
| OFFLINE-ROUTED | PASS (clean rerun) | `ENQUEUE to=2 → Region-2 TERMINAL TARGET_NOT_FOUND`, nothing applied |

Two harness issues found and fixed on the way (not primitive results):
- **Wrong jar deployed once.** Commit 1 bumped the build version 47.4.236 → 47.4.237; the deploy
  step still used a hard-coded `.236` path and installed the commit-1 build. Caught because the new
  scenarios did not exist. Deploys now take the version from the build log and assert marker classes.
- **Direct variants inherited region ownership** from earlier routed fixtures (OFFLINE-DIRECT ran
  with `owner=1`). Direct variants now explicitly make the player unowned and log it.

**OFFLINE-ROUTED first run: delivery lost to CORE-016 (split variant)** — `OWNER_RESOLVED owner=1`
→ `Split Region{id=1} -> [3,4]` → `ENQUEUE to=1` → no TERMINAL. Primitive side intact (nothing
applied, tracker untouched). Classified as: primitive correctness PASS, delivery blocked by
CORE-016. `docs/RACE_CLASSIFICATION.md` #16 updated: split variant now reproduced live; pinning
does not prevent splits.

# Finding API1B7-STRESS-001 — first stress attempt crashed the server (harness overload; two real observations)

`ServerHangWatchdog: a single server tick took 60.00 seconds`. Main thread stack: inside log4j
`RollingRandomAccessFileManager.writeToDestination` (synchronous file write). Cause: 4 uncapped
submitter threads × several synchronous per-op INFO lines (`[API1A7]` phase trace + `[API1B6]
SETSLOT`) — `server.out` reached ~3M lines in ~2 min. Not a primitive result; the run produced no
invariant data. Harness fix: per-op DIAG logger raised to WARN for the stress run, fixture output
on its own logger, submitters capped (~200/s each).
Real observations kept from it:
1. The `[API1A7]` per-op INFO phase trace is temp instrumentation that is ALREADY in committed code
   on the `setVelocity` path (`EntityMutationDispatcher.dispatch/applyQueued`). A mod calling the
   Mod API at a high rate could reproduce this on a real server. It must be removed or demoted
   before any of this instrumentation is considered production-ready.
2. The NestWorld peer marshals every `setSlot` through a main-thread task (thread-safe
   `PlayerList` lookup, by design). `setSlot` throughput is therefore main-thread-bound — a
   documented performance characteristic, not a correctness issue.

# Stress (NestWorld, build 47.4.237, 60 s each, 3 bots + `invbot`)

Load per run: 4 submitter threads through the PUBLIC facade (~200/s each), random player from a
refreshed, possibly stale snapshot of player objects, slots 13–20, each caller stack mutated right
after the call; a migrator reassigning player ownership between live regions (main-thread tasks,
~6.7/s); driver chaos: alternating `/kill` (auto-respawn) and full disconnect/reconnect of a random
bot every ~2.5 s. Invariants are computed independently of the trackers: `wrongIncarnationMutation`
compares the mutated object against the object the harness itself recorded for that token at
submission (`==`); `callerObjectLeak` checks the stored stack against an identity set of every
caller-owned stack; `finalStateMismatch` compares each slot's final `nwseq` tag with the highest
version applied in the current incarnation's domain; delivery = submitted − terminal outcomes.

| Run | Submitted | Applied | Stale | IncMismatch | Offline (backend/queued) | MISSING_TERMINAL | Splits / merges in run | Result |
|---|---|---|---|---|---|---|---|---|
| PINNED | 46,488 | 45,488 | 715 | 174 | 109 / 2 | **0** | 1 / 1 (region 1 retired) | primitive PASS, delivery complete |
| UNPINNED | 46,820 | 45,853 | 653 | 162 | 152 / 0 | **0** | 0 / 0 | primitive PASS, delivery complete |

Both runs: `duplicateApply=0 wrongOwnerMutation=0 wrongIncarnationMutation=0 staleIntentApply=0
callerObjectLeak=0 unboundedReroute=0 (maxRerouteHop=1, limit=5) tokenCollision=0
finalStateMismatch=0` (24 slots checked); 9 kills + 9 relogins each.

What these do NOT show: CORE-016 exposure. The unpinned run had no split/merge at all; the pinned
run had one of each and did not hit the window. A third run (`stress-churn`) forces split/merge
every ~2 s and measures messages stranded in retired regions' mailboxes directly.

| CHURN (forced split/merge every ~2 s) | 46,258 | 44,821 | 995 | 137 | 134 / 0 | **171** | 16 / 14 (44 regions retired) | primitive PASS, delivery blocked by CORE-MAILBOX-MERGE-001 |

**Churn attribution — exact**: `messagesStrandedInRetiredMailboxes=171` (summed over the 44 region
objects retired during the run) `== MISSING_TERMINAL=171`. Every submission without a terminal
outcome is a message left in a retired region's mailbox — CORE-016 / CORE-MAILBOX-MERGE-001,
measured rather than inferred. All primitive invariants stayed 0 under churn
(`duplicateApply=0 wrongOwnerMutation=0 wrongIncarnationMutation=0 staleIntentApply=0
callerObjectLeak=0 unboundedReroute=0 finalStateMismatch=0`). Note what `finalStateMismatch=0`
means here: every slot holds the highest version that was APPLIED; a lost later write leaves an
earlier applied value in place — consistent state, but a write the caller made never took effect.
That is the delivery defect's user-visible face, not a primitive inconsistency.
