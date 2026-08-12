# NestWorld Inspector — technical specification (draft, 2026-08-11)

Status: **§4 (hotspot detection) and §5 (teleport-to-problem) have a first implementation, 2026-08-11.**
See "Implementation log" at the bottom for what actually shipped vs. what's still spec-only. All
other sections remain spec-only — do not treat them as built.

## Goal

A built-in diagnostics/administration system for NestWorldCore at the level of Spark, but aware of
this project's region-sharded/free-running architecture specifically. The admin should not just see
"the server is lagging" — they should be able to identify the specific region, thread, mod/system,
coordinates, and root cause, and jump directly to it in-game.

## Scope (15 sections, as specified)

1. **Profiler** — `/nestworld profiler start|stop|report|status`. Collects TPS, MSPT (p50/p95/p99/max),
   per-region MSPT, server thread, `NestWorld-Region-*` threads, chunk generation, entity ticking,
   block-entity ticking, block/fluid ticks, mailbox, cross-region work, worldgen, GC, Netty, JVM
   thread CPU time, lock contention, waiting/blocked threads. Must have minimal production overhead.
2. **JVM Thread Inspector** — `/nestworld threads [dump [<region>]] [find <state>]`. Per-thread: name,
   CPU time, state, blocked/waiting, stack trace, owning region (if NestWorld), current work, average
   execution time.
3. **Region Inspector** — `/nestworld regions`, `/nestworld region <id>`. Per region: ID, bounds,
   chunk count, entities, block entities, MSPT, localTick, global gameTime, tick delta,
   free-running/barrier mode, mailbox, pending chunk promotions, chunk generation, split/merge state,
   CPU, hottest operations, ownership violations, cross-region traffic.
4. **Hotspot detection** — `/nestworld hotspots`. Automatically ranks the most loaded spots, by
   region MSPT AND by entities/block-entities/block-fluid-ticks/explosions/pathfinding/mailbox/
   chunk-gen/cross-region ops — not MSPT alone.
5. **Teleport to problem** — `/nestworld goto hotspot|region|entity|chunk <...>`. One command from a
   detected hotspot/entity/chunk to standing there in-game. Must be safe — no dangerous operations on
   the target object itself.
6. **Mod attribution** — real stack-sampling attribution of tick time to the owning mod (not a naive
   class-name guess), plus hot-method breakdown per mod.
7. **Ownership/concurrency diagnostics** — `/nestworld health`: entity ownership violations,
   LevelTicks violations, wrong-thread access, mailbox duplicates/misrouting/lost messages, chunk
   ownership violations, concurrent mutation, region sync failures, scheduler anomalies, catch-up
   bursts.
8. **Automatic incident recorder** — on a critical event (LevelTicks violation, entity ownership
   violation, wrong-thread access, NPE/CME/ISE, Watchdog warning, region crash, MSPT-over-threshold,
   mailbox overflow, chunk-gen spike, region-lag-over-threshold): keep a rolling 30-60s telemetry
   buffer and snapshot it into a numbered, UUID'd incident record (region, thread, gameTime,
   localTick, delta, MSPT percentiles, mailbox counts, entity breakdown, hot operations, timeline,
   stack traces) under `logs/nestworld/incidents/`.
9. **Crash diagnostics** — extend (never replace) vanilla/Forge/NeoForge crash reports with NestWorld
   context: region, bounds, thread, free-running/barrier status, localTick, gameTime, delta, MSPT,
   mailbox state, entity/chunk ownership, recent incidents, hot methods, recent cross-region
   messages, split/merge history, chunk-gen state.
10. **Region timeline** — `/nestworld region <id> history`: per-second MSPT history with hotspot
    markers and load-change attribution.
11. **Spark integration** — complements Spark's own output (e.g. Spark shows "Server thread 184ms";
    Inspector adds the NestWorld-specific breakdown of that time: per-region cost, hot operations,
    hotspot coordinates, main contributor, and action buttons — teleport/inspect/thread-dump/incident).
12. **Web/GUI API** — architecture should allow a future web UI (HTTP/WebSocket endpoint or
    `/nestworld inspector`) showing a region map, MSPT heatmap, entities, mailbox, localTick delta,
    hotspots, JVM threads, incidents, graphs, timeline, and a teleport button.
