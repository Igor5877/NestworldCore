# `setSlot` — Inventory Set-Slot Bounded Mutation Primitive (API-1B.5/1B.6/1B.7)

**Status: API-1B SET-SLOT — PRIMITIVE VERIFIED / DISTRIBUTION & TRANSPORT BLOCKED** (architecture owner, 2026-10-01)

**Details (after API-1B.7 verification)**:
- **Primitive correctness: VERIFIED** on NestWorld (direct and region-routed paths) and on genuine
  stock Forge 1.20.1-47.4.20 — deterministic fixtures, real respawn/relogin, and 3×60 s stress
  with every primitive invariant at 0. Evidence: `docs/contracts/api-1b7-findings.md`.
- **Incarnation = object identity** (`PlayerIncarnationRegistry`); the earlier event-driven model
  was refuted live (fixture E) and removed. Forge events are not part of any correctness path.
- **Delivery reliability: BLOCKED by CORE-MAILBOX-MERGE-001** (`docs/RACE_CLASSIFICATION.md` #16):
  routine region merge/split strands queued messages with no terminal outcome (measured: 171/171
  missing terminals under forced churn). Not a primitive defect; separate Core gate; routing/stress
  tests to be re-run after its fix.
- **Distribution: BLOCKED by API1B7-PKG-002** — a consumer mod that embeds `:modapi` cannot boot
  on NestWorld (split package with NestWorld's own `forge` module). Packaging decision pending.
- Not exercised: integrated (singleplayer) server; a second `MinecraftServer` in one JVM.

Mirrors `docs/contracts/SetVelocityPrimitive.md`'s shape and rigor; the
second Bounded Mutation Primitive, chosen from `Inventory.setItem` (API-1B boundary audit, `SET`
row) after explicit GO for `SET` / NO-GO for the combined Inventory API (`INSERT`/`EXTRACT`/
`COMPARE-CONDITIONAL` all explicitly excluded — separate future gates, not this primitive's
scope).

## Incarnation model (current — supersedes the two historical sections below)

```
PlayerIncarnation   = one concrete live ServerPlayer object (reference identity, ==)
Target identity     = (player UUID, slot)                          InventoryTargetKey
Execution validity  = request.playerIncarnation == incarnationOf(object being mutated)
Temporal domain     = (player UUID, slot, incarnation)             InventorySlotVersionTracker
```
- `PlayerIncarnationRegistry.incarnationOf(player)` returns a monotonic opaque `long` token,
  minted on the first observation of that exact object. Lookup is by `==` only:
  `Entity.equals/hashCode` compare entity ids and `PlayerList.respawn` copies the old id onto the
  new object, so the old and new players are `equals()` — any equality-based map would conflate
  them. Weak references only; queued requests carry the token, never a `ServerPlayer`.
- Proof sketch: a token is minted once, for one object, and never reused (counter never reset,
  even across server generations); `incarnationOf(x)` returns a token only for an entry whose
  referent `== x`. Hence equal tokens imply the same object: a request stamped against A can never
  validate against any other object, independent of events, entity ids, or interleaving.
- At apply time both peers re-acquire the player by UUID and compare tokens before touching the
  version tracker (`INCARNATION_MISMATCH`). If the token matches but the object is already removed
  (direct path holding the old reference), the request is dropped as `TARGET_REMOVED`.
- Versions live per (player, slot, incarnation): old A/V1000 and new B/V1 are different domains and
  can never be compared. There is no bump and no reset step that has to happen "in time";
  domain cleanup (when the registry finds an object garbage-collected, and on server stop) is
  memory management only.

## HISTORICAL — implementation-time correction to this contract's own respawn recommendation (superseded)

This contract (below) recommended hooking `PlayerEvent.PlayerRespawnEvent`/
`PlayerEvent.PlayerLoggedInEvent` directly from the incarnation tracker itself, self-registered,
"so this works correctly on ordinary Forge with NestWorldCore entirely absent." **That specific
self-registration mechanism turned out to be impossible, confirmed by direct compiler evidence
during implementation, not assumed**: `:modapi` (where the tracker lives, for portability) only
ever compiles against `:clean` (this repo's genuinely-unpatched-vanilla project), which carries
none of `net.minecraftforge.event`/`.common`/`.eventbus` — `:modapi:compileJava` failed with
`package net.minecraftforge.common does not exist` when the self-registering draft was first
compiled. Per FORGE-001, those classes exist in this repo only as part of `:forge`'s own patched
sourceSet, inseparable from NestWorldCore.

**Resolution**: `PlayerIncarnationTracker` (in `net.nestworld.modapi.inventory`) stays a pure,
passive counter — no self-registration. The actual `@SubscribeEvent` bridge
(`onPlayerLoggedIn`/`onPlayerRespawn`, calling `PlayerIncarnationTracker.bump(uuid)`) lives in
`net.nestworld.region.NestworldRegionSystem`, which already has full Forge event-bus access.
**This reopens a real gap this contract's design did NOT anticipate — an OPEN BLOCKER, not a
benign degradation**: on ordinary Forge with NestWorldCore entirely absent, nothing calls `bump()`
at all, so `checkIncarnation` always returns `true`. An earlier revision of this section called
that "fails open, never corrupts" — **that wording was too strong and is withdrawn**: with no
incarnation signal, a request submitted against the pre-respawn (or pre-relogin) player can be
reacquired by UUID onto the NEW `ServerPlayer`/`Inventory` and applied there — the exact
respawn-semantics violation the incarnation mechanism was introduced to prevent. The contract's
respawn semantics therefore do **not** currently hold on stock Forge.

**Intended fix boundary (architecture owner, 2026-10-01)** — loader-specific event subscription
must NOT leak into the portable `:modapi` artifact:
```
:modapi                      PlayerIncarnationTracker + inventory primitive semantics
   ▲  passive lifecycle signal (login / respawn / logout)
runtime integration layer
   ├── NestWorldCore Forge bridge     (exists: NestworldRegionSystem)
   └── stock-Forge integration bridge (does not exist yet)
```
If the current packaging has no home for a stock-Forge integration layer, that is itself an
API-1B architectural finding to fix — not grounds to weaken this contract.

## HISTORICAL — incarnation boundary vs. event timing (source-traced, then confirmed live by fixture E; the reason the event model was dropped)

Formal definition adopted: **an incarnation is the lifetime of one concrete live `ServerPlayer`
object** — it begins when that object becomes reachable by UUID and ends when it stops being so.
Forge events must be mapped onto that definition, not the other way round. Mapping them
(`projects/forge/.../server/players/PlayerList.java`):

| Transition | New object published | Event that drives `bump()` today | Gap |
|---|---|---|---|
| respawn | `serverlevel1.addRespawnedPlayer` + `playersByUUID.put` (`:504-506`) | `firePlayerRespawnEvent` (`:509`) | new object reachable BEFORE bump |
| login / relogin | `playersByUUID.put` (`:214`), `addNewPlayer` (`:218`) | `firePlayerLoggedIn` (`:265`) | ~50 lines, same shape |
| logout | removed at `:383`/`:390` | `firePlayerLoggedOut` fires at `:367`, BEFORE removal | not bumped today at all |

Consequence: between publication and the event, an intent stamped with the OLD incarnation passes
`checkIncarnation` and, if its target is reacquired in that window, is applied to the NEW
player's inventory. The ordinary-Forge peer is not exposed (its apply runs on the main thread,
the same thread that executes `respawn`/`placeNewPlayer` atomically). The NestWorld peer IS
exposed in principle: a region thread can drain an ENTITY_MUTATE message concurrently with the
main thread's `respawn`, especially with free-running regions. Fixtures A–D cannot see this
(they call `bump()` directly). Fixture **E** is added to reproduce it deterministically before
any design decision is made.

## Already-closed evidence (cited, not re-derived here)

**`ItemStack.copy()` is a genuinely independent snapshot** — confirmed by the architecture owner
directly against real source (`projects/forge/src/main/java/net/minecraft/world/item/
ItemStack.java`): `copy()` (`:423-435`) builds `new ItemStack(item, count, this.serializeCaps())`
(fresh instance, capabilities captured via serialize/deserialize round-trip, not a shared live
handler reference) and deep-copies NBT via `this.tag.copy()` (`CompoundTag.copy()`,
`CompoundTag.java:438-441`, recursively calls `Tag::copy` on every nested tag). The
`ItemStack(Item, int, CompoundTag)` constructor used by `copy()` calls `forgeInit()`
(`:990-995`), which **immediately** (not lazily) calls `deserializeCaps(this.capNBT)` — the copy's
capability instance is freshly reconstructed at construction time. **Zero shared mutable state
survives a `stack.copy()` call** — count, item, damage value, NBT tag, and Forge capabilities are
all independently owned by the copy.

## NEW finding this gate closed: respawn creates a genuinely new incarnation, unconditionally

**Question**: does `ServerPlayer`/`Inventory` object identity survive respawn, such that a
pre-respawn `setSlot` intent applying post-respawn (via fresh reacquisition) is still writing to
"the same" inventory?

**Answer, settled by direct source trace, no live test needed to determine this specific fact**
(`projects/forge/src/main/java/net/minecraft/server/players/PlayerList.java:446-506`,
`ServerPlayer.restoreFrom`, `:1133-1173`):

- `PlayerList.respawn(ServerPlayer, boolean)` constructs `new ServerPlayer(this.server,
  serverlevel1, p_11237_.getGameProfile())` (`:461`) — **a brand-new object**, unconditionally,
  every respawn, no exceptions.
- `this.playersByUUID.put(serverplayer.getUUID(), serverplayer)` (`:506`) — the SAME UUID now
  maps to the NEW object. Any `PlayerList.getPlayer(uuid)` call after this line returns the new
  instance; any call holding a reference to the OLD instance is holding a genuinely stale
  reference, even though the UUID never changed.
- `ServerPlayer.restoreFrom` (`:1133-1173`) is where old-player state transfers to the new
  object. **Critically, `this.getInventory().replaceWith(p_9016_.getInventory())` — the ONLY
  place inventory contents transfer — is conditional** (`:1139-1154`): it runs only if the
  `keepEverything` param is true (e.g. an End-conquest respawn) OR the `keepInventory` gamerule
  is true OR the dying player was a spectator. **In the ordinary death-respawn case (no
  `keepInventory`, not spectator, not End-conquest), `replaceWith` is never called at all** — the
  new `ServerPlayer`'s `Inventory` is whatever its own constructor produces: a fresh, empty
  inventory, with zero relationship to the pre-death one.

**Conclusion: `ServerPlayer` and `Inventory` are BOTH new objects on every respawn, always — not
merely "possibly," and in the common case the new `Inventory` isn't even populated from the old
one.** UUID-based identity is stable across this; object-based identity is not, at all.

**Why this matters for `LATEST_INTENT_WINS` specifically, not just "state changed"**: version
numbers under the existing `(playerId, slot)` scheme are strictly increasing and never reset, so
in the COMMON case a stale pre-respawn intent naturally loses to any post-respawn write for the
same slot (higher version wins, as designed). The actual hazard is narrower but real: **a
pre-respawn intent still in flight (submitted, not yet applied) when respawn happens, where NO
post-respawn write has yet occurred for that slot** — its version is still "the highest seen" for
that `(playerId, slot)` key, so an unmodified `tryAccept` would ACCEPT it and write pre-death item
data into the player's fresh post-respawn (possibly just-emptied) inventory. This is a real
item-integrity hazard, structurally similar in spirit to the `INSERT` hazard the API-1B boundary
audit already flagged (stale data landing somewhere it has no business being), not a theoretical
edge case invented for completeness.

**Recommendation: an incarnation counter, checked alongside version, NOT folded into
`TargetKey` itself.** Consistent with the architecture owner's own principle that `TargetKey`
should carry only stable identity (`setVelocity` deliberately excludes dimension from its
`TargetKey` for the same reason — routing/execution state doesn't belong in identity). Concretely:
- `TargetKey = (UUID playerId, int slot)` — unchanged, stays minimal.
- A separate `currentIncarnation(UUID)` counter, incremented exactly once per confirmed new
  `ServerPlayer` object for that UUID — hookable via the REAL, already-existing
  `net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent`
  (`PlayerEvent.java:467-483`), fired with the NEW player via
  `ForgeEventFactory.firePlayerRespawnEvent` (confirmed real, not assumed) — and equally at
  initial login/reconnect (a reconnecting player is, by the same object-identity argument, also
  always a new `ServerPlayer` instance — standard, well-known vanilla behavior, not separately
  traced here since it's the identical shape).
- The submission-time payload carries `(version, incarnationAtSubmission)` — mirroring exactly
  how `intentVersion` itself already travels as a payload field, not identity, for `setVelocity`.
- `tryAccept` rejects (in addition to the existing version check) whenever
  `incarnationAtSubmission != currentIncarnation(playerId)` at apply time — any intent captured
  before a respawn/relogin is unconditionally stale after one, regardless of its version number.

This directly reuses two already-validated patterns from elsewhere in this project rather than
inventing a third: the identity-plus-monotonic-counter shape is `ModuleGeneration`'s own pattern
(MODULE-1) applied to a player instead of a module; the "everything before this point is
retroactively stale, without a real event making it happen individually" trick is MODULE-0.5's
own `QUIESCING`-sentinel-generation design, applied here as a respawn-sentinel instead.

**Not resolved by this gate, deliberately left for live verification**: the exact timing-race
behavior (an intent racing the actual moment of respawn) needs a real deterministic test, the
same barrier-controlled style `Api1A7DeterministicTests` already established — proposed as an
explicit evidence item below, not attempted here (design-only gate).

---

## The contract

```
Identity:
  TargetKey = (UUID playerId, int slot)

  `slot` is EXCLUSIVELY the stable flat index (0-40) Inventory's own three compartments expose
  (items[36] + armor[4] + offhand[1], confirmed in the API-1B boundary audit,
  Inventory.java:35-38,370-386). An AbstractContainerMenu slot index (assigned per-GUI-instance
  at menu construction, ephemeral) is NEVER valid here and must never leak into this API's public
  surface at all -- not as an accepted input, not as a documented alternative.

  Dimension is explicitly NOT part of TargetKey -- same principle setVelocity's contract already
  established: identity for LOOKUP and identity for ROUTING are different concerns. Unlike
  setVelocity, player-UUID lookup (PlayerList.getPlayer(uuid), confirmed a server-wide map,
  PlayerList.java's playersByUUID field) needs no dimension component at all -- a genuine
  asymmetry from entity lookup (level.getEntity(uuid) only searches one ServerLevel), not a
  simplification applied by convention.

Payload:
  SetSlotRequest {
    ItemStack snapshot         -- stack.copy() taken at the PUBLIC API boundary, before any
                                  thread/region handoff -- see the closed evidence above; the
                                  caller's original ItemStack must never cross the boundary live
    long intentVersion         -- assigned at submission (assignVersion, the linearization
                                  point -- identical discipline to SetVelocityIntentTracker)
    long incarnationAtSubmission -- captured at submission, from currentIncarnation(playerId) at
                                  that moment -- see the respawn finding above
  }

Ownership:
  Whichever region currently owns the player's ENTITY (re-resolved fresh at apply time, same
  mechanism setVelocity already proven) -- confirmed by the API-1B boundary audit: zero existing
  NestWorld code treats ServerPlayer specially for ownership purposes; a player is just an Entity
  to EntityMutationDispatcher/WorldGrid/NestworldOwnershipAssertions today, and raw Inventory
  manipulation touches only the player's own state (no second entity, unlike damage()'s
  causing-entity split) -- real, confirmed evidence for PLAYER_SINGLE_OWNER, not an assumption.

Reacquisition:
  server.getPlayerList().getPlayer(playerId)          -- fresh ServerPlayer, or null (see Failure)
    -> current dimension/position/region owner        -- read fresh, never cached from submission
    -> currentIncarnation(playerId)                    -- compared against incarnationAtSubmission
    -> player.getInventory()                            -- fresh Inventory object every time,
                                                           per the respawn finding -- never assume
                                                           this is "the same" Inventory reacquisition
                                                           returned on a prior call
    -> slot (bounds-checked against the reacquired Inventory's actual compartment sizes -- see
       Failure)

Temporal:
  LATEST_INTENT_WINS per TargetKey (playerId, slot) -- independent version domains PER SLOT, not
  per whole inventory (setSlot(p,5,X) and setSlot(p,6,Y) never arbitrate against each other).
  ADDITIONALLY gated by incarnation: an intent whose incarnationAtSubmission no longer matches
  the player's current incarnation is unconditionally STALE_INTENT_DROPPED regardless of its
  version number -- this is the one genuine extension beyond setVelocity's own temporal model,
  evidence-driven (the respawn finding above), not a default carried over uncritically.

Delivery:
  best-effort, at-most-once -- unchanged posture from setVelocity. No new delivery guarantee is
  claimed; if anything this primitive has MORE documented silent-loss surface (respawn-triggered
  incarnation staleness, in addition to setVelocity's target-not-found/shutdown-mid-flight paths).

Completion:
  No synchronous completion guarantee -- "return != completion," identical principle to
  setVelocity. A mod must not assume a slot's contents are visible immediately after the call
  returns, on any execution path, even ones that happen to be synchronous today.

Callbacks:
  None permitted inside the bounded mutation graph. Confirmed by the API-1B boundary audit:
  zero Forge/event references found anywhere in Inventory.java's full source. If a future
  reaudit of this primitive after implementation finds an unexpected callback surface
  (analogous to how the DamageSource gate found a hazard setVelocity's original design missed),
  that is a new finding requiring the same rigor, not something to assume against forever.

Explicit exclusions (do not scope-creep this primitive):
  INSERT, EXTRACT, AbstractContainerMenu (any GUI/menu-slot addressing), arbitrary Inventory
  access beyond the one target slot, arbitrary ItemStack callback, caller-provided Runnable,
  any compare-and-swap/conditional-write shape (confirmed not evidenced anywhere in vanilla
  Inventory by the boundary audit -- not invented here either).
```

## Evidence / tests (API-1B.7 — all run; details and traces in `api-1b7-findings.md`)

| # | Item | Result |
|---|---|---|
| 1 | Direct path | PASS — every lifecycle fixture has a direct variant (player unowned, `owner=null`) |
| 2 | Worker-thread submission | PASS — NestWorld direct (`Server thread`), routed (`NestWorld-Region-1`), stock Forge (marshaled, not applied on caller thread) |
| 3 | Migration/reroute | PASS — fixture B with real `REROUTE`; stress: 399 migrations/run |
| 4 | Logout-before-apply | PASS — routed `TARGET_NOT_FOUND`, direct `TARGET_REMOVED`, stock no-op; reconnected inventory untouched |
| 5 | Respawn-before-apply | PASS — real respawn, routed + direct + stock (C, D) |
| 6 | Stale-intent inversion | PASS — fixture B |
| 7 | Different slots independent | PASS — fixture SLOTS |
| 8 | Caller mutates original ItemStack (count, nested NBT, capability) | PASS — NestWorld and stock, mutation proven to precede apply |
| 9 | Reroute limit | PASS — `REROUTE_LIMIT_DROPPED` at hop 5, nothing applied |
| 10 | Lifecycle reset across server generations | PASS for the in-JVM state boundary (fixture GEN); second-server-in-one-JVM not exercised |
| 11 | Stress | PASS for primitive correctness (pinned, unpinned, churn); churn shows delivery loss attributed 171/171 to CORE-MAILBOX-MERGE-001 |
| 12 | Cross-boundary ordering (A/V1000 vs B/V1) | PASS — NestWorld routed + direct, stock Forge |
| — | Publication window (E) | PASS under the identity model (routed + direct, respawn + relogin); FAILED under the old event model — the reason for the redesign |
| — | Equals trap (same entity id, different object) | PASS — fixture C-SIM |
| — | Invalid slot | PASS — rejected at the facade, nothing assigned or dispatched |

## Not yet decided / explicitly deferred

- Whether `currentIncarnation` needs its own dedicated tracking class or can live inside a
  natural extension of `SetVelocityIntentTracker`'s existing shape (a genuinely new class vs.
  a generalized one) -- an implementation-time decision, not a contract question.
- The exact public method signature (`NestWorldEntities`-style facade vs. a new
  `NestWorldInventory` facade class) -- naming/placement, not contract substance.
- Whether `INSERT`/`EXTRACT` eventually reuse this primitive's incarnation-tracking mechanism
  once their own gates run, or need something else -- explicitly out of scope for this gate.
