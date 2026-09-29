# `setVelocity` — Bounded Mutation Primitive contract (API-1A.5)

**Status ladder** (do not conflate these — this document tracks all five):
```
API-1A.5   CONTRACT DESIGNED       PASS
API-1A.5P  PORTABILITY PoC         PASS
API-1A.6   IMPLEMENTED             PASS
API-1A.7   VERIFIED                CLOSED (2026-09-29) -- precise scope, not a blanket claim:
                                    verified for Forge dedicated, Forge integrated, NestWorld
                                    dedicated. NestWorld integrated PARKED as a named
                                    infrastructure gap (API1A7-NW-INTEGRATED-001), not silently
                                    skipped. Full evidence: docs/contracts/api-1a7-findings.md.
API-1A.8   TEMPORAL CONTRACT        CLOSED (2026-09-29) -- LATEST_INTENT_WINS implemented and
                                    VERIFIED: the exact V1/V2 ordering-inversion interleaving
                                    that API-1A.7 found now terminates V1=STALE_INTENT_DROPPED,
                                    V2=APPLIED (was V1=APPLIED, silently overwriting V2). Full
                                    evidence: docs/contracts/api-1a8-findings.md.
```
**This document now describes actual production behavior, verified, not a plan.** Code exists,
compiles against this repo's real build output, is `jdeps`-verified, and has been exercised
under real deterministic tests, real client boots, and a real 119,762-operation stress run with
all 5 critical invariants holding. **One real, evidence-backed gap found and deliberately NOT
fixed yet**: a genuine ordering inversion (submit V1,V2 → apply V2,V1 across a migration) — not
a contract violation (no ordering was ever promised), but a real temporal-semantics gap. Decided
fix (`LATEST_INTENT_WINS`, versioned per-target) is recorded in
`docs/NESTWORLD_API_ARCHITECTURE.md`'s "API-1A.7 — confirmed ordering inversion" section, NOT
yet implemented — deferred to a future, separate **API-1A.8 — Temporal Contract** gate. Where
this document and the code ever disagree, that is a bug in one of them to fix, not license to
trust one over the other by default.

First real API-1A primitive, chosen per API-1A.4's Primitive Boundary Inventory
(`docs/NESTWORLD_API_ARCHITECTURE.md`). Public surface:
`net.nestworld.modapi.entity.NestWorldEntities.setVelocity(Entity entity, Vec3 velocity)`.
Internal mechanism: one new case on the ALREADY-SHIPPED `EntityMutationDispatcher`/
`EntityMutationOp` (src/main/java/net/nestworld/region/), the same machinery `Ignite`/
`ExtinguishFire` already use in production, plus two new peer execution-semantics classes
under `src/main/java/net/nestworld/modapi/entity/` (see "Implementation" below). **No new
routing architecture, no generic capability, no ServiceLoader/SPI/DI, no arbitrary-Runnable
escape hatch** — scope held exactly to what was approved.

## Implementation (API-1A.6, landed 2026-09-29)

| File | Role |
|---|---|
| `net/nestworld/region/EntityMutationOp.java` | +`SetVelocity(Vec3)` record |
| `net/nestworld/region/EntityMutationDispatcher.java` | +one `applyDirect` case |
| `net/nestworld/modapi/entity/NestWorldEntities.java` | public facade, `setVelocity(Entity, Vec3)` |
| `net/nestworld/modapi/entity/VelocityExecutionBackend.java` | Core-internal SPI (peer contract) |
| `net/nestworld/modapi/entity/VelocityBackendRegistry.java` | install/uninstall state machine |
| `net/nestworld/modapi/entity/ForgeVelocityExecution.java` | ordinary-Forge peer (threading contract) |
| `net/nestworld/region/NestworldVelocityBackend.java` | NestWorldCore peer (delegates to dispatcher) |

Every file above carries a class-level `Responsibility/Does/Does NOT/Threading/Invariant/
Failure/Lifecycle/Evidence-tests` javadoc block — that javadoc IS part of this contract, not a
separate description of it. Read the code's own javadoc for the authoritative per-class detail;
this document gives the cross-cutting picture.

