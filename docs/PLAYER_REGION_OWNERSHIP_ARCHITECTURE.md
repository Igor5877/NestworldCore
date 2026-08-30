# Player Region-Owned Gameplay Execution — design (2026-08-27, NOT implemented)

Full architecture proposed by the user during the full-core-audit follow-up (2026-08-27), preserved
here per this project's own established practice (see `LOCAL_TICK_STAGE4.md`) of writing up serious
future directions even when not implemented immediately. **Status: design only.** The one piece that
WAS implemented immediately is the narrow Main→Region entity-mutation mailbox (see
`EntityMutationHelper.redirectFromMainThread`, project memory `player-region-ownership-architecture.md`
— fixes `Player.attack()`'s zero-guard finding from the audit without touching the network/threading
model at all).

## Why this came up

The full-core audit (`full-core-audit-2026-08-27.md`) found `Player.attack()` (melee, including
sweep AOE) had zero cross-region ownership guard — the single highest-frequency finding, since every
player hit goes through it. The existing `EntityMutationHelper.redirectIfForeign` pattern couldn't
cover it directly: it checks "is the calling thread a RegionThread", and `self` in `Player.attack()`
is the PLAYER, always ticked on the main thread — deliberately, per an existing, repeatedly-reaffirmed
design decision (see `NestworldDimensionRegion.java`'s own comment: "Tick players on the main thread —
their state is shared with the network thread, so region-thread ticking races on position and causes
rubber-banding" — still explicitly listed as "must stay global" even in the current Stage 4/5
free-running-region architecture, `LOCAL_TICK_STAGE4.md` lines ~1861-1867).

## The proposed model: Region Ownership + hybrid mechanisms (not full Actor/ECS/RCU)

Explicitly NOT a full actor-per-entity model (loses Minecraft's critical "operation sees spatially-
associated state synchronously" property, and turns every interaction into cross-actor messaging —
`Player → Zombie → message → Zombie → message → World`) and NOT ECS (Minecraft's existing data model —
Entity/BlockEntity/Level/Chunk/Capability/Forge events/mods/mixins/reflection/callbacks — would
require a full "Minecraft ECS compatibility layer", a separate colossal project on its own). Instead:

- **Base model: Region Ownership** (already exists) — Players/Entities/Chunks all owned by a
  `WorldRegion`, executed on that region's `RegionThread`.
- **Actor pattern**: only for mailbox/message delivery (already exists — `RegionMessage`/
  `nestworldPostMessage`).
- **Command Buffer**: for AOE/cross-region operations — one gameplay action fans out into multiple
  per-destination-region commands instead of taking locks.
- **Spatial indexing**: for entity lookup (already exists via `WorldGrid`/region bounds).
- **Lock-free ownership**: the primary synchronization mechanism (already the project's core
  philosophy — no new per-entity/per-region locks for the entity-tick hot path).
- **Snapshot/read-only structures**: only for specific expensive read-heavy systems (already used,
  e.g. `WorldRegion.EntitySnapshot` for `BoundaryEntityTransfer`), not for the whole world.

## Revised P0 framing

Not "move `Player.attack()` into the region" (too narrow) — **"Player Region-Owned Gameplay
Execution"**: the player's CONNECTION stays on network/main, but INPUT PROCESSING (movement, attack,
interact, use item, break/place block, inventory actions, entity interaction) executes on the
player's OWNER region thread.

```
Player
 ├── connection      ← network/main
 ├── input queue
 ├── gameplay state  ← owner region
 └── region owner
```

```
Netty:  packet → decode → basic validation → player input queue
Region: input → movement → collision → interaction → attack → inventory → entity mutation
```

**Critical detail the first-pass analysis missed**: this only actually resolves the rubber-banding
concern the original design avoided if BOTH sides move together — player ticking AND packet handling
(movement especially) onto the SAME region thread. Vanilla/Forge's packet-dispatch mechanism
(`PacketUtils`-style "ensure running on the expected thread") currently targets the main thread
unconditionally; making this model correct requires redirecting THAT dispatch target to the player's
current owner region instead. This is explicitly flagged as the single biggest NEW risk this design
introduces beyond the original P0 scope: it's a change to core network/packet threading, a much larger
and more unpredictable blast radius than any single-subsystem change made so far this session — every
mod that touches networking, or makes any assumption about "packet handling happens on the one true
server thread," is a potential compatibility break, in the same spirit as (but larger than) this
session's ModernFix mixin-compatibility incident.

## Input sequence numbers

