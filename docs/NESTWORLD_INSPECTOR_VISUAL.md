# NestWorld Inspector (visual admin mod)

`compat/nestworld-inspector` — one Forge 1.20.1 jar (`nestworld_inspector`), both sides, optional on both.
Shows an operator, in game, which region/thread every chunk and block entity of my core executes on, and why.
Read-only: no gameplay change, no Core state is written.

## What it shows
- **Chunk columns** (radius around the player, default 4, max 8 chunks): a line box per chunk from feet-8 to feet+16,
  tinted by the grid region that owns it. Border-band chunks are drawn dimmer with a **red X** on the floor plane —
  block-entity work there runs on MAIN even though the chunk belongs to the region.
- **Block entities**: a box per loaded block entity, colored by its execution domain as Core resolves it
  (`BlockEntityExecutionDomain.resolveBlockEntityExecutionDomain`). A block entity in a `BlockEntityGroups` group gets
  a second, larger **yellow** box.
- **HUD** (top-left, hidden with F3/F1): server MSPT, current chunk's region + that region's avg tick ms, whether its
  block entities run on MAIN (border band / no region) or on the region, counts, legend. When the crosshair targets
  a block entity: type id, domain, reason, group size.

Reasons (derived with Core's own precedence, then cross-checked against Core's resolved domain):
`type-pinned`, `mod-pinned`, `position-pinned`, `cascade-safe (grid region)`, `border band`, `region interior`,
`no region`, `dimension not managed`; `unexplained (Core differs)` when the derived reason does not match Core's answer.

## Colors
| color | meaning |
|---|---|
| red | MAIN (server thread) |
| gray | chunk with no region |
| stable per-id hue (never red) | REGION#id (same id ⇒ same color on every client) |
| red X on floor | border band (BE work → MAIN) |
| yellow outer box | block entity in a BE group |
| magenta | Core's domain differs from the derived reason |

## Commands (permission level 2)
- `/nwinspect on [radius]` — start receiving snapshots (player with the mod on the client only).
- `/nwinspect off` — stop and clear the client overlay.
- `/nwinspect radius <1..8>`
- `/nwinspect status` — Core present / groups / position pins supported, watcher count, collection errors.
- `/nwinspect probe <cx> <cz> [radius]` — the same snapshot as text (works from console/RCON).

Client key: **F7** (rebindable, category "NestWorld Inspector") toggles drawing locally.

## Install
- Server: put `nestworld-inspector-<ver>.jar` in `mods/`.
- Admin client: the same jar in the client's `mods/`.
- Players without it can join a server that has it, and a client with it can join any server (DisplayTest
  `IGNORE_ALL_VERSION`, channel `nestworld_inspector:main` accepts absent on both sides).
- Without NestWorld Core: `/nwinspect on` answers "no NestWorld core" and nothing is sent.

## Limits / cost
- Data goes only to operators (level 2) who ran `/nwinspect on` and whose client has the channel; re-checked every
  send (lost op / logout ⇒ removed).
- No watchers ⇒ the tick handler does one `isEmpty()` and returns.
- Refresh every 20 ticks; radius ≤ 8 (≤ 289 chunks); ≤ 4096 block entities and ≤ 1024 distinct types per snapshot
  (otherwise "truncated").
- Core is reached reflectively (no compile dependency): `NestworldRegionSystem`, `NestworldPins`, `WorldGrid`,
  `WorldRegion`, `BlockEntityExecutionDomain`; `BlockEntityGroups.groupOf` and `NestworldPins.isBePositionPinned`
  are optional (absent in older cores such as 47.4.401 ⇒ no group boxes / no position-pin reason).
- Block-entity lists are copied per chunk under the map's monitor; a concurrent mutation by a region thread skips
  that chunk for that round.
- Lines are depth-tested (not visible through walls).
- Measured on the H401BEDOM rig (Core 47.4.401, 3 CPUs, 199 mods), server thread, per snapshot per watcher:
  r=4 with no BEs ≈ 0.3 ms; r=8 with 680 BEs ≈ 2–3 ms (13.4 KB packet). Once per 20 ticks.

## Verified / not verified (2026-11-12)
- Verified on a dedicated server (rig H401BEDOM, console/RCON): mod loads, Core found reflectively, commands
  register, `probe` collects without exceptions (680 BEs: border band 75, mod-pinned 448, type-pinned 4,
  region interior 153, 0 "unexplained"), unmanaged nether ⇒ `managed=false`, packet encode/decode round trip equal,
  no client class loaded on the dedicated server.
- Not verified: the client renderer/HUD/key binding (no display here) and a real `/nwinspect on` with a client.
