# #31.1 — Player Execution Contract (design, no code)

For every player action category: does it move to the player's owner `WorldRegion`, stay on main,
or split (part region, part main)? Each row states the target subphase (31.2-31.5) and the
concrete mechanism, reusing what already exists wherever possible.

## Contract table

| Category | Today | Target | Subphase | Mechanism |
|---|---|---|---|---|
| **Player tick** (base `Entity`/`Player` tick — physics, hunger, effects, air/fire, XP, cooldowns) | Main thread, unconditionally (`NestworldDimensionRegion`'s own documented "must stay global" list) | **Owner region** | 31.3 | Player becomes a region-owned tickable, same tick-dispatch machinery entities already use. Requires 31.2's input mailbox to exist first (a player mid-tick on a region thread needs its latest input already applied, not racing a main-thread packet handler). |
| **Player movement** (position/rotation from movement packets) | Main thread: packet arrives on Netty, dispatched to main via `ensureRunningOnSameThread` (unmodified), applied directly to the player entity | **Owner region execution, main-thread packet RECEIPT unchanged** | 31.2 (mailbox) + 31.3 (apply) | This is the core of the original rubber-banding concern. Packet receipt/decode stays exactly where it is (Netty -> main dispatch, untouched — see "explicitly not touched" below). What moves is WHERE the movement is APPLIED: main hands the decoded movement off as a sequenced `PlayerInputCommand` to the player's owner region's mailbox instead of mutating position directly. |
| **Player interaction** (right-click block/entity, `Player.interactOn()`) | Main thread, **zero cross-region ownership guard today** — this is the CONFIRMED, still-open gap from `docs/PHASE9_1_ENTITY_MUTATION_AUDIT.md` (flagged as needing its own design pass, not a drop-in `EntityMutationOp`, because interaction needs a synchronous result — item consumption, container open, etc.) | **Owner region** | 31.4 | Needs a new mutation shape beyond the closed `EntityMutationOp` set (interaction can consume/return items, open a menu, trigger a synchronous client-visible result) — this is exactly the gap 31.4 is scoped to close, reusing `EntityMutationDispatcher`'s apply-time-revalidation machinery but with a richer op type. |
| **Player attack** (`Player.attack()`) | **Partially region-executed already**: the melee/sweep TARGET MUTATION (`hurt`/`Knockback`) already redirects to the target's owner region via `EntityMutationHelper.redirectFromMainThread` -> `EntityMutationDispatcher.applyQueued` (shipped, live-validated, #27). But the ATTACK LOGIC ITSELF (cooldown check, critical-hit roll, enchantment lookup, sweep-target scan) still runs on main, since `Player.attack()` is invoked from main-thread packet handling. | **Whole call moves to owner region**, mutation boundary unchanged | 31.4 | Once player TICK is on the owner region (31.3) and player INPUT arrives via the 31.2 mailbox, `Player.attack()` naturally executes ON the owner region thread already — no new mutation-boundary work needed here, `EntityMutationDispatcher` already handles the "target is foreign" case correctly (and will now ALSO correctly handle "target is local," the common case, via its existing direct-apply branch). This row is mostly a consequence of 31.2/31.3, not new work. |
| **Inventory/action** (open/close menu, item use, crafting, drop, swap hands) | Main thread. Container/menu state (`AbstractContainerMenu`) has its own client-visible sync protocol (slot updates sent to the client) layered on top of whatever thread applies the change. | **Owner region for the mutation; menu-sync protocol unchanged** | 31.4 | Same shape as interaction — needs new op types (inventory doesn't fit `EntityMutationOp`'s closed set either), but the CLIENT-FACING sync packets (slot contents) are a separate concern from where the mutation itself runs and don't need to move — only who computes the new state before those packets are sent. |
| **Player handover** (crossing a region boundary) | N/A today (players are main-thread-global, so "which region owns this player" doesn't exist as a live-execution concept yet — only as data-ownership, via the existing `BoundaryEntityTransfer` mechanism that already moves player DATA, not input processing) | **New mechanism** | 31.5 | `BoundaryEntityTransfer` moves entity DATA on border crossing already. 31.5 needs a NEW, additional mechanism specifically for INPUT PROCESSING OWNERSHIP — freeze input on A, transfer, resume on B — since "which region's thread currently owns this player's next input" is a different question than "which region currently holds this player's data," and per `docs/PLAYER_REGION_OWNERSHIP_ARCHITECTURE.md`'s own prior analysis, no input may be lost or reordered across this handover (needs the 31.2 sequence numbers). |

## Explicitly stays on main thread (not part of #31 at all)

- **Netty channel / packet decode itself** — network I/O, unchanged. Only WHERE a decoded packet's
  effect gets APPLIED changes (per the movement row above); packet receipt and Forge/vanilla's own
  `ensureRunningOnSameThread` dispatch mechanism are not touched by any of 31.1-31.5.
- **Login/logout, `PlayerList` join/leave** — infrequent, global by nature (affects the
  now-`CopyOnWriteArrayList` `ServerLevel.players`, per #30's G1 fix), no benefit to region-owning
  this.
- **Respawn/death handling** — can involve dimension changes and global-state triggers
  (advancements, statistics); infrequent enough that region-owning it adds complexity without a
  measurable win. Stays main-thread-coordinated even after 31.3-31.5 land.
- **Global commands** (`/gamemode`, `/tp`, admin/OP commands, most of `/nestworld` itself) —
  server-wide by nature, main-thread by Brigadier's own design, unaffected.
- **Global chat/broadcast** — server-wide fan-out, no single region owns "everyone."
- **Scoreboard/team updates** (`docs/PHASE10_GLOBAL_STATE_AUDIT.md` finding G7, PLAUSIBLE,
  untraced) — out of #31's scope; if it turns out to need region-thread access as part of some
  31.4 mutation (e.g., a stat-triggered objective on a kill), that's a dependency to resolve WITHIN
  the relevant #31 subphase, not a reason to move ownership of the scoreboard itself.
- **Advancement triggers, statistics** — same reasoning as scoreboard; these are typically
  triggered as a side effect of a mutation (e.g., a kill) and can be posted back to main as a
  fire-and-forget notification without needing the scoreboard/advancement system itself to become
  region-aware.

## Sequencing dependency graph

```
31.1 (this doc)
   |
   v
31.2 Input mailbox (sequence numbers, PlayerInputCommand)
   |
   v
31.3 Player tick moves to owner region  <-- first real multi-region-player test
   |
   v
31.4 Interaction/attack/inventory move to owner region (EntityMutationDispatcher-based)
   |
   v
31.5 Handover (freeze/transfer/resume, atomic, no dual-region execution window)
```

Each subphase is validated (compile -> isolated rig -> live bot/RCON test -> real ATM9) before the
next begins, per the rollout discipline in `docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md`. In
particular, 31.3 is the single highest-risk step — it's the first point where the ORIGINAL
rubber-banding concern that justified main-thread-only player ticking gets directly tested. If
31.3's live rubber-banding measurements (position correction rate, input latency, dropped/
duplicate input) come back bad, that's a signal to stop and reconsider before 31.4/31.5 build on
top of a shaky foundation — not a signal to push through anyway.
