# MODULE-0 — NestWorld Module SPI Boundary & Lifecycle Audit

Status: COMPLETE. Verdict: MODULE-0 PARTIAL (see bottom). Research/architecture/documentation only --
no ModuleClassLoader, no hot reload implementation, no changes to net.nestworld.modapi (Mod
API, Milestone 1, frozen at commits d405f5187/7123ef366).

Written incrementally, section by section, so a session-limit cutoff loses no more than the
section in progress.

## Standing principle under test

```
Ordinary mods
     |
     +-- legacy compatibility
     |
     +-- Mod API
            |
     bounded capabilities (net.nestworld.modapi -- Milestone 1's tier, already built)
            |
============|============
       NestWorldCore
 region/ownership runtime
============|============
            |
        Module SPI
            |
 privileged but controlled
 Core extension capabilities
```

`privileged != unrestricted`. A module with scheduler-policy privilege must NOT automatically
inherit the right to reach into another region's `Entity` and mutate it from an arbitrary
thread — checked explicitly in section B/10 below.

---

## A. Extension Point Inventory

Codebase scale: `net.nestworld.region` alone is 121 files / 26,201 lines. This inventory covers
every category the audit directive named, grounded in real files, not exhaustive file-by-file
coverage of the package.

| Extension point | Current implementation | Mutable state | Thread/owner model | Lifecycle scope | Extensible today without patching Core? | Privilege a module would need |
|---|---|---|---|---|---|---|
| **Region scheduler/execution** | `RegionThreadPool` (barrier-synchronized tick loop), `RegionScheduler` (`RegionScheduler.java:17-38`, purely observational `IDLE/RUNNING/DELAYED/OVERLOADED` classifier, explicitly does NOT change `awaitLatch()`'s semantics) | Per-region rolling tick-cost average, deferral counters | One `RegionThread` per region, main thread runs the barrier | Whole-server-lifetime, thread pool sized at boot | NO — classification thresholds (`DELAYED_THRESHOLD_FRACTION` etc.) are compile-time constants, no policy hook | Read: none needed (observational). Influence: would need a genuinely new hook — none exists today. |
| **Ownership/routing** | `EntityMutationDispatcher` (dispatch/applyQueued, Milestone 1's own reference implementation), `EntityOwnershipGuard`, `NestworldOwnershipAssertions`, `ModCallbackRouter` (`ModCallbackRouter.java:9-24`, defers `MAIN_THREAD_ONLY`-classified mod callbacks off region threads) | Per-target ownership resolution via `WorldGrid`; `ModCallbackRouter`'s `PENDING` queue | Caller's thread; routed work executes on the resolved owner's `RegionThread` or main thread | Whole-server-lifetime | NO — this is exactly the boundary Milestone 1 spent the whole session proving must stay Core-controlled; nothing here is designed for third-party extension | **Highest tier.** Any module touching this needs Core-controlled execution — this is the invariant a Module SPI must never let a module bypass (see §10). |
| **Split/merge policy** | `RegionSplitManager` (`RegionSplitManager.java:12-30`) — hysteresis thresholds (`SPLIT_MS_THRESHOLD`, `MERGE_MS_THRESHOLD`, consecutive-evaluation counts) are hardcoded constants | Per-region consecutive-over/under-threshold counters | Runs on main thread between ticks (BSP tree mutates while region threads are parked) | Whole-server-lifetime | NO — no policy interface exists; a module wanting different split/merge heuristics has nothing to implement against today | A genuine "scheduler policy" extension point would live here — but see §10: influencing WHEN to split must not imply the right to touch entity state directly. |
| **Region lifecycle** | `NestworldRegionSystem.onServerStarting`/`onServerStopping` (`NestworldRegionSystem.java:167-200`, `@SubscribeEvent`-based) | `INSTANCE` static field (already flagged elsewhere this session as a JVM-global-singleton risk — see §7) | Main thread, Forge event bus | Server-process lifetime (or JVM lifetime across an integrated-server world restart, since `INSTANCE` is static) | Partially — Forge's own event bus lets other listeners subscribe to `ServerStartingEvent`/`ServerStoppingEvent`, but nothing Core-specific (region state, split/merge state) is exposed as a hook | A Module SPI's own lifecycle almost certainly needs to piggyback on these same two events as its outermost boundary. |
| **Compatibility policy** | `net.nestworld.compat.NestworldModCompat` (`NestworldModCompat.java:19-30`) — `MODERNFIX_PRESENT` is a `public static final boolean` computed ONCE via `Class.forName` at class-init | None after class-init (immutable post-bootstrap) | JVM class-loading, effectively single-threaded at that point | **Bootstrap-only** — this exact field is one of the three `FORGE-001` findings (this session) of Core coupling baked directly into patched vanilla's own `StateDefinition.<init>` | NO — and structurally can't be, short of re-running class-init | RESTART_REQUIRED by construction (see §8) — no module privilege level changes this. |
| **Race detection** | `net.nestworld.racedetect` package — `RaceDetectTuning.Mode` enum (`OFF/DISCOVERY/WATCH/DEEP/CONFIRMED`, `RaceDetectTuning.java:22-24`) is a `public static volatile` field, live-settable via `/nestworld race mode` RCON, no restart needed | `CandidateRegistry`'s scan results | Background scan thread | Can be toggled any time post-boot | Sort of — it's a real precedent for a LIVE, RCON-settable mode transition (unlike the compat flag above), but the scan targets (which jars/classes) aren't module-extensible, only the mode is | Low — this is closer to "Core-owned diagnostic toggle" than something a module would drive. Cited here as the clearest existing precedent for a *safely live-transitionable* piece of Core state — relevant groundwork for §3/§8. |
| **Instrumentation/metrics** | `TickDurationHistory`, `TickAttribution`, `ScheduledTickHandoffMetrics`, `ExecutionMetrics` (gmeil) | Various rolling counters/histograms, mostly per-class static state | Written from whichever thread the tracked work runs on; read from main thread/RCON | Whole-server-lifetime | Read-only exposure exists (`/nestworld status` etc.); no module-facing subscription/hook mechanism | Read-only tier — plausibly `HOT_LOADABLE` (see §8) since it's additive/observational, not mutating shared state. |
| **Commands** | `NestworldCommand.register` — one Brigadier tree, **230 `Commands.literal` call sites in one file** (already flagged this session as `CORE-CMD-001`: a large inline fluent chain silently produced a structurally-wrong command tree once, worth remembering for any module-registered-subcommand design) | Brigadier's own command dispatcher tree | Registered on `RegisterCommandsEvent` (main thread, boot-adjacent) | Registered once per server start; Brigadier itself supports dynamic add/remove of nodes, but nothing here does | NO — no module-facing command-registration API exists; every command is a Core literal | A module registering its own subcommands needs (a) a namespaced registration point and (b) a `ModuleContext`-owned handle so Core can unregister them centrally (see §D). Brigadier's own model would ALLOW dynamic removal, unlike most of Core's other state — worth flagging as a genuinely `HOT_LOADABLE`-friendly candidate (§8). |
| **Lifecycle events** | Forge's own `@SubscribeEvent` mechanism (`onServerStarting/Stopping/Tick`, `onEntityJoinLevel/LeaveLevel`, etc. — `NestworldRegionSystem.java:167-268`) | N/A (Forge-owned event bus) | Whatever thread fires the event (mostly main) | Whole-server-lifetime, static `@SubscribeEvent` methods registered once at mod-loading | YES, in the narrow sense that Forge's own event bus is already extensible by design — but a module wanting to safely subscribe/unsubscribe without leaking a listener registration has no NestWorld-specific mechanism (see §D) | A Module SPI's event subscription surface should NOT be "just use `@SubscribeEvent` directly" — that has no revocation story. |
| **Services** | `net.nestworld.api.NestworldApi` (pre-existing, narrow metrics/diagnostics facade — on-record #94 ruling, NOT the seed of a general Mod API, confirmed earlier this session) | Stateless facade over Core reads | Any thread (read-only) | Whole-server-lifetime | Partially — this IS a working example of a Core-internal-type-free facade, same signature discipline Milestone 1's `net.nestworld.modapi` also uses | Read-only service tier; a "service registration" concept (module registers a service Core or other modules can look up) does not exist anywhere in this codebase today — would be new. |
| **Configuration** | `NestworldTuning` (boot-time, mostly `System.getProperty`-resolved constants) + `NestworldLiveTuning` (`NestworldLiveTuning.java:14-19` — `public static volatile int chunkGenBudgetOverride`, `-1` sentinel = "use boot-time value", live-settable via RCON, explicitly modeled to avoid a JIT-compilation-storm confound from restart-per-change sweeps) | Individual volatile fields, one per tunable | Written from RCON command thread (main), read from any thread | Whole-server-lifetime, live-mutable post-boot | Sort of — the *pattern* (`volatile` field + `effectiveX()` accessor + sentinel default) is a real, reusable precedent; there's no generic module-config namespace, just ad hoc per-value fields | Low — but this exact pattern (§3/§6 below) is the cleanest existing precedent for "safely change Core behavior without a restart," worth reusing structurally for any config a Module SPI exposes. |

**Not invented, explicitly checked and excluded**: no dependency-injection container, no service-locator, no plugin-manifest/classpath-scanning mechanism, no existing "module" or "addon" concept anywhere in `net.nestworld.*` — confirmed by the package listing (`adapter`, `api`, `chunk`, `compat`, `gmeil`, `racedetect`, `region`, `startup`, plus `modapi` now split out) and by grep for `ServiceLoader`/`interface *Plugin`/`interface *Addon`/`interface *Extension` across `src/main/java/net/nestworld` returning zero hits. Every "extension point" above is either (a) a Forge-provided mechanism NestWorld code happens to use (event bus, commands) or (b) a Core-internal seam with zero external-facing contract today.

---

## B. Privilege Boundary

```
Mod API (net.nestworld.modapi)              Module SPI (this audit's subject)
  portable, Core-free ABI                     privileged, Core-specific
  jdeps-verified zero Core imports             directly touches Core internals
  bounded capabilities (setVelocity-shaped)    extension points from §A
  same contract on ordinary Forge & NestWorld  meaningless without NestWorldCore present
  target: any Forge mod                        target: NestWorld-aware modules specifically
```

**What modules MAY plausibly control** (evidence-grounded, from §A's rows): commands
(namespaced subtree registration with a revocable handle), read-only metrics/instrumentation
subscription, configuration values following the `NestworldLiveTuning` volatile-field pattern,
and — the genuinely new, not-yet-existing category — split/merge POLICY (the *decision* of
when/how to split, not execution of the split itself, which must stay Core-owned).

**What must stay Core-private, no exceptions found that would justify otherwise**: entity
mutation/ownership routing (`EntityMutationDispatcher`'s whole mechanism — this is precisely
where Milestone 1's rigor lives, see §10), the region thread pool's own barrier/scheduling loop,
`RegionSplitManager`'s actual split/merge EXECUTION (atomicity depends on running only while
region threads are parked), and anything bootstrap/coremod-level (§A's compat row, §8) —
structurally impossible to make module-controlled regardless of privilege tier.

**Capability declaration is architecture/error-prevention, not a Java security sandbox** — worth
stating plainly rather than implying otherwise: nothing in this audit proposes bytecode
verification, a `SecurityManager`-style enforcement layer, or any mechanism that stops a module
written in Java from simply calling `net.nestworld.region.EntityMutationDispatcher.dispatch(...)`
directly if it's on the classpath and the class is public. Milestone 1's own routing precedent
already proves this class of protection is a convention/API-shape problem, not a JVM-sandboxing
problem — `EntityMutationDispatcher` is `public` today and NOTHING stops arbitrary same-JVM code
from calling it; the actual protection Milestone 1 built was a clean bounded contract that makes
the CORRECT path also the EASY path, not a technical barrier against the incorrect one. A Module
SPI's privilege model should adopt the same posture: capability declarations document and
architecturally shape what a well-behaved module does, they do not physically prevent a
malicious or buggy one from reaching further. This must be stated explicitly wherever the
Module SPI contract is eventually written, so nobody downstream assumes a security property that
was never built.

---

## C. Lifecycle Contract Draft

**Core's OWN lifecycle precedent is simpler than the proposed 7-state model** — worth stating
before evaluating it. `NestworldRegionSystem` (`onServerStarting`/`onServerStopping`) and
`VelocityBackendRegistry` (`install`/`uninstall`, Milestone 1's own lifecycle precedent) both
have effectively TWO states each (absent/present), transitioning atomically 1:1 with server
start/stop — nothing in Core today has a separate "loaded but not yet enabled" moment, because
Core's own components are compiled in and boot together. This doesn't mean the proposed
`DISCOVERED -> LOADED -> ENABLED -> RUNNING -> QUIESCING -> DISABLED -> UNLOADED` chain is wrong
— modules, unlike Core's own components, are meant to be discovered/loaded independently of
server boot (e.g. added while the server is already running) — but it means NONE of that extra
granularity has a working Core precedent to borrow correctness from. Each new transition is a
genuinely new mechanism, not a reuse of something already proven.

**Revised state list, keeping the proposed shape but grounding each transition**:

| State | Meaning | Owns the transition | Core precedent? |
|---|---|---|---|
| `DISCOVERED` | Module found (jar/classpath entry located), not yet touched | Core (a module-discovery mechanism, doesn't exist yet) | None |
| `LOADED` | Classes loaded, module object constructed, NOT yet hooked into any extension point | Core | None — closest analog is a mod's own `@Mod` constructor running, which Forge already does, but NestWorld itself has no equivalent |
| `ENABLED` | Module has registered its extension-point hooks (commands, config, policy) | Core, module supplies the registrations | `VelocityBackendRegistry.install` is the closest real precedent — but it's 1:1 with server start, not independently triggerable |
| `RUNNING` | Steady state, hooks are live and may be invoked | N/A (not a transition, a duration) | — |
| `QUIESCING` | Draining: no NEW work accepted, existing in-flight work finishes or is explicitly cancelled | Core-initiated, module may observe but not veto (see §F) | **No Core precedent exists.** This is the single biggest gap: Core's own shutdown today does NOT quiesce — `RegionThread.shutdown()`'s mailbox is NOT drained (`docs/RACE_CLASSIFICATION.md` item 15, found during Milestone 1), meaning Core's real shutdown behavior today is closer to `RUNNING -> UNLOADED` with silent loss than a clean `QUIESCING` step. A Module SPI that wants a real `QUIESCING` guarantee must build genuinely new mechanism — it cannot inherit correctness from how Core itself shuts down, because Core itself doesn't do this cleanly today. |
| `DISABLED` | Hooks unregistered, module object may still be referenced/inspectable | Core | `VelocityBackendRegistry.uninstall`'s token-check IS a real, working precedent for "make sure a stale reference can't be mistaken for the active one" — but again, does not prove referenced resources were released (see §G/§11, and this session's own "Lifecycle identity replacement: PASS" finding, which proved exactly this distinction the hard way). |
| `UNLOADED` | Classloader eligible for collection, module fully gone | Core (can only ASSERT eligibility, see §7/§11 — cannot force it) | None |
| `FAILED` | Entered from any state on an unhandled exception during a transition or during `RUNNING` | Core | `NestworldPins`'s pin-reason tracking (this session's earlier work) is a real, working precedent for attributing WHY something got disabled — reusable shape for module failure attribution (§G), though it tracks mod tick-errors today, not module-lifecycle failures. |

**Legal transitions**: `DISCOVERED -> LOADED -> ENABLED -> RUNNING -> QUIESCING -> DISABLED ->
UNLOADED`, plus `FAILED` reachable from any state, plus `DISABLED -> ENABLED` (re-enable without
reload) as a plausible addition IF `DISABLED` genuinely guarantees zero leaked registrations
(unproven — see §11). `LOADED -> UNLOADED` directly (skip `ENABLED`) should be legal for a module
that fails validation before ever registering hooks. No state may skip `QUIESCING` on the way to
`DISABLED` if it ever reached `RUNNING` — this is the one hard rule this draft asserts with
confidence, precisely because Core's own shutdown gap (above) shows what happens when that step
is skipped.

---

## D. Resource Ownership Matrix

For each leakable resource category, what Core evidence exists today, and whether a
`ModuleContext`-owned-registration model is plausible:

| Resource type | Real Core example | Leak risk today (evidence) | `ModuleContext` ownership plausible? |
|---|---|---|---|
| Listeners (Forge `@SubscribeEvent`) | `NestworldRegionSystem`'s own handlers | Forge's event bus has no NestWorld-specific revocation; a module using raw `@SubscribeEvent` leaks on unload unless Forge's own bus-unregister API is used AND actually called | Yes — `ModuleContext.subscribe(...)` returning a handle Core closes centrally is a direct, low-risk addition |
| Commands | `NestworldCommand`'s 230-literal Brigadier tree | No revocation mechanism exists for ANY command today, module or Core; Brigadier's tree itself supports node removal, nothing in this codebase exercises that path | Yes, and this is the clearest `HOT_LOADABLE`-leaning candidate in the whole audit (§F) |
| Scheduled work | `NestworldRegionSystem#nestworldEnqueueScheduleTick`, `RegionScheduledTicks` | "Never lost, next-tick-at-worst" discipline (per `ModCallbackRouter`'s own javadoc, `ModCallbackRouter.java:20`) — but nothing tags WHO scheduled it; a module's scheduled work is indistinguishable from anyone else's once enqueued | Needs generation tagging (§E) before revocation is even meaningful — can't cancel what you can't identify |
| Region mailbox work | `EntityMutationDispatcher`'s `RegionMessage.entityMutate(...)` queue | **Confirmed unrevoked on shutdown today** — `RACE_CLASSIFICATION.md` item 15, found during Milestone 1: `RegionThread.shutdown()` does not drain the mailbox | Same conclusion — a module's in-flight mailbox work has the SAME unrevoked-on-shutdown property Core's own already does; fixing this for modules specifically without fixing it for Core generally would be inconsistent |
| Executors/threads | None found in `net.nestworld.region` spawning ad hoc executors for mod-facing work (the region thread pool is fixed-size, Core-owned) | A module spawning its OWN `ExecutorService` (the exact failure mode `NestworldShutdownWatchdog`'s own javadoc cites as the ORIGINAL incident that motivated that whole class — a leaked non-daemon executor kept the JVM alive after shutdown) | Must be disallowed or Core-mediated — a module should never get raw `Executors.newXxx()` access; if async work is needed, it should go through a Core-owned, trackable facility |
| Subscriptions/callbacks/lambdas | `ModCallbackRouter.PENDING` (`ConcurrentLinkedQueue<Runnable>`) | Same "no owner tag" gap as scheduled work | Needs generation tagging |
| `ThreadLocal`s | `DimensionChangeScope.DEPTH` (`DimensionChangeScope.java:23`, `ThreadLocal<int[]>`) is Core's own real, working example — correction to an earlier draft of this row that wrongly found none | Core's own instance is a Core-owned, Core-lifetime `ThreadLocal` — not a leak, since it's never expected to go away. A MODULE-introduced `ThreadLocal` is the actual risk, and it is definitionally impossible for Core to revoke without the module's own cooperation | Flag as an explicit prohibition (or at minimum a documented, unenforceable risk) in the eventual SPI contract — `ModuleContext` cannot solve this structurally, it can only forbid the pattern by convention |
| Static references | `NestworldRegionSystem.INSTANCE`, `NestworldModCompat.MODERNFIX_PRESENT`, `VelocityBackendRegistry`'s `backend`/`installedFor` fields | This session's own established pattern (`VelocityBackendRegistry`) shows the FIX (token-checked install/uninstall) but also shows it only proves "no stale reference wins arbitration," not "the old object is collectible" (§11) | The `VelocityBackendRegistry` pattern generalizes directly — but a module's OWN static fields (inside module code, not Core's) are invisible to Core entirely; Core can only manage references IT holds, never references the module's own classes hold to themselves |
| Minecraft/Core object references | Any entity/level/server reference a module's callback captures | Not specifically evidenced in Core code (Core doesn't hold onto mod objects) — but the RISK is symmetric: a module callback capturing a live `Entity`/`ServerLevel` in a field is exactly Milestone 1's own "live reference crossing an async boundary" hazard class, now applied to module lifetime instead of thread boundaries | Not solvable by `ModuleContext` — this is a module-authoring discipline problem, the same one Milestone 1's whole `SetVelocityIntentTracker`/fresh-reacquisition design exists to prevent for Core's own dispatch, now recurring one layer up |

**Conclusion for this section**: a `ModuleContext` that owns revocable HANDLES (listeners,
commands, scheduled-work generation tags) is real and buildable, directly reusing the
`VelocityBackendRegistry` token-check pattern. It cannot, by construction, guarantee a module
has released everything — executors, `ThreadLocal`s, and captured live references are either
outside Core's reach entirely or require the module's own correct cooperation. This limits what
"successful unload" can honestly claim (§11).

---

## E. Generation/In-flight Work Model

**`ModuleId`/`ModuleVersion`/`ModuleGeneration` as three separate axes**: `ModuleId` identifies
WHAT (stable across reloads), `ModuleVersion` identifies the code revision (changes when the
module's jar changes), `ModuleGeneration` identifies THIS LOADED INSTANCE (changes every single
load, even reloading the identical jar unchanged — this is the axis that actually matters for
"is this work from before or after replacement," not version). This three-way split has no
direct Core precedent (Core has no module concept yet) but IS structurally the same shape as
Milestone 1's own `SetVelocityIntentTracker.TargetKey` + `intentVersion` design: a stable
identity axis (there: `(dimension, entityId)`; here: `ModuleId`) plus a strictly-increasing
per-identity counter that is the actual arbitration key (there: `intentVersion` at the entity
level; here: `ModuleGeneration` at the module level).

**Does queued/in-flight work need generation tagging? Yes, evidenced directly by §D's own
findings**: scheduled work and mailbox work today carry NO owner tag of any kind (§D, "Scheduled
work"/"Region mailbox work" rows) — Core cannot even attribute a queued operation to a module
today, let alone distinguish "this operation is from generation N, the module is now on
generation N+1." Without generation tagging, `QUIESCING` (§C) has no way to know whether a piece
of work draining right now belongs to the module being quiesced or a brand-new generation that
already replaced it mid-drain.

**Old-generation work executing after replacement — explicitly studied, not just flagged**:
this is the module-lifecycle analog of `API1A7-SEC11-ORDERING` (Milestone 1's confirmed,
deterministically-reproduced ordering inversion: a delayed operation applying after a newer one
already won). The exact same mechanism applies one layer up: if module generation N submits
scheduled work, then gets replaced by generation N+1, and N's work was delayed (region migration,
mailbox congestion, whatever), N's work can execute AFTER N+1 is already running — potentially
against state N+1 has already changed. Milestone 1's fix (`LATEST_INTENT_WINS`, version checked
immediately before the mutation) is a DIRECT template for module generation: a
`ModuleGeneration`-tagged operation should be checked against the module's CURRENT generation
immediately before it executes, and dropped (not applied) if stale — same shape, same
linearization-point discipline (assign the generation tag at submission, not at execution), same
"this is not a delivery guarantee, it's an intent-arbitration guarantee" framing. **Not
implemented here** — this is a design-transfer observation, not a claim that the mechanism has
been built for modules.

---

### E.2 Quiesce / In-flight Work (continuing Section E -- generation + in-flight work together)

**What "module stopped" actually means -- must be decomposed, not treated as one moment**:

- **Block new submissions**: straightforward IF the module's registration points are
  `ModuleContext`-owned (Section D) -- Core can flip a flag the registration-forwarding methods
  check. No Core precedent needed; this is a new but simple mechanism.
- **Drain vs. cancel existing work**: Core's own precedent (Section C) is neither -- it's SILENT
  LOSS (`RACE_CLASSIFICATION.md` item 15). A Module SPI wanting a real drain-or-cancel choice
  must build it fresh; it cannot inherit this from Core's region-mailbox mechanism, which
  doesn't offer either option today, it just drops.
- **Mailbox work specifically**: per Section D/E, mailbox work has no owner tag today. Quiescing
  a module's mailbox work cleanly requires generation tagging to exist FIRST (Section E) -- the
  ordering matters: you cannot selectively drain "this module's work" from a mailbox that
  doesn't know which entries belong to which module.
- **Region migration/reroute during quiesce**: `EntityMutationDispatcher.applyQueued`'s existing
  reroute mechanism (Milestone 1, extensively verified) re-resolves ownership fresh at apply
  time and reroutes if it changed -- this ALREADY handles "the target moved during the
  operation's flight," independent of any module concept. A module-originated mutation riding
  through this same dispatcher inherits this property for free. The NEW risk specific to
  quiescing is different: not "did the target move," but "is the module that submitted this
  operation still the current generation" (Section E) -- reroute-correctness and
  generation-correctness are orthogonal checks that both need to pass.
- **Executor termination**: only relevant if modules get raw executor access, which Section D
  already recommends against. If Core mediates all async module work through a Core-owned
  facility instead, "executor termination" reduces to "Core's own facility stops
  accepting/tracking that module's submissions" -- no separate executor lifecycle to manage per
  module.
- **Callbacks currently executing (in-flight at the moment QUIESCING begins)**: the hardest
  case, and the one with the LEAST Core precedent. Nothing in this codebase currently has a
  "wait for in-flight callback, with a timeout, before proceeding" pattern -- the closest
  analog, `NestworldShutdownWatchdog`, doesn't wait for anything, it force-halts the JVM after a
  fixed delay if shutdown hasn't completed by then -- a safety net for when graceful shutdown
  hangs, not a graceful-wait mechanism itself. A module quiesce implementation needs a genuinely
  new bounded-wait-then-force pattern; the watchdog's "safety net, not the primary path" framing
  is reusable philosophy even though its mechanism (halt the whole JVM) is obviously too blunt
  for one module.
- **Timeout/failure behavior**: given the watchdog precedent above, "quiesce timed out" should
  probably transition the module to `FAILED` (Section C) rather than silently proceeding to
  `DISABLED` as if the drain succeeded -- proceeding silently would repeat exactly the "distinct
  identity proves nothing about resource cleanup" mistake this session already learned the hard
  way (Section 7/11).

**Addendum to Section B -- Core Safety (execution-model rules a module must never bypass)**:

Grounded directly in Section A's "Ownership/routing" row: `EntityMutationDispatcher` is the ONE
mechanism this entire session's Milestone 1 work proved safe for cross-region entity mutation,
through `EntityOperationRouter.md`'s deferred `damage()` audit, the block-interaction audit, the
Primitive Boundary Inventory, and finally `setVelocity`'s full implementation+verification. A
Module SPI that let a module reach a region-owned `Entity`/block from an arbitrary thread WITHOUT
going through this same dispatch discipline would silently undo all of that work. Concretely:

- **A module influencing scheduler/split-merge POLICY (Section A) must not thereby gain a side
  channel to entity mutation.** These are different privilege tiers even though both sound like
  "Core extension." The clean design is a policy module returning a DECISION (e.g. "split now" /
  "merge regions X and Y") that CORE then executes via its own existing, already-atomic
  mechanism (`RegionSplitManager`'s existing split/merge execution, unchanged) -- never a module
  directly touching region/entity state itself.
- **Any module-facing entity/block mutation capability must route through
  `EntityMutationDispatcher` (or an equivalent Core-owned dispatcher for a future non-entity
  resource), never a raw reference handed to module code.** This is exactly the discipline
  Milestone 1's `net.nestworld.modapi.entity.NestWorldEntities.setVelocity` already embodies for
  ordinary mods -- a Module SPI capability that mutates entities should be built the SAME way,
  just with a possibly-wider op set and a privilege check Mod API capabilities don't need.
- **Execution-context rules, tentative, not finalized without more evidence**: `CORE_CONTROLLED`
  (module supplies a decision/callback, Core decides when/where it runs -- matches the
  split-policy example above), `REGION_LOCAL` (module code runs ON a specific region's own
  thread, already-proven safe by `RegionThread.requestWork`'s existing production use, reused
  during this session's own `API1A7` same-owner visibility test), `MAIN_ONLY` (matches
  `ModCallbackRouter`'s existing `MAIN_THREAD_ONLY` classification, real precedent), and
  `CONCURRENT` (module code may run on its own thread with NO Core state access at all --
  the only tier compatible with unmediated module-owned execution). These four names are
  descriptive placeholders reflecting what Section A's evidence actually supports, not a
  finalized public enum -- GMEIL's own `ExecutionPolicy` enum (`MAIN_COMPAT/SERIAL_COMPAT/
  REGION_CERTIFIED/CORE_OWNED/EXTERNAL_ASYNC/DENY`) is a real, adjacent precedent worth
  reconciling with rather than inventing a second, overlapping vocabulary -- flagged as an open
  question (Section H), not resolved here.

---

## F. Reloadability Matrix

**Classloader audit (Section 7 of the directive) findings first, since reloadability
classification depends on them**:

`ClassLoader.close() != module unloaded` is not a hypothetical caution here -- it is the SAME
lesson this session's own Milestone 1 work already proved empirically. `docs/contracts/
api-1a7-findings.md`'s "Lifecycle identity replacement: PASS" finding (`VelocityBackendRegistry`,
two sequential server boots, confirmed distinct `MinecraftServer` object identity) explicitly
states it proves distinct identity, NOT that all resources (executors, listeners, classloader
references) from the prior generation were released -- the exact same gap applies one layer up
to a module's own classloader.

**Reference paths capable of retaining an old module classloader, enumerated from real Core
patterns found in this audit**:
- A Core-side static field holding a direct reference to a module object (`NestworldRegionSystem
  .INSTANCE` at `NestworldRegionSystem.java:44` is a real example of the SHAPE of this risk --
  plain `private static` field, not even `volatile`, holding a reference for the JVM's lifetime;
  a Module SPI must not let module-supplied objects end up in an equivalent Core-side static
  field without an explicit clear-on-unload step, mirroring `VelocityBackendRegistry.uninstall`).
- Any listener/callback registered with Core or with FORGE's OWN event bus and never
  unregistered (Section D) -- Forge's event bus itself is JVM-global, unrelated to server
  restarts, so a leaked listener there outlives even a full `NestworldRegionSystem` teardown.
- A `ThreadLocal` value set by module code on a Core-owned, long-lived thread (e.g. a
  `RegionThread`, which per Section A lives for the whole server process) -- `ThreadLocal`s are
  exactly the reference-retention hazard Section D already flagged as outside `ModuleContext`'s
  reach.
- Any lambda/closure captured inside Core's own scheduled-work or mailbox queues (Section D,
  "Scheduled work"/"Region mailbox work" rows) -- these already lack owner tags, so they also
  lack any "belongs to a since-unloaded classloader" signal.
- Coremod-installed transformations (Section A, "Compatibility policy" row) -- these modify
  bytecode of ALREADY-LOADED classes at JVM class-load time; there is no "undo" path once a
  class is loaded and initialized, regardless of what happens to any module's own classloader
  afterward.

**No `ModuleClassLoader` implementation in this pass** -- confirmed not done, per the hard
constraint.

**Reloadability classification, per extension category from Section A, each with cited
evidence**:

| Category | Classification | Evidence |
|---|---|---|
| Commands | `HOT_LOADABLE`-leaning | Brigadier's own tree structurally supports dynamic node add/remove (Section A); nothing in `NestworldCommand` exercises this today, but nothing prevents it either -- the clearest positive candidate in this whole audit |
| Read-only instrumentation/metrics subscription | `HOT_LOADABLE`-leaning | Purely additive/observational (Section A); no shared mutable state a module write could corrupt |
| Split/merge POLICY (decision only, not execution) | `HOT_RELOADABLE` plausible | `RegionSplitManager`'s actual split/merge execution is unchanged, atomic, Core-owned (Section A/B addendum) -- swapping which policy SUPPLIES the decision between evaluations is structurally safe if the policy call itself is side-effect-free |
| Config values (`NestworldLiveTuning`-pattern) | `HOT_RELOADABLE`, proven | `NestworldLiveTuning.chunkGenBudgetOverride` (`NestworldLiveTuning.java:14-19`) is ALREADY a real, live-reloadable Core value, RCON-settable, explicitly designed to avoid a restart-per-change JIT-storm confound -- direct, working precedent |
| Entity/region mutation capabilities (routed through `EntityMutationDispatcher`) | `HOT_RELOADABLE` for the CAPABILITY, `STARTUP_ONLY` or generation-gated for in-flight op continuity | The dispatcher itself doesn't care which module submitted an op; but Section E's generation-tagging gap means "safely swap the module without a stale in-flight op applying" is NOT yet solved -- classify as reloadable in principle, blocked in practice on Section E's unimplemented generation model |
| `net.nestworld.compat.NestworldModCompat`-shaped bootstrap flags | `RESTART_REQUIRED`, hard | Computed once via `Class.forName` at class-init (Section A); structurally cannot change post-bootstrap regardless of module privilege |
| Coremod/Mixin-shaped transformations | `RESTART_REQUIRED`, hard | JVM class-loading is a one-way operation; `META-INF/coremods.json`'s transformers run once, before the target class is ever used (Section A) -- this is the single clearest "no privilege tier changes this" category in the whole audit |
| Anything touching `NestworldRegionSystem.INSTANCE` directly | `RESTART_REQUIRED` today, `UNKNOWN` whether ever fixable | Plain static field (`NestworldRegionSystem.java:44`), already independently flagged this session (before MODULE-0) as a JVM-global-singleton risk; MODULE-0 does not resolve this, only re-confirms it's real and relevant here too |
| Scheduled/mailbox work (module-originated) | `UNKNOWN` | Blocked on Section E's generation-tagging gap -- cannot classify reloadability of something that isn't yet attributable to a module at all |

**Addendum to Section D -- Unload Correctness (Section 11 of the directive)**:

Candidate criteria, each marked by whether this audit can show a real mechanism or is merely
listing a desideratum:

- **Zero owned registrations** -- OBSERVABLE, if `ModuleContext` owns them (Section D's central
  recommendation); Core can literally enumerate the handle set and assert it's empty.
- **Zero queued old-generation work** -- NOT YET OBSERVABLE; requires Section E's generation
  tagging to exist first. Currently not even attributable, let alone provably zero.
- **Executors terminated** -- OBSERVABLE only if modules never get raw executor access (Section
  D's recommendation); otherwise unenforceable by construction.
- **No active callbacks** -- PARTIALLY OBSERVABLE at best; Core can track "is a Core-mediated
  callback currently executing" for anything routed through a Core-owned dispatch point, but has
  zero visibility into a module's own internal threads/callbacks it never told Core about.
- **No Core-owned strong refs to module objects** -- OBSERVABLE for the SPECIFIC static fields
  Core itself controls (the `VelocityBackendRegistry`-pattern fix, generalized); NOT observable
  for the general case, since Core cannot enumerate all references transitively.
- **Classloader collectible where testable** -- the ONLY item on this list with a genuine
  empirical test methodology already proven in this session: a `WeakReference` to the module's
  classloader, held after an explicit `System.gc()`, checked for `null` -- this is a real,
  buildable verification technique, but it is a TEST tool (used to validate the design after
  the fact), not a mechanism Core can rely on at runtime to guarantee anything before it happens.

**Honest bottom line for this section**: of the six candidate unload-correctness criteria, only
ONE (classloader collectibility) has a real, direct empirical test path, and even that one is a
test-time verification technique, not a runtime guarantee. "Successful unload" as a Core-issued
CLAIM ("this module is fully gone") is not something this audit can support proving in general
-- it can only be supported for the subset of resources `ModuleContext` centrally owns.

---

## G. Failure/Attribution Model

**What Core can reliably attribute today, evidenced**: `NestworldPins.PinReason`
(`NestworldPins.java:129-131`: `kind, key, mechanism, errorCount, threshold, exceptionClass,
exceptionMessage, topFrame, timestampMillis`) is a real, working, ALREADY-SHIPPED attribution
record -- it tracks WHY a mod got pinned (a mod-tick-error mechanism, not a module-lifecycle
one), but its shape is directly reusable: a `ModuleFailure` record could carry `moduleId,
moduleVersion, moduleGeneration, extensionPoint, thread, region (nullable), operation,
exceptionClass, exceptionMessage, timestampMillis` -- same discipline, new fields for the
module-specific identity axes (Section E).

**What Core CANNOT reliably attribute today, evidenced by gaps already found in this audit**:
region/thread attribution IS available for anything routed through `EntityMutationDispatcher`
(it already logs owner region and thread, per Milestone 1's own `[API1A7]` phase-trace
instrumentation) -- but only for work that goes through that one dispatcher. Section D already
established that scheduled work and mailbox work generally carry no owner tag; the same gap
applies to failure attribution -- Core cannot attribute a failure in DIRECT, untagged Core state
mutation to a specific module without Section E's generation/identity tagging existing first.

**Failure attribution is separate from state rollback -- stated with no exceptions found that
would justify blurring them**: nothing in this codebase implements or even partially supports
undoing an arbitrary state mutation. `EntityMutationDispatcher`'s own ops (Milestone 1) are
individually simple and mostly idempotent-by-construction (`setDeltaMovement`, `setSecondsOnFire`
etc. all simply overwrite a field) but NOTHING tracks a "previous value" to restore, and nothing
in Core's broader state (inventories, block state, scoreboard, etc. -- see this session's own
`GLOBAL_SHARED_UNOWNED` finding for one such unprotected category) has any undo mechanism either.
**A Module SPI must never claim arbitrary module mutations can be rolled back.** The only
rollback-shaped mechanism found anywhere in this codebase is `NestworldRegionSystem`'s region
LAYOUT save/restore on shutdown (persisted state, not live mutation undo) -- structurally
unrelated to undoing a module's runtime actions.

---

## H. Open Questions

1. **Execution-context vocabulary collision.** Section B's addendum proposed
   `CORE_CONTROLLED/REGION_LOCAL/MAIN_ONLY/CONCURRENT` as descriptive placeholders, but GMEIL's
   existing `ExecutionPolicy` enum (`MAIN_COMPAT/SERIAL_COMPAT/REGION_CERTIFIED/CORE_OWNED/
   EXTERNAL_ASYNC/DENY`, `net.nestworld.gmeil.ExecutionPolicy`) already occupies adjacent
   conceptual space. Should Module SPI execution-context rules reuse/extend GMEIL's vocabulary,
   or are they genuinely different concepts (GMEIL is about classifying OPAQUE mod callbacks
   Core doesn't control the shape of; Module SPI is about classifying Core's OWN extension
   points) that happen to sound similar? Not resolved here.
2. **Generation tagging's actual storage/plumbing.** Section E establishes that scheduled work
   and mailbox work need a `ModuleGeneration` tag to make quiescing/unload-correctness
   meaningful, but this audit did not design where that tag physically lives (a new field on
   `RegionMessage.EntityMutate`-shaped records, mirroring how Milestone 1 added `intentVersion`
   to `EntityMutationOp.SetVelocity`? A separate side-table keyed by operation id, mirroring this
   session's own diagnostic `opId` mechanism? Both have real precedent; neither was chosen.
3. **Where does discovery actually happen?** Section C's `DISCOVERED` state assumes some
   jar/classpath scanning mechanism exists or will exist -- this audit found NO existing
   Core-side classpath/jar discovery mechanism to build on (Forge's own mod-loading is the
   closest analog, but modules are explicitly meant to be a NestWorld-specific tier layered on
   TOP of already-loaded Forge mods, per the standing three-tier diagram -- not the same
   discovery moment).
4. **Does the `RegionSplitManager` policy-injection point (Section A/F) actually need to be
   built, or is it aspirational?** No module use case was validated against it beyond "the
   architecture owner's own example" -- worth confirming real demand exists before treating it
   as a priority extension point.
5. **What happens to a module's OWN static state (fields inside module-authored classes) on
   unload?** Section D was explicit that this is outside `ModuleContext`'s reach structurally.
   Is this an acceptable permanent limitation (module authors must write correctly, same
   discipline expected of any Java library), or does it need addressing via a mandatory
   base-class/interface contract that discourages static state in module code? Not resolved.

---

## I. Rejected Designs

- **A generic DI/service-locator container for modules.** Explicitly out of scope per the
  directive's own hard constraint, and no evidence found anywhere in this audit that Core's
  actual extension points (Section A) need one -- every real extension point found is either a
  narrow, specific hook (commands, config, split/merge policy) or already Core-mediated
  (dispatcher-routed entity mutation). A general service container would be solving a problem
  this audit found no evidence of.
- **Treating `ClassLoader.close()` (or any classloader-collectibility signal) as proof of
  successful unload.** Rejected explicitly and repeatedly throughout this audit (Sections 7/11)
  -- this session's own `VelocityBackendRegistry` lifecycle finding already proved distinct
  identity is not the same claim as resource release, and this audit found the SAME gap applies
  one layer up to modules. Any future MODULE-1 design that conflates these two claims would be
  repeating an already-identified mistake.
- **A Module SPI capability that lets a module directly hold/mutate a live `Entity`/region
  reference outside `EntityMutationDispatcher`'s discipline**, even under a "trusted, privileged"
  framing. Rejected per Section B's addendum -- `privileged != unrestricted`; this is exactly
  the hole the architecture owner explicitly asked this audit to check for, and this audit
  agrees it would undo Milestone 1's entire region-ownership verification effort if allowed.
- **Java-level security-sandbox framing for "privilege."** Rejected in Section B -- this
  codebase has no precedent for or mechanism supporting actual bytecode-level enforcement;
  claiming otherwise would misrepresent what capability declarations actually do (architectural
  convention, not a security boundary).

---

## J. Evidence References

Primary source files cited throughout (file:line citations given inline per finding, not
repeated here): `RegionScheduler.java`, `RegionSplitManager.java`, `EntityMutationDispatcher.java`,
`EntityOwnershipGuard.java`, `ModCallbackRouter.java`, `NestworldRegionSystem.java`,
`NestworldModCompat.java` (`net.nestworld.compat`), `RaceDetectTuning.java`,
`NestworldCommand.java`, `NestworldLiveTuning.java`, `NestworldTuning.java`,
`DimensionChangeScope.java`, `NestworldPins.java`, `net.nestworld.gmeil.ExecutionPolicy`/
`PolicyRegistry`, `META-INF/coremods.json`. Session documents cited: `docs/contracts/
api-1a7-findings.md` ("Lifecycle identity replacement: PASS", `API1A7-SEC11-ORDERING`,
`API1A7-DEPLOY-001` pattern of honest self-correction), `docs/RACE_CLASSIFICATION.md` (item 15,
mailbox-not-drained; `GLOBAL_SHARED_UNOWNED` entry), `docs/contracts/SetVelocityPrimitive.md`
and `docs/NESTWORLD_API_ARCHITECTURE.md` (Milestone 1's full design/verification trail, the
direct template for Section E's generation-tagging design-transfer argument). Verified negative
claims (things checked and NOT found): `ServiceLoader`/plugin-interface grep across
`net.nestworld` (Section A), initial incorrect "no `ThreadLocal`" claim caught and corrected
in-place (Section D) with real evidence (`DimensionChangeScope.java:23`) once actually checked.

---

## Verdict

**MODULE-0 PARTIAL**

Sufficient evidence exists to start SOME of MODULE-1's design work, but three specific
boundaries remain genuinely unresolved, not just undecided by preference:

1. **Generation/in-flight-work tagging (Section E) has no chosen implementation shape** -- this
   blocks quiescing (Section F addendum), unload-correctness for queued work (Section F
   addendum), and old-generation-work-after-replacement safety (Section E) all at once. This is
   the single largest concrete gap, and it is a DESIGN gap (a shape needs to be chosen), not a
   feasibility question (Milestone 1's `intentVersion` mechanism already proves the general
   pattern works).
2. **Execution-context vocabulary is unresolved (Open Question 1)** -- Module SPI needs this
   settled before ANY privileged capability (e.g. a split/merge policy hook) can be specified
   precisely, and this audit found a real, unaddressed tension with GMEIL's existing,
   adjacent-but-different `ExecutionPolicy` vocabulary that needs a deliberate decision, not
   silent duplication.
3. **"Successful unload" cannot be honestly claimed as a general Core guarantee** (Section F
   addendum) -- only for the subset of resources a `ModuleContext` centrally owns. MODULE-1 must
   either accept and clearly document this limited scope, or explicitly scope its FIRST
   capability to something where the limitation doesn't matter (e.g. a `STARTUP_ONLY`-classified
   capability, sidestepping unload-correctness entirely for a first slice -- mirroring how
   Milestone 1 itself narrowed to one primitive before generalizing).

No fundamental architectural blocker was found -- nothing in this audit suggests the three-tier
model (Mod API / NestWorldCore / Module SPI) is unworkable, and the `privileged != unrestricted`
principle the architecture owner asked this audit to formally check DOES hold structurally
(Section B/10 addendum) as long as entity/region mutation stays routed through
`EntityMutationDispatcher`-shaped mechanisms, never a raw module-held reference. That is real,
positive evidence, not a blocker -- hence `PARTIAL`, not `BLOCKED`.

Per the directive: **not proceeding to MODULE-1 design or implementation.** This decision belongs
to the architecture owner after reviewing this audit's three specific open boundaries above.
