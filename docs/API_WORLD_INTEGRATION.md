# NestworldCore + api-world — integration planning notes

(2026-08-22, live ATM9 session — captured verbatim from a design discussion, no
code written yet. Decision recorded below is the owner's, not a recommendation
being pushed through.)

## What api-world is

[`Igor5877/api-world`](https://github.com/Igor5877/api-world) is a per-player
SkyBlock island system, unrelated to ATM9:

- **FastAPI backend** (`api/`) — creates/starts/stops/freezes an **LXD
  container per player island**, cloned from a base image, tracked in a
  MySQL/MariaDB table.
- **Velocity proxy plugin** (`Nestworldvelocity/`) — routes a connecting
  player to their island, creating/starting it on demand, polling the API
  until the island reports ready.
- **Forge server mod** (`mods-server/`) — runs inside each island container,
  reports readiness back to the API, auto-freezes the container after the
  last player leaves.

Architecturally this is close to the *opposite* of NestworldCore's premise:
one JVM/one shared world sharded across region-threads (NestworldCore) vs.
many isolated single-purpose JVMs, one per player (api-world, today).

## Three placement models discussed

### A. NestworldCore as the base-image server jar (1 island = 1 container = 1 JVM)
Drop-in: swap vanilla Forge for this fork in the base LXD image, no changes
to api-world's Python/Velocity/mod code. Region-thread parallelism still has
something to shard (an island is always &gt;1 chunk), so it isn't wasted, but
most of what this fork fixes (LevelTicks races, POI races, ChunkHolder races,
...) are bugs the region-sharded architecture itself introduces — vanilla
single-threaded Forge never had them at 1-concurrent-player scale to begin
with. Net effect: import the architecture's self-inflicted risk surface
(including the still-open **shutdown-hang** bug — JVM does not always exit
cleanly after `/stop`, a known recurring issue) without the throughput
payoff region-sharding exists for.

### B. Fully consolidated (all islands as regions/dimensions in one JVM)
The architecturally "correct" application of this fork's strength — and
per-dimension region-sharding (Nether/End Stage 0a) is already the right
technical foundation for "one dimension per island" inside a single JVM.
Removes LXD orchestration entirely. But it also removes LXD's crash/exploit
**isolation** — today one broken mod or exploited bug on one island kills
only that one container; in a fully shared JVM it can take the whole host
down for every player. NestworldCore is explicitly in a pre-public
bug-hunt phase with multiple open crash-classes (see project memory) — not
mature enough yet to be trusted with that blast radius.

### Hybrid (N islands per NestworldCore node, several nodes)
Splits the difference: fixed pools of e.g. 5-10 islands per JVM instance,
several instances running. Bounds isolation blast-radius to one pool instead
of "everyone," while still getting real region-thread parallelism (unlike A)
and real RAM/container savings (unlike A, which is still 1 JVM per player).

**Placement/routing rule for the hybrid model** (the concrete design
question this session answered): routing must be **sticky, not
capacity-based**, or a returning player can be routed to the wrong node.
- New island (first login): backend picks a node with free capacity
  (round-robin / least-loaded among nodes with open slots), creates the
  dimension there, records `node_id` in the existing island-metadata table
  (same table that stores LXD container IP today — `node_id` just replaces
  that column's meaning).
- Existing island (return login): backend **only** looks up the already-
  recorded `node_id` and routes there via Velocity's `registerServer`/
  `connect`, regardless of whether that node currently reports itself full
  for *new* placements. Capacity only ever gates new-island creation, never
  routing of an existing island.

**Known gap, not yet built**: NestworldCore currently only manages a fixed,
modpack-known set of dimensions (Overworld/Nether/End/Moon/...). It has no
mechanism today to create a brand-new, arbitrary per-player dimension
(`island_<uuid>`) at runtime on demand. This is required groundwork for the
hybrid (and fully-consolidated) models regardless of which one is chosen
later — it does not exist yet and was not scoped or estimated this session.

## Decision (2026-08-22)

Going with **Variant A** for now (simplest, lowest engineering cost) —
islands are always more than one chunk, so region-thread concurrency still
has real work to shard even at 1 player. Hybrid is the documented future
direction once (a) dynamic per-island dimension creation exists and (b) the
shutdown-hang risk below is resolved or mitigated.

**Action item before broad rollout of Variant A**: verify/mitigate the
shutdown-hang bug specifically for this use case. api-world's auto-freeze/
stop lifecycle is fully automated (no human standing by to force-kill a
hung JVM, unlike an ATM9 admin session) — a hung container-stop leaks host
resources silently over time. Minimum bar: api-world's own orchestration
should add a stop-timeout → force-kill fallback at the LXD/API layer so this
isn't a hard dependency on the core-side fix landing first.

## Metrics collection at fleet scale

The existing companion mod (`metrics-exporter/`, see
`docs/CHUNK_GEN_PIPELINE_OBSERVABILITY_SPEC.md` for what it exports) already
works per-JVM with no changes: drop it in `mods/` on the base image and every
island container gets its own `/metrics` on port 9219 (binds all interfaces
already, reachable from the LXD bridge network without config changes).

What's actually new at fleet scale is **Prometheus target discovery**, since
containers come and go dynamically (island created/frozen/destroyed as
players join/leave) — a static scrape config doesn't work here the way it
did for the single ATM9 instance.

- LXD has no native Prometheus service-discovery integration.
- Recommended: **`http_sd_config`** against a new FastAPI endpoint (e.g.
  `/api/v1/prometheus/sd`) that returns the current set of *running* (not
  frozen) islands as Prometheus target JSON:
  ```json
  [{"targets": ["10.x.x.x:9219"], "labels": {"island_uuid": "...", "player": "..."}}]
  ```
  The backend already tracks exactly this data (container IP + state) for
  its own lifecycle management — this is a read-only projection of data that
  already exists, not a new source of truth.
- Frozen islands simply fail scrape (`up=0`) — expected, not an error
  condition, no special-casing needed.
- **Cardinality**: per-mod/per-chunk tick-attribution metrics
  (`nestworld_tick_ms_total{kind,mod}`, `nestworld_tick_hotspot_chunk_ms`)
  are already bounded per-instance (top-20 chunks, ~mod-count entries), but
  multiplied across potentially hundreds of islands the fleet-wide series
  count adds up. Default Grafana views for the fleet should be **aggregate**
  (`sum by (mod) (...)` across all islands), with per-island drill-down
  (filtered by `island_uuid`) as a secondary, not the default, view.