```
PlayerInput {
    sequence = 18421
    tick = 92831
    type = ATTACK
    data = ...
}
```

Guarantees ordering (accept 18420, 18421, 18422...; reject a late-arriving 18419) — gives part of the
Actor model's benefit (ordered per-player input stream) without its eventual-consistency downsides
(the region still processes input synchronously in its own tick, not as an async mailbox with no
ordering guarantee).

## Cross-region attack via Command Buffer

Single target:
```
Region A                    Sweep/AOE:
Player                      Player Region A
   │ attack                        │
   ▼                          targets
target search                      │
   │                          ┌────┼────┐
   ▼                          ▼    ▼    ▼
Target = Region B             A    B    C
   │                          │    │    │
   ▼                          ▼    ▼    ▼
DamageCommand                cmd  cmd  cmd
   │
   ▼
Region B mailbox
```
One gameplay action → multiple per-destination commands, not locks. This is exactly what the
already-implemented `EntityMutationHelper.redirectFromMainThread` does today for the narrow
entity-mutation case — the Command Buffer model generalizes it to movement/interaction/block-break/
etc., not just `hurt`/`knockback`/`addEffect`/`ignite`.

## Mutation levels

```
Mutation
   ├── LOCAL   (same region)      → direct
   ├── CROSS   (different region) → mailbox command
   └── GLOBAL  (server/global state) → global queue/main scheduler
```
Simple, three-tier, easy to reason about and test.

## Region Handover Protocol (needed once player EXECUTION, not just entity data, moves per-region)

```
Region A
   │ freeze player input
   ▼
handover
   │
   ▼
Region B
   │ install player state
   ▼
B owns player
   │
   ▼
resume input
```
Sequence numbers ensure no input is lost/reordered across the handover. This is a NEW mechanism
beyond the existing entity-transfer-on-border-crossing logic (`BoundaryEntityTransfer`) — that moves
entity DATA; this would need to move INPUT PROCESSING OWNERSHIP atomically, including whatever's
in-flight in the input queue at the moment of transfer.

## What stays global (explicitly NOT moved into regions)

Server commands, global registries, network connection management, global scoreboard, some
Bukkit/Forge global APIs, global events, server lifecycle.

```
Server
   ├── Global/Main  (commands, registries, connections, scoreboard, global events, lifecycle)
   └── Regions      (chunks, entities, players+input, block entities, scheduled tasks, mutations)
```

## Full picture

```
NETWORK
   │
   ▼
Player Input
   │
   ▼
Player Owner
   │
   ▼
┌─────────────┐
│   REGION    │
│ Player      │
│ Entities    │
│ Chunks      │
│ BlockEntity │
└──────┬──────┘
       │
┌──────┴──────┐
▼             ▼
Local       Cross-region
mutation    mutation
   │             │
direct        mailbox → destination region
```

## Relationship to the existing Folia Actor-Model Staged Plan

This is a much more concrete, further-along elaboration of the "Mailbox→Ownership→Local Tick"
trajectory already noted in project memory (`folia-actor-model-staged-plan.md`). It should be treated
as a LATER stage of that plan, not a P0 bolt-on — the project is currently at "Mailbox+Ownership"
(Stage 4/5, free-running regions with cross-region messaging); this proposal is effectively "extend
Ownership to cover player input/execution, not just player entity DATA," a further stage requiring
its own careful, isolated, staged rollout — prototype behind a flag, validated specifically for
rubber-banding/input-latency regressions (the exact failure mode the current design avoids) before
any wider rollout, given the risk that reversing a currently-working (if imperfect) invariant could
turn a rare, quiet race into a constant, visible-to-every-player regression if done wrong.

## What shipped instead (2026-08-27, this session)

The narrow fix: `EntityMutationHelper.redirectFromMainThread(Entity target, EntityMutationOp op)` —
same mailbox primitive (`WorldRegion.nestworldPostMessage`, already "safe from any thread", already
supports a null `sourceRegion`), applied specifically to close `Player.attack()`'s zero-guard finding,
WITHOUT touching packet-dispatch threading or player ticking location. Backpressure-aware (falls back
to a direct call — no worse than the pre-fix behavior — if the target region's mailbox is already over
threshold, since a main-thread caller has no "own region" to hold a pending-retry queue the way
region-to-region senders do). This closes the CONFIRMED audit finding today; the fuller
Region-Owned-Gameplay-Execution model above remains available as the next real step whenever there's
room for its own dedicated, careful rollout.
