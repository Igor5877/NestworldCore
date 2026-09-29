# `EntityOperationRouter` — Core-internal contract (not public ABI)

Phase API-1A. Backs `net.nestworld.modapi.entity.NestWorldEntities` (the public facade — see
`docs/NESTWORLD_API_ARCHITECTURE.md`'s API-1A section). Mods never call this type directly.

Scope for API-1A: **one operation only — damage.** Per API-0.5's own governing rule ("existing
mod race fixes / internal capabilities do not automatically become public API"), the remaining six
`EntityMutationDispatcher` ops (`AddEffect`, `Ignite`, `ExtinguishFire`, `Knockback`, `Kill`,
`Discard`) are deliberately NOT carried over yet — see API-1E.

```
Responsibility:
  Route ONE semantic entity operation (damage) to the target's current
  owner, applied against freshly-reacquired state on both sides of the
  operation (target AND, where present, the causing/direct entity).
  Internal routing mechanism behind NestWorldEntities -- not itself a
  public API.

Does:
  - accept an EntityTarget{dimension, uuid} + an immutable DamageRequest
    payload (see "Payload shape" below) -- never a live Entity or
    DamageSource reference
  - resolve current owner via WorldGrid, scoped by dimension (see
    "Target identity")
  - hand off to the owner's execution context if caller isn't already there
  - reacquire the target entity fresh, by (dimension, uuid), on the
    executing thread
  - reconstruct a fresh DamageSource on the executing thread from the
    payload's identity fields, re-resolving causing/direct entities by
    UUID at that moment -- never reusing whatever those entities were at
    request time
  - revalidate the target still exists before applying
  - reroute (bounded) if ownership changed between request and execution

Does NOT:
  - accept arbitrary caller logic
  - return a result (matches EntityMutationDispatcher's proven
    fire-and-forget precedent)
  - guarantee delivery beyond what "Delivery semantics" below states
  - chase the target across dimensions if it migrated after the request
    was captured
  - guarantee the causing/direct entity is found at apply time -- see
    "Payload shape"

Threading:
  caller -> any permitted context
  routing initiation -> caller's own context
  MUST NOT perform blocking cross-region waits
  MUST NOT perform global dimension scans
  mutation -> owner's region-thread context only, on freshly reacquired
    state on every entity side touched by the operation

Invariant:
  No live Entity reference supplied by the caller -- and no live Entity
  reference reachable FROM any other parameter the caller supplied --
  may cross the public API boundary into an asynchronous ownership
  handoff. Proven necessary, not just precautionary: see "Why DamageSource
  needed this" below -- carrying the current DamageSource unchanged is a
  real, demonstrated write hazard on the causing entity's own state, not
  a theoretical one.

Target identity:
  EntityTarget = (ResourceKey<Level> dimension, UUID uuid). Deliberately
  excludes MinecraftServer -- that is runtime scope, passed alongside a
  request (`route(server, EntityTarget, operation)`), not baked into the
  identity type. Confirmed consistent with how EntityMutationDispatcher
  already treats server access today: it is never stored, only fetched
  inline via `target.level().getServer()` when needed
  (EntityMutationDispatcher.java:79,91).

  UUID alone is insufficient -- confirmed against
  WorldGrid.findOwningRegion(UUID) (WorldGrid.java:74-79): it scans only
  its OWN dimension's region list; WorldGrid is constructed per-dimension.
  No implicit cross-dimension scan will be added. If the target changed
  dimension between request and execution, the router treats this the
  same way as target-not-found for the PUBLIC outcome (see Delivery
  semantics) -- but see "Internal termination reasons" for why Core
  itself must not collapse these the way current code does.

Payload shape (`DamageRequest`, resolves this gate's finding):
  DamageSource's own fields (net/minecraft/world/damagesource/DamageSource.java:14-18):
    Holder<DamageType> type          -- registry reference, value-safe, carried as-is
    Vec3 damageSourcePosition        -- immutable value, carried as-is
    @Nullable Entity causingEntity   -- LIVE reference, NOT carried -- see below
    @Nullable Entity directEntity    -- LIVE reference, NOT carried -- see below

  Confirmed by direct trace (LivingEntity.java): causingEntity/directEntity
  are not merely read but actively MUTATED during normal damage
  application -- isDamageSourceBlocked reads directEntity for
  shield/arrow-pierce logic (LivingEntity.java:1269); hurt() writes to
  the causing entity's OWN state (setLastHurtByMob, lastHurtByPlayer,
  mob-anger/TamableAnimal targeting, LivingEntity.java:1122-1140+); die()
  calls causingEntity.awardKillScore(...) (LivingEntity.java:1306) --
  mutating a THIRD entity's state with zero ownership check, since only
  the target is currently ownership-validated/reacquired.

  DamageRequest therefore carries: type, damageSourcePosition,
  causingEntityId (UUID, nullable), directEntityId (UUID, nullable) --
  never the live Entity or the live DamageSource. On the owner thread,
  immediately before applying: reacquire causing/direct entities fresh by
  UUID (same dimension as the target, since a DamageSource's causing
  entity is conventionally in the same dimension as its target -- not
  independently verified this pass, flag for API-1D). If either lookup
  fails (entity gone/migrated), reconstruct DamageSource with that field
  null rather than failing the whole operation -- this loses some
  attribution (kill credit, anger target) but not correctness. Both
  sides of the operation get fresh state, not just the target.

  This is a stricter payload than EntityMutationDispatcher's OWN current
  `Damage(DamageSource, float)` record, which still carries the live
  DamageSource unchanged (EntityMutationOp.java, EntityMutationDispatcher.java:133-134)
  -- accepted there as a documented, deliberate tradeoff based on soak
  testing, not proof. NOT addressed by this document: whether the
  existing internal EntityMutationDispatcher.Damage path should also be
  tightened to match. That is an existing, already-shipped mechanism,
  separate from API-1A's own scope -- flagged for a separate decision,
  not silently fixed here.

Internal termination reasons (must remain distinguishable to Core,
  even though the public outcome is uniform -- see Delivery semantics):
    TARGET_REMOVED
    TARGET_CHANGED_DIMENSION
    TARGET_NOT_FOUND            (covers the above two if not distinguished yet)
    CAUSING_ENTITY_NOT_FOUND    (operation still applies, degraded attribution)
    REROUTE_LIMIT
    SERVER_STOPPING

  Confirmed this does NOT exist today: EntityMutationDispatcher.applyQueued
  collapses "removed" and "not found for any reason" into one branch
  (`if (target == null || target.isRemoved()) return;`,
  EntityMutationDispatcher.java:103) and level.getEntity(targetId) only
  searches the one ServerLevel passed in -- a genuinely-removed entity and
  one that merely changed dimension produce an identical null today. This
  distinction is real, new work for API-1A.1/1A.2, not something to
  assume is already available to build on.

Delivery semantics:
  TBD before contract stabilization -- still a blocker, not yet resolved
  by this document. Known-permitted outcomes (hop-limit drop, not-found
  no-op, shutdown-unresolved) are not consistent with an "operation is
  never silently lost" guarantee. Do not advertise best-effort vs.
  at-most-once vs. exactly-once until: (a) reroute-vs-apply race behavior
  is verified (can the old owner still apply an operation it already
  claimed WHILE a reroute for the same operation is in flight?), and (b)
  the causing/direct-entity-not-found degraded-attribution case above is
  accounted for in whatever guarantee is eventually stated.

Failure (outcome-only, no log-level specified -- Core telemetry policy,
  not public ABI):
  target not found (removed, or not in expected dimension)
      -> operation terminates without application; no exception
         propagated to caller; a diagnostic event MAY be emitted,
         carrying the internal termination reason above even though the
         public-facing outcome is uniform
  causing/direct entity not found at apply time
      -> operation still applies to the target, with that field null in
         the reconstructed DamageSource (degraded attribution, not a
         failure of the operation itself)
  owner changed between request and apply
      -> bounded reroute is attempted (exact hop policy: API-1A.2, reuse
         NestworldTuning.ENTITY_MUTATE_MAX_REROUTE_HOPS unless a reason
         to diverge is found)
  hop limit exceeded
      -> operation terminates without application; diagnostic event MAY
         be emitted
  server shutdown mid-flight
      -> UNRESOLVED for API-1A. Must be specified from real lifecycle
         evidence before stabilization. Only assertable now: must not
         crash the JVM/server.

Lifecycle:
  Router itself stateless per call. Underlying implementation
  (Forge-compat vs. Core-region) resolved per-MinecraftServer instance;
  exact mechanism deferred to API-1B/1C -- must not repeat the rejected
  global-static pattern (see docs/NESTWORLD_API_ARCHITECTURE.md's
  BackendResolver/WeakHashMap discussion, and NestworldRegionSystem.INSTANCE's
  own flagged latent risk of the same class).

Evidence/tests (API-1D, proposed, not yet written, scoped to damage only):
  1. same-thread apply (dispatch to self-owned entity)
  2. cross-region handoff, single hop, target and causing entity same region
  3. cross-region handoff where target and causing entity are owned by
     DIFFERENT regions -- the case this gate exists because of; assert
     no cross-region write happens outside the owner-thread reconstruction
  4. ownership changes between request and apply -> reroute happens
  5. hop-limit exceeded -> drop+log, no crash
  6. target removed before apply -> no-op, no crash, correct internal
     termination reason recorded
  7. target changed dimension before apply -> no-op, no crash, correct
     internal termination reason recorded (distinct from #6 internally)
  8. causing entity not found at apply time -> operation still applies
     to target, degraded attribution, no crash
  9. Forge dedicated, no Core -> compatibility fallback runs correctly
  10. singleplayer/integrated server -> compatibility fallback runs
      correctly
  11. stress: concurrent damage requests against entities migrating
      across regions rapidly, including attacker/target crossing
      different regions mid-flight -- no double-apply, no silently-lost
      operation outside the documented drop conditions above
```