13. **Mod compatibility constraint** — must not require modifying third-party mods, must not add
    global accessor hooks (`getX()`/`getY()`/`getZ()`-style) purely for profiling, must not alter
    third-party bytecode unnecessarily. Attribution must use safe sampled/stack-sampling profiling.
    Must scale to very large modpacks (hundreds of mods).
14. **Version architecture** — designed as a separate compatibility layer (`NestWorld Inspector API`
    with per-MC-version backends: 1.16.5 / 1.20.1 / 1.21.1+/NeoForge), not hardwired to specific
    vanilla classes.
15. **Guiding principle** — answer three questions in sequence: **WHAT** (Region #3 = 184ms/tick),
    **WHY** (98k TNT → `Explosion.explode()` → 91ms), **WHERE** (-760 70 -3182) — then
    `/nestworld goto hotspot 1` teleports the admin directly there.

## Definition of done (as specified)

Under load like 100k+ TNT, hundreds of mods, worldgen, redstone, fluids, MineColonies, Mekanism,
cross-region activity, free-running regions — an admin can go from "TPS dropped" to "Region #3 →
184ms → Explosion.explode → TNT → coordinates X/Y/Z → specific hotspot → teleport → incident trace"
in a handful of commands. This is meant to be NestWorldCore's own diagnostics system, not a Spark
copy.

## Relationship to existing NestWorldCore tooling (context for whoever scopes implementation)

This session (and prior sessions) already built a number of pieces that overlap with sections above
and should be reused/extended rather than rebuilt:
- `/nestworld status` — already shows per-region cost/p95/entities/heat/mailbox/localTick-delta
  (partial overlap with §3 Region Inspector, §4 Hotspot detection's `heat=` field).
- `/nestworld entityguard` / `EntityOwnershipGuard` — already the core of §7's entity-ownership
  check (diagnostic-only today, not yet aggregated into a `/nestworld health` summary view).
- `/nestworld entityrecheck` / `EntityOwnershipRecheck` — today's skip counter, same family.
- `/nestworld mailboxaudit` / `MailboxAudit` — sent/applied/pending/duplicates/misrouted, already
  most of §7's mailbox diagnostics.
- `NestworldTickOwnership` — LevelTicks ownership violations, now with region/isFreeRunning/localTick
  context (see [[region-split-scheduletick-race]]) — overlaps §7's LevelTicks-violations line.
- `BlockTickHeat` — already the `heat=[...]` hotspot summary shown in `/nestworld status`, the
  existing seed for §4's hotspot ranking (currently block-tick-only, not yet unified across
  entities/block-entities/explosions/pathfinding/mailbox/chunk-gen as §4 specifies).
- SparkBridge (`NestWorld/SparkBridge`) — already auto-starts a Spark profiler; §11 asks for
  NestWorld's own layer to sit alongside this, not replace it.
- `/nestworld mspt` — main-thread MSPT percentiles already exist; §1's profiler needs the same
  percentile machinery extended per-region and to the other listed categories.
- None of the incident-recorder (§8), crash-report extension (§9), teleport commands (§5), mod
  attribution via stack sampling (§6), region timeline (§10), or web/GUI layer (§12) exist yet in
  any form.

## Suggested phasing (not agreed with the project owner — a starting proposal only)

Given the scope (15 sections, explicitly framed as "not just a Spark copy," with a web UI and
multi-version compatibility layer), this reads as a multi-week, multi-phase project, not a single
implementation pass. A natural phase order, leaning on what already exists:

1. `/nestworld health` (§7) — mostly aggregation of existing diagnostics already built this session
   (EntityOwnershipGuard, EntityOwnershipRecheck, MailboxAudit, NestworldTickOwnership) into one view.
2. Unified hotspot ranking (§4) extending `BlockTickHeat` to the other categories, plus
   `/nestworld goto hotspot|region|chunk` (§5, minus entity-uuid teleport initially).
3. Region Inspector detail view (§3) and Region timeline (§10) — extends `/nestworld status`'s
   existing per-region fields with history and more detail.
4. Incident recorder (§8) — the rolling telemetry buffer + snapshot-on-trigger mechanism, reusing
   the hotspot/health signals from steps 1-3 as its trigger inputs.
5. JVM Thread Inspector (§2) and mod attribution via stack sampling (§6) — the profiler-heavy pieces,
   likely the most implementation-risky given the "minimal production overhead" + "no third-party
   bytecode changes" constraints (§1, §13).
6. Crash-report extension (§9) — depends on incident recorder (§8) already existing to pull
   "recent incidents" into the crash context.
7. Web/GUI API (§12) and version-architecture abstraction (§14) — last, once the underlying data
   model from steps 1-6 has stabilized enough to design a stable API surface around it.

This phasing is a proposal for discussion, not a commitment — the project owner should confirm
priority order and which phase(s), if any, to start on before implementation begins.

## Implementation log

### §4 + §5, first slice (2026-08-11)

Shipped: `HotspotDetector.java` (new class) + `/nestworld hotspots`, `/nestworld goto
hotspot|region|chunk|entity <...>` in `NestworldCommand.java`. `BlockTickHeat` got one new
package-private accessor (`snapshotCounts()`) to feed the ranking; no other existing class touched.

**What it actually does**: combines the already-continuously-tracked `BlockTickHeat` (block/fluid-tick
+ block-entity heat, per chunk, world-wide) with entity density computed FRESH on each call (bucketing
every region's already-published `EntitySnapshot` map by chunk — no new continuous tracking, so true
zero idle cost, matching §1/§13's overhead constraint). Ranks the top 10 chunks by combined score,
resolves each to a real in-game position (a live entity's snapshot position if entities dominate, a
heightmap-safe chunk center otherwise), and for entity-dominated hotspots shows a real entity-type
breakdown (e.g. `minecraft:falling_block x49`) via a live `Entity.getType()` read — safe because entity
type is an immutable field, unlike position/velocity/health, so this doesn't reintroduce the
cross-thread mutation hazard the Entity Safety Layer work earlier this session exists to prevent.
Results are cached so `/nestworld goto hotspot <id>` can reference the last computed list.
`/nestworld goto region <id>` picks a region's own hottest chunk (or a live entity, or a
heightmap-safe geometric fallback, clamped away from the literal center of world-sized catch-all
regions — see `HotspotDetector.clampChunkCoord`) rather than requiring a prior `hotspots` call.

**What it deliberately does NOT do (later phases, not this slice)**: no real stack-sampled mod
attribution (§6 — the entity-type breakdown is an honest, cheap proxy, not the same thing); no
per-hotspot MSPT (that needs the profiler/thread-inspector work of §1/§2); no explosion/pathfinding/
mailbox/chunk-gen heat channels yet (§4 lists these — only block/fluid-tick + entity density are
wired up so far); `region avg cost:` in the hotspot listing is the region's OWN overall average tick
cost (already-existing `WorldRegion.getAvgTickMs()`), not a per-hotspot attribution — don't read it as
"this hotspot causes N ms," it's context, not a measurement of the hotspot itself.

**Validated**: real, non-fabricated output on gen-spike-repro (13-region layout, natural mob/
falling-block activity, no synthetic load needed) — correct heat math (caught and fixed one bug:
an errant `/100.0` left over from the ranking-only integer scaling was corrupting the displayed
totals before this was tested), correct entity-type attribution, correct coordinates/chunk info,
clean graceful failures for every edge case tried via RCON (`goto hotspot` with a stale/nonexistent
id, `goto region` with a nonexistent id, `goto entity` with a UUID that resolves to no live entity,
and — RCON not being a player — every `goto` correctly reports "A player is required to run this
command here" instead of crashing). **Not validated**: an actual in-game teleport with a connected
player client (no client available in this environment) — the command reaches
`ServerPlayer.teleportTo(...)` correctly gated behind `CommandSourceStack.getPlayerOrException()`,
but the destination landing itself (does it actually place the player somewhere sensible/safe) has
only been reasoned about via the heightmap-safe chunk-center fallback, not observed live.
