# #31 — Player Region Execution: controlled project roadmap (2026-08-27)

Status: design phase, no implementation started. Gated on Phase 10 (#30) having closed with no
outstanding P0s — see `docs/PHASE10_GLOBAL_STATE_AUDIT.md`. This is treated as its own controlled
project (design + instrumentation first, gradual rollout after), the same discipline this project
already applied to the startup-optimization work and the region-ownership roadmap — NOT a quick
fix, per the user's explicit framing: "Це вже не черговий маленький fix. Це наступна фундаментальна
зміна execution model."

Builds on `docs/PLAYER_REGION_OWNERSHIP_ARCHITECTURE.md` (the original 2026-08-27 design capture
from the full-core-audit follow-up) — that document remains the conceptual background (Region
Ownership + Command Buffer + spatial indexing + lock-free ownership, explicitly NOT Actor-per-
Entity or full ECS). This spec is the concrete, phased execution plan for actually building it.

## Current base (what #31 builds on top of)

```
Startup           DONE  ~93s -> ~52-55s
Runtime core       DONE  region scheduler, ownership, assertions (#29), entity dispatcher (#27),
                         global-state audit (#30)
Safety             DONE  entity mutations, major global races (#30's G1/G3 fixes)
Safety             OPEN  passenger/vehicle (#35, deferred), piston/BlockEntity (#28, closed/
                         deferred)
```

## Five subphases

### 31.1 — Player execution contract (design, no code)

Formally define, for every player action category, whether it moves to the player's owner
`WorldRegion` or stays on main, and why. See `docs/PHASE11_1_PLAYER_EXECUTION_CONTRACT.md` for the
actual contract table (produced alongside this spec).

### 31.2 — Input mailbox

Do NOT move all of Netty at once. Build: `Netty/Main -> PlayerInputCommand -> Player Region mailbox
-> apply on owner`, with a monotonically increasing sequence number per player so packets applied
out of arrival order (100, 101, 102) never get applied as 102, 101, 100 after a region-boundary
delay or handover.

### 31.3 — Player tick

Move player TICKING itself onto the owner region. First real test of the whole model: N players
spread across different regions/threads ticking independently. This is the phase that actually
proves or disproves the rubber-banding concern the original main-thread-only design was built to
avoid.

### 31.4 — Interaction/attack

Only after player tick is stable: move attack, block break/place, item use, entity interaction,
inventory actions onto owner-region execution. `EntityMutationDispatcher` (already shipped, #27)
is the primary mechanism these route through.

### 31.5 — Handover

Player crossing a region boundary must be atomic: `old owner -> freeze input -> transfer state ->
new owner -> resume sequence`. No window where Player ticks concurrently on both A and B.

## Explicitly NOT part of #31 (still deferred / separate)

- **#35** (`Entity.startRiding()` passenger/vehicle cross-region linkage) — deliberately left until
  AFTER the Player execution/handover model exists. Rationale (user's own): "Player ↔ Vehicle має
  бути однією ownership-транзакцією" — the correct fix for #35 likely reuses whatever atomic
  ownership-transfer mechanism 31.5 builds for player handover, rather than inventing a separate
  one now.
- **#28** (piston/BlockEntity atomicity) — stays CLOSED/ROOT CAUSE UNKNOWN, not reopened without
  new evidence.
- **Hot-region load balancing / worker offload** — explicitly LATER than #31. Only makes sense once
  Region/Global execution split (this phase) and Player/Entity/Block ownership (already done) are
  both solid. Per the user's own final-architecture sketch:

```
NestWorldCore
                       |
        +--------------+--------------+
        v                             v
 Region execution              Global execution
        |
 +------+---------+
 v      v         v
Player Entity    Block
 |
 v
Input
                                              (only after all of the above:)
                                        Hot Region -> measure workload -> split/rebalance
                                        -> optional worker offload
```

## Rollout discipline (standing constraint for all 5 subphases)

Same rigor as every prior phase this session: compile -> deploy to an isolated test rig first ->
live-test (real bot where practical, RCON stress otherwise) -> only then real ATM9, always
boot-verified and left stopped/clean afterward with 0 players online unless explicitly permitted
otherwise. No subphase skips ahead of the previous one being validated. `ensureRunningOnSameThread`
and Netty's own threading are NOT touched until 31.2/31.3 explicitly require it, and even then
per the already-flagged risk in `docs/PLAYER_REGION_OWNERSHIP_ARCHITECTURE.md` ("packet-dispatch
threading... a much larger and more unpredictable blast radius than any single-subsystem change
made so far this session").