All facts below are verified against real source this same day (2026-09-28) by a dedicated
read-only audit — file:line citations throughout, not assumption.

```
Public API semantics:
  net.nestworld.modapi.entity.NestWorldEntities.setVelocity(Entity entity, Vec3 velocity)
  Sets the target entity's velocity (Entity.setDeltaMovement equivalent) to the given value.
  Fire-and-forget: no return value, matching EntityMutationDispatcher's existing op contract.
  Mod-author-facing signature accepts a live Entity for ergonomics; stripped to identity at
  the facade boundary (see "Capture semantics").

Public facade vs. internal router boundary -- IMPLEMENTED (API-1A.6, 2026-09-29):
  NestWorldEntities (net.nestworld.modapi.entity) -- public, stable-intent API surface. Accepts
  Entity + Vec3. Never exposes Region/RegionThread/WorldGrid/mailbox/EntityMutationDispatcher
  types in its own signature -- confirmed by actual `jdeps` output against the compiled class,
  not just code-reading: net.nestworld.modapi.entity's classes resolve only to
  net.minecraft.world.entity.Entity, net.minecraft.world.phys.Vec3, org.slf4j.*, and JDK types.
  Delegates to whichever of the TWO PEER execution semantics is currently active (see
  "Ownership resolution" below) -- there is no separate "internal router" class for
  setVelocity; NestWorldCore's own peer (net.nestworld.region.NestworldVelocityBackend) is a
  one-line delegate straight into the already-shipped EntityMutationDispatcher.dispatch(Entity,
  new EntityMutationOp.SetVelocity(velocity)) case. A dedicated router class was deliberately
  NOT added -- unjustified for one primitive that maps directly onto an existing dispatcher
  case; adding one would repeat the exact "abstraction before repetition" mistake API-1's first
  draft already made (docs/NESTWORLD_API_ARCHITECTURE.md, "API-1A narrowed and specified").
  Backend selection itself lives in net.nestworld.modapi.entity.VelocityBackendRegistry, also
  Core-internal (not called by mods) -- see "Backend lifecycle" below.

Target identity:
  EntityTarget = (dimension: ResourceKey<Level>, uuid: UUID). Same identity shape as
  EntityOperationRouter.md's (deferred but still-correct) reasoning: UUID alone is
  insufficient, confirmed against WorldGrid.findOwningRegion(UUID)'s per-dimension scoping.
  Do not use UUID globally without dimension -- this repeats verbatim from the damage()
  contract because the underlying WorldGrid constraint is unchanged. Used directly by
  ForgeVelocityExecution's own re-acquisition (see "Forge threading contract" below); the
  NestWorld peer inherits it via EntityMutationDispatcher's own identity handling.

Capture semantics -- IMPLEMENTED, corrected from the original draft:
  NestWorldEntities.setVelocity itself does NOT strip to identity before delegating -- it hands
  the live Entity straight to whichever peer is active, because a synchronous, same-call-frame
  hand-off to an already-audited capturing mechanism is not the same hazard as retaining a live
  reference across an actual thread/async boundary:
    - NestWorld peer: EntityMutationDispatcher.dispatch(Entity, op) is the ALREADY-AUDITED
      component responsible for stripping to (UUID) before anything crosses a thread boundary
      (RegionMessage.entityMutate captures the UUID, never the Entity) -- re-verified unchanged
      by this contract, not re-implemented.
    - Forge peer (ForgeVelocityExecution): this one DOES its own capture, deliberately, because
      it does its own ad-hoc thread marshal (server.execute(...)) rather than reusing an
      already-audited mechanism -- capturing (dimension, uuid) before marshaling and
      re-acquiring fresh on the server thread, exactly the discipline this whole document
      argues for. See "Forge threading contract" below.
  In both peers, the live Entity reference itself never survives past the synchronous call that
  received it. Confirmed: EntityMutationOp's existing sealed interface carries no Entity-typed
  field for any of its zero-live-reference ops (Ignite(int), ExtinguishFire()) -- SetVelocity
  (Vec3) follows that exact shape, not Damage(DamageSource, float)'s live-reference shape.

Vec3 payload safety (verified, not assumed):
  net.minecraft.world.phys.Vec3 (projects/forge/src/main/java/net/minecraft/world/phys/
  Vec3.java): three `public final double` fields (x, y, z), set once in the constructor, zero
  setters, zero back-references to any Entity/Level/mutable object anywhere in the class.
  Contrast with DamageSource's causingEntity/directEntity (live Entity fields, actively
  MUTATED during normal use, per EntityMutationOp.java's corrected javadoc and
  docs/NESTWORLD_API_ARCHITECTURE.md's API-1A.3 findings) -- Vec3 has no analogous field.
  Nothing to strip, nothing to reconstruct on the owner thread. SetVelocity(Vec3) is exactly
  as safe to carry unchanged across the mailbox handoff as Ignite(int seconds) already is,
  confirmed shipped and running today.

Ownership resolution / handoff / fresh reacquisition / revalidation:
  Reuses EntityMutationDispatcher.dispatch()/applyQueued() verbatim -- this is the load-bearing
  design decision of this whole contract, not a simplification. Concretely, per direct trace:
    dispatch(Entity, EntityMutationOp) -- EntityMutationDispatcher.java:57-92
      - unmanaged level or unowned target -> applyDirect immediately (:58-69)
      - caller already on owner's RegionThread -> applyDirect (:71-76)
      - else -> posts RegionMessage.entityMutate(...) to the owner's mailbox (:78-91)
    applyQueued(UUID, op, rerouteHops, region, level) -- EntityMutationDispatcher.java:101-128
      1. level.getEntity(targetId) -- FRESH reacquisition, not the captured reference (:102)
      2. re-resolves CURRENT owner from scratch, never trusts the mailbox destination (:106-108)
      3. owner changed -> bounded reroute (see below) or apply, mutually exclusive (:109-127)
    applyDirect -- plain instanceof switch (:132-152); SetVelocity's case would be
      `entity.setDeltaMovement(sv.velocity())`, structurally identical to Ignite's
      `entity.setSecondsOnFire(i.seconds())` at :140.
  No new capture/handoff/reacquisition/revalidation code is being designed here -- it already
  exists, is already proven (Ignite/ExtinguishFire, live in production), and this contract's
  only real job is confirming SetVelocity's payload shape fits it cleanly (it does, see above).

Reroute semantics (shipped protocol, verified, not designed fresh here):
  Hop limit: NestworldTuning.ENTITY_MUTATE_MAX_REROUTE_HOPS, default 5, via
    `-Dnestworld.entityMutateMaxRerouteHops` (NestworldTuning.java:951-952).
  Hop limit exceeded -> message DROPPED with LOGGER.warn, no crash, no retry
    (EntityMutationDispatcher.java:110-119).
  Fast ping-pong (owner changes again during an in-flight reroute) -> handled recursively,
    bounded by the same hop limit, explicitly NOT loop-proof against faster-than-drain
    ping-pong -- accepted as "best-effort... an edge case, not the common path" per the
    dispatcher's own class javadoc (:41-43). This contract inherits that acceptance, does not
    relitigate it.
  Double-apply: not possible from a single applyQueued invocation -- reroute and apply
    branches are mutually exclusive (:109-127).

Bounded mutation set:
  Exactly one field: the target entity's own delta-movement vector
  (Entity.setDeltaMovement(Vec3), the method setVelocity ultimately calls). No neighbor
  cascade, no Forge event, no second entity, no global singleton. This is the entire
  contract's safety argument -- verified by the API-1A.4 inventory pass finding zero
  ForgeHooks/EVENT_BUS calls anywhere near this mutation, independently re-confirmed by this
  gate's own audit.

Internal termination reasons (Core-internal, distinguishable even though public outcome is
  uniform -- same pattern as EntityOperationRouter.md's, carried forward unchanged since the
  underlying dispatcher hasn't changed):
    TARGET_REMOVED
    TARGET_CHANGED_DIMENSION      (see "Known, inherited, NOT fixed by this contract" below)
    TARGET_NOT_FOUND              (collapses the above two today -- inherited gap, not new)
    REROUTE_LIMIT
    SERVER_STOPPING               (see below -- confirmed unhandled, not merely undocumented)

Delivery semantics (confirmed, not assumed -- this is stricter than EntityOperationRouter.md's
  "TBD" because the underlying mechanism is shipped and its real behavior was directly traced):
  NOT exactly-once, NOT guaranteed-delivered. Two CONFIRMED silent-loss paths exist in the
  shipped mechanism this primitive reuses:
    (a) target not found at apply time (removed OR migrated dimension, collapsed identically)
        -> SILENT no-op, NO log at all (EntityMutationDispatcher.java:103-105).
    (b) any message still sitting in a region's mailbox when that region's RegionThread.shutdown()
        runs -> silently dropped when the process exits; no drain, no apply, no log
        (RegionThread.java:236-239,251-253; NestworldDimensionRegion.java:1125-1130).
  Best-effort, at-most-once (never double-applies, per "Reroute semantics" above), NOT
  never-lost. Do not advertise stronger semantics than this -- both loss paths are confirmed
  by direct trace, not theoretical.

Failure (outcome-only, no log-level specified as public ABI):
  target not found (removed, or migrated dimension) -> silent no-op, no exception, no log
  hop limit exceeded -> operation dropped, WARN logged (Core-internal, not public-facing)
  server shutdown mid-flight -> UNHANDLED, not merely UNRESOLVED-on-paper: confirmed no
    drain/apply/log step exists anywhere in the shutdown path. Does not crash the JVM/server
    (thread simply exits its loop), but any in-flight setVelocity call can be silently lost.

Backend lifecycle -- IMPLEMENTED (API-1A.6), explicit state machine, not a bare forever-lived
  static field:
  ```
  UNINSTALLED
      | server starting  (NestworldRegionSystem.onServerStarting)
      v
  INSTALLED(server A)
      | server stopping  (NestworldRegionSystem.onServerStopping)
      v
  UNINSTALLED
      | new integrated-server world starts (same JVM)
      v
  INSTALLED(server B)
  ```
  Lives in net.nestworld.modapi.entity.VelocityBackendRegistry (Core-internal, mods must never
  call install/uninstall). `uninstall(server)` only clears the backend if `server` matches the
  token recorded at install time -- a delayed/out-of-order uninstall from a previous
  integrated-server session (same JVM, world A stopped, world B already started) cannot clobber
  world B's freshly-installed backend. This is a NEW API surface; it deliberately does not
  repeat NestworldRegionSystem.INSTANCE's own already-flagged JVM-global-singleton lifecycle
  risk. `NestWorldEntities.setVelocity` itself is stateless per call -- it just reads whatever
  `VelocityBackendRegistry.current()` returns at call time.

Dedicated-server behavior:
  Confirmed: full region-sharded routing applies, exactly as described above. No special
  casing needed for this primitive.

Integrated-server (singleplayer) behavior -- CORRECTED from earlier aspirational doc language:
  docs/NESTWORLD_API_ARCHITECTURE.md previously described the (unbuilt) Mod API's intended
  design as routing singleplayer through a CompatibilityBackend, region sharding being
  "dedicated-server-only." **This is not what NestworldRegionSystem does today.** Verified by
  direct trace: NestworldRegionSystem.onServerStarting/onServerStopping
  (NestworldRegionSystem.java:168-201) are plain @SubscribeEvent handlers with zero
  IntegratedServer/DedicatedServer branching anywhere in net.nestworld.* (one unrelated hit in
  patched vanilla's ServerLifecycleHooks.java:132, not NestWorld code). init(MinecraftServer)
  (:265-326) initializes full region sharding for ANY MinecraftServer that fires the event,
  including IntegratedServer (a MinecraftServer subclass that fires the same Forge lifecycle
  events). Conclusion: setVelocity behaves IDENTICALLY on integrated and dedicated servers
  today -- both get real region-routed dispatch, not a fallback. This contract records this as
  fact, not as a design choice; whether a future compatibility-fallback tier should be built
  for singleplayer is a separate, not-yet-opened question this contract does not resolve.

  **Standing correction -- two independent axes, do not conflate them in future contracts:**
  this document and its predecessors previously treated "integrated server" as synonymous with
  "falls back to a non-region execution model." That was wrong. Execution model (vanilla vs.
  region-sharded) and physical environment (dedicated vs. integrated) are orthogonal:

  ```
                    Execution model
                Vanilla         Regions
             ┌───────────┬───────────────┐
   Forge     │     X     │       -       │
   NestWorld │     ?     │       X       │   <- confirmed cell: NestWorld always uses
             └───────────┴───────────────┘      Regions, on BOTH physical environments
                    Physical environment (orthogonal, separate axis)
                    dedicated  /  integrated
  ```

  "Integrated" tells you nothing about which execution model is in effect -- it never implied
  CompatibilityBackend, that implication was simply never true. Every future primitive contract
  must state execution-model behavior and physical-environment behavior as two separate,
  independently-verified claims, not infer one from the other.

Ordinary Forge behavior WITHOUT NestWorldCore -- RESOLVED (API-1A.5P PoC, PASS, 2026-09-29):
  A real PoC (build/run/`jdeps` evidence, not code-reading alone -- full report in
  docs/NESTWORLD_API_ARCHITECTURE.md's "API-1A.5P" section) confirmed the artifact/classloading
  boundary this primitive needs is achievable with zero reflection, zero ServiceLoader/SPI/DI,
  and zero Core types in the modapi package's ABI. The actual API-1A.6 implementation is built
  on exactly that boundary from the start -- confirmed again here, independently, by running
  `jdeps` against net.nestworld.modapi.entity's real compiled classes (not the PoC's throwaway
  ones): the only non-JDK types resolved are net.minecraft.world.entity.Entity,
  net.minecraft.world.phys.Vec3, and org.slf4j.* -- zero net.nestworld.region/adapter/
  EntityMutationDispatcher references anywhere in the package.

  This does NOT yet prove the exact deployed-jar/module-separation question from the original
  concern (NestworldCore is still compiled into one monolithic `:forge` output today -- that
  build-topology fact is unchanged) -- what it proves is the narrower, load-bearing claim this
  contract actually needs: net.nestworld.modapi.entity's OWN classes are written such that they
  would still load and link correctly if Core's classes were absent, whenever that build-topology
  work eventually happens. That is a currently-true property of the code as written, verified
  by jdeps against the real build output, not a promise about deployment packaging.

Forge threading contract -- IMPLEMENTED (API-1A.6), corrected from the original "direct
  setDeltaMovement call" draft per architecture-owner review: a bare `backend == null ->
  entity.setDeltaMovement(velocity)` would let an ordinary Forge mod mutate vanilla entity state
  off-thread from an arbitrary worker thread -- exactly the abstraction failure this whole API
  exists to prevent. The actual Forge-peer implementation
  (net.nestworld.modapi.entity.ForgeVelocityExecution):
  ```
  ordinary Forge
      |
      +-- already correct server execution context (MinecraftServer.isSameThread())
      |      `-- apply directly (entity.setDeltaMovement(velocity))
      |
      `-- foreign thread
             `-- capture (dimension, uuid) -- NOT the live Entity
                    `-- marshal via server.execute(Runnable)
                           `-- re-acquire entity fresh by identity
                                  `-- apply if still present, else silent no-op
  ```
  Uses `Entity.getServer()`/`MinecraftServer.isSameThread()`/`.execute(Runnable)`/
  `.getLevel(ResourceKey)` -- all confirmed-existing vanilla API (verified against
  projects/forge/src/main/java/net/minecraft/... before writing this code, not assumed). This
  is a self-contained peer implementation, not a "fallback" for the NestWorld peer -- both
  implement the same contract, selected by whether a backend is currently installed (see
  "Backend lifecycle" above). No "fallback" language appears anywhere in the actual code's
  javadoc, per the architecture-owner's explicit correction that this framing implies a wrong
  hierarchy (Core = real, Forge = degraded) that this contract does not intend.

What this contract GUARANTEES:
  - setVelocity mutates ONLY the target entity's own velocity field.
  - No live Entity/DamageSource-shaped reference crosses the async handoff.
  - No caller-supplied or mod-supplied callback executes as part of this operation.
  - No Forge/mod callback graph is entered (confirmed zero ForgeHooks/EVENT_BUS calls on this
    path).
  - At-most-once application (no double-apply, confirmed by mutually-exclusive reroute/apply
    branches).

What this contract explicitly does NOT guarantee:
  - Delivery. Two confirmed silent-loss paths exist (target-not-found-at-apply,
    shutdown-mid-flight) -- inherited from the underlying shipped mechanism, not introduced by
    this primitive, but not fixed by it either.
  - Correct behavior when target changes dimension before apply on the NestWorld peer --
    collapsed identically to "removed" today (inherited gap, see below).
  - Loop-proof rerouting under faster-than-drain ownership ping-pong (accepted, bounded by hop
    limit, not eliminated).
  - A separately-packaged/deployed "Forge without Core" artifact -- API-1A.5P proved the CODE-
    LEVEL boundary holds (verified by jdeps), not that such an artifact is buildable today from
    this repo's Gradle setup (still one monolithic `:forge` output) -- see "Ordinary Forge
    behavior" above for the precise scope of what was and wasn't proven.

Known, inherited, NOT fixed by this contract (pre-existing gaps in EntityMutationDispatcher
  this primitive reuses as-is -- flagging them here is due diligence, not a promise to fix them
  as part of API-1A.5/1A.6):
  - TARGET_CHANGED_DIMENSION is not distinguished from TARGET_REMOVED
    (EntityMutationDispatcher.java:102, level.getEntity only searches the one ServerLevel
    passed in) -- already noted as an open gap in docs/contracts/EntityOperationRouter.md:128-135
    for damage(); confirmed to apply identically here since it's the same dispatcher code.
  - No mailbox drain on RegionThread.shutdown() (RegionThread.java:236-239,251-253;
    NestworldDimensionRegion.java:1125-1130) -- any in-flight operation of ANY
    EntityMutationDispatcher op type, not just setVelocity, can be silently lost at shutdown.
    This is a Core-wide gap, out of scope for a single-primitive contract to fix.

API-1A.6 acceptance criteria -- status as of the implementation landing (2026-09-29):
  1. Public ABI (net.nestworld.modapi.* -> ZERO net.nestworld.region.*/dispatcher/internal
     imports) -- MET, verified by real `jdeps` against the compiled classes (see "Ordinary
     Forge behavior" above), not asserted.
  2. Payload (caller Entity used only for immediate identity capture, NEVER stored in a queued
     Core operation) -- MET on the Forge peer (ForgeVelocityExecution captures (dimension,
     uuid) before marshaling, never closes over the live Entity); MET on the NestWorld peer by
     inheritance from EntityMutationDispatcher's own already-audited capture discipline
     (unchanged by this work).
  3. Identity (ResourceKey<Level> + UUID, never bare UUID) -- MET, used explicitly in
     ForgeVelocityExecution; inherited by the NestWorld peer via EntityMutationDispatcher/
     WorldGrid's existing per-dimension scoping.
  4. Core backend (capture -> owner lookup -> mailbox -> reacquire -> ownership revalidation/
     reroute -> setDeltaMovement(Vec3)) -- MET, unchanged shipped mechanism, one new case.
  5. Mutation boundary (exactly the audited SetVelocity primitive -- no arbitrary Runnable, no
     mod-supplied callback, no generic submit escape hatch) -- MET, EntityMutationOp stays a
     closed sealed interface, one new record, no new generic capability added.
  6. Delivery (best-effort, at-most-once, NOT guaranteed) -- MET, documented plainly above, not
     oversold.
  7. Backend lifecycle (install once per MinecraftServer lifecycle, uninstall on shutdown, no
     stale backend across integrated-server restart) -- MET, see "Backend lifecycle" above;
     server-token check is the actual staleness guard, not just a comment promising one.
  8. Ordinary Forge (API artifact loads without Core; explicit threading contract) -- MET, see
     "Ordinary Forge behavior"/"Forge threading contract" above.
  9. Integrated NestWorld (uses Core region backend exactly like dedicated NestWorld) -- MET by
     construction: VelocityBackendRegistry.install is called from onServerStarting
     unconditionally, with no dedicated/integrated branch anywhere (matches the confirmed
     "NestworldRegionSystem has zero server-type branching" finding).
  10. Evidence (fresh HEAD 47.4.233 build + dedicated Forge + integrated Forge + dedicated
      NestWorld + integrated NestWorld) -- PARTIAL. Standalone compile + real `jdeps` against
      THIS repo's actual current build output (criteria 1 confirmed this way, not against a
      borrowed older rig, addressing the API-1A.5P PoC's own recommendation). Full four-way
      live-server evidence is API-1A.7's job (below), not yet run.

Negative tests (API-1A.7, not yet written -- as important as the positive path, per explicit
  instruction not to treat them as an afterthought):
  1. entity removed before apply -> no mutation (silent no-op, matches documented behavior --
     a surprise log or exception here would mean the dispatcher changed since this was written)
  2. entity changes region -> bounded reroute (NestWorld peer only; inherits
     EntityMutationDispatcher's existing reroute-hop mechanism, hop limit 5)
  3. entity changes dimension -> no wrong-world mutation (currently indistinguishable from
     "removed" on the NestWorld peer -- test should assert the CURRENT gap behavior, not an
     aspirational fixed one, until that Core-wide gap is separately addressed; the Forge peer's
     own re-acquisition is dimension-scoped via `server.getLevel(dimension)` and correctly
     no-ops if the entity isn't in that specific level any more)
  4. server stops with a queued request in flight -> loss allowed by the current contract
     (RACE_CLASSIFICATION.md item 15) -- test should assert no crash, not assert delivery
  5. backend absent -> ordinary Forge threading-contract behavior runs, NOT
     NoClassDefFoundError/ClassNotFoundException
  6. Core classes absent from the classpath -> the modapi artifact still loads and links
     (re-run of the API-1A.5P PoC's own evidence, as a proper regression test rather than a
     one-off experiment)
  7. repeated integrated-server start (same JVM, world A stop then world B start) -> no stale
     previous-server backend -- directly exercises VelocityBackendRegistry's server-token check

Evidence/tests carried over from the original draft, still applicable to the NestWorld peer
  specifically (API-1A.7, not yet written):
  1. same-thread apply (dispatch to self-owned entity)
  2. cross-region handoff, single hop
  3. hop-limit exceeded -> drop+warn-log, no crash
  4. rapid ownership ping-pong -> confirm bounded-not-loop-proof behavior matches the
     documented acceptance, not a silent regression into an infinite reroute loop
  5. Vec3 payload integrity -- confirm the exact value applied on the owner thread equals the
     value passed at the call site (no corruption across the mailbox handoff)
  6. concurrent setVelocity calls against an entity migrating across regions rapidly -- no
     double-apply, no crash, losses only via the documented paths above
  7. dedicated server -- full flow works as specified
  8. integrated (singleplayer) server -- full flow works IDENTICALLY to dedicated (this test
     should FAIL if singleplayer ever silently starts behaving differently, since no such
     distinction is currently designed or expected)

**API-1A.7 must be a real compatibility/concurrency test gate, not another design audit** --
per explicit instruction. The acceptance-criteria table above and the two test lists are its
starting checklist, not a substitute for actually running them.
```
