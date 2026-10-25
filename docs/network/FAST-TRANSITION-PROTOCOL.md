# NWTP-1 — NestWorld Transition Protocol (draft for FT-PROTOCOL-1)

**Status: DESIGN DRAFT (2026-10-14). No client, proxy or wire change exists yet.**
Builds on [FAST-TRANSITION-1](FAST-TRANSITION-1.md) (architecture, principles P1-P7, state machine)
and [FAST-TRANSITION-MODULE](FAST-TRANSITION-MODULE.md) (what the backend Module already provides).
Terms: **C** client mod, **V** Velocity + `nestworldvelocity`, **S** source backend (player is in PLAY
there), **T** target backend (runs the Fast Transition Module). The player has ONE TCP connection,
C <-> V. V holds a separate backend connection to S, and later to T.

## 1. Stages (each is shippable and fails closed)

| Stage | What changes for the player | Needs |
|---|---|---|
| A. PREPARE-BEFORE-SWITCH | Login mode is still FULL; T has chunks FULL + manifest ready BEFORE the switch starts | V<->T control plane, `ModuleContext.prepareChunks` (done), manifest (partly done) |
| B. WARM | Static payloads C already holds (recipes, tags, ...) are not resent; Forge login state machine otherwise intact | A + client generation inventory + V comparison |
| C. FAST_ATTACH | No repeated FML negotiation | B + FT-AUDIT-1..4 closed. UNKNOWN whether Forge 1.20.1 permits it. Not part of this draft's commitments |

## 1a. Stage 0 — PRE-CONNECT (client start) — revised after measurement (2026-10-14)

Measured on a cold first join (client log + JFR, one run): Connecting -> JEI finished = 17.6 s.
Server side < 1 s. Client: registry injection (RegCache MISS) 3.0 s, recipe rebuild (RecipeCache MISS)
2.4 s, JEI start 5.2 s (single-threaded on the render thread, cannot be pre-run: needs a ClientLevel).
Hence Stage 0 is NOT "download server data in the launcher". It is:

1. After a successful join the client mod persists the INPUTS (per-registry `Snapshot` NBT, recipe
   packet bytes), never live state: `RegCache` HIT means "GameData in THIS process already holds this
   snapshot", and `RecipeCache` holds live `Recipe` objects. A fingerprint on disk would be a false HIT.
2. Whenever the title screen is shown with no injected registry state (first menu after launch and
   again after a full disconnect), the client mod REPLAYS the persisted inputs and sets the in-memory
   fingerprint. Connect then HITs. A changed server -> fingerprint differs -> normal MISS path (safe).
   The current client implementation replays registry snapshots; recipe replay remains separate work.
3. The launcher only seeds those files for a brand-new client (first ever launch) and may pass the
   lobby manifest id via `systemEnv`. It never carries Mojang/session tokens to V.
4. `PRE_READY` (client reached the menu) lets V start `PREPARE` for the lobby/island chunks late
   (leases live <= 1200 ticks).

Current measurement: registry prewarm moved the connect path from 4.4-4.7 s to 1.7 s in one loaded-machine
run, while replay itself took 4.9 s at the menu. This proves work moved off the connection path; it does
not yet prove an end-to-end launch-to-play gain. Recipe-cache work (~2.4 s in the earlier run) and JEI
(5.2 s) remain separate decisions. Verify total launch-to-play and every transition with FT-OBS-1.

## 2. Transports

- **C <-> V:** custom payload channel `nestworld:ft` carried on the existing player connection.
  Messages in PLAY are intercepted by V and never forwarded to a backend. During LOGIN they use
  login query/response (`nestworld:ft` as a login channel), also terminated at V.
- **V <-> T control plane (see 2a):** NOT a new port. Islands already hold a WebSocket to `api-world`
  (`ws/core_{owner_uuid}`, sends heartbeat / shutting_down; the API re-sends undelivered commands and
  the mod is idempotent), and V already receives island readiness (ip, port, `minecraft_ready`) from the
  API over its own WebSocket. NWTP rides that rendezvous: `REGISTER`/`READY` are extra fields on the
  island's status, `PREPARE`/`CANCEL` are idempotent commands the API forwards to the island. Auth is the
  API's existing one. The unauthenticated `CanonicalRegistrySync` listener (:25700, all interfaces) is
  NOT used for control; flagged separately. Lobby (no API record) uses the same messages over a direct
  call from V, because it is always on.
- **T-internal:** the Module (`ModuleContext`) on the server thread; nothing network-visible.

Every message carries `protocol` (int), `transition_id` (128-bit random, minted by V), and is
idempotent per `transition_id`. Unknown `protocol` or unknown required field -> mismatch -> FULL_LOGIN.

## 2a. Deployment topology (LXD containers) and what it implies

Known: V = container `pro-dev` (10.198.126.8, project SkyBlock-dev). Backends: `lobby` (Spawn,
10.198.126.52, always on), `factions`, `minigames`, and dynamic `island-<uuid>` servers that
`nestworldvelocity` registers at runtime (`registerServer` + `createConnectionRequest`) from an external
island API. Island containers are started on demand and may be STOPPED when idle (e.g. the solo
container). The player reaches only V (port-forwarded); V reaches backends on the private bridge.
Dominant pattern: lobby <-> island.

| Fact | Consequence for the protocol |
|---|---|
| Cold start (STOPPED -> container -> Forge ready) takes ~5-6 min and runs in parallel with the client launch (~1 min); resume from FROZEN (LXD freeze = SIGSTOP + RAM) takes seconds | Wake dominates a first/idle island transition. NWTP does not speed it up; it sequences after it. A warm FROZEN island is the case NWTP can actually help. |
| T's address is unknown until it is up; `nestworldvelocity` registers `island-<uuid>` only after the API reports RUNNING + `minecraft_ready` | `REGISTER`/manifest are published with that readiness; V never dials T before the API says it is ready. |
| STOP gives a new JVM; FREEZE/unfreeze keeps the SAME JVM (same `run_id`) after a wall-clock gap of minutes | `run_id` alone does not detect a resume. T detects a tick-gap (wall clock jump) and emits `RESUMED`; V then re-validates the manifest and issues `PREPARE` only after it. Tick-based leases do not age while frozen. |
| Heartbeats stop while FROZEN by design | Silence is NOT death. Liveness comes from the API lifecycle state (RUNNING/FROZEN/STOPPED), never from a missed heartbeat alone. |
| The island mod freezes 5 min after the last logout; the proxy stops after 15 min away; `idle_reaper` is a backstop with a live check against the proxy | A prepared target with no player yet must not be frozen/stopped mid-transition: keep `PREPARE` -> switch under ~30 s (far below the 5 min freeze), and report `transition_pending` to the proxy's live check so the reaper counts it as in use. |
| Chunk leases live <= 1200 ticks (60 s) and expire with a stalled server | V sends `PREPARE` only AFTER `REGISTER`, only when the switch is imminent, and re-sends (new `prep_id`) if the plan stalls > ~30 s. |
| Islands hold player-owned content; one bridge, many tenants | Per-backend HMAC key = HKDF(master secret, `server_id`); V binds `server_id` to the registered source address; an island can never speak for another. Replay window: timestamp + nonce. |
| Bridge latency < 1 ms, but backends may sit on another LXD host | No same-host assumptions: every call has a timeout and a defined fallback (section 5). |
| Launcher already triggers island start on profile select (`IslandModule`) | That is the earliest wake signal; NWTP `PREPARE` follows readiness, not the launcher click. |
| V-side code: `api-world/Nestworldvelocity` (`MyIslandCommand` -> API start -> WebSocket ready -> `registerServer` + ping + `createConnectionRequest`); client mod + Ambassador fork: `Forge-Client-Reset-Packet` | The coordinator is a small extension of `MyIslandCommand`'s ready path. |

## 3. Messages

| # | Message | From -> To | Channel / connection | Content |
|---|---|---|---|---|
| 1 | `CLIENT_HELLO` | C -> V | login query reply, first connect, before any backend login completes | `protocol`, capability bits, `cache[]` = generations C currently holds (registry, recipe, tag, config) |
| 1b | `REGISTER` / `RESUMED` | T -> V (via API) | control plane, when the Module is enabled and after a detected unfreeze | `server_id`, `run_id`, `moduleVersion`, manifest; `RESUMED` = tick gap seen, re-validate before PREPARE |
| 2 | `PREPARE` | V -> T | control plane | `transition_id`, player UUID, destination (dimension, x, y, z) or "default spawn", `ttl_ticks` (<= 1200), radius (<= 4) |
| 3 | `READY` / `PREPARE_FAILED` | T -> V | control plane | `server_id`, `run_id`, `readiness_generation`, manifest (registry/recipe generation, counts, `moduleVersion`), chunk state (`READY` or `LOADING`), reason on failure |
| 4 | `PLAN` | V -> C | `nestworld:ft`, C in PLAY on S | `transition_id`, `mode` (FULL or WARM), target `run_id`, per-generation verdict (`REUSE` or `SEND`), `deadline_ms` |
| 5 | `PLAN_ACK` / `PLAN_NACK` | C -> V | `nestworld:ft`, PLAY on S | `transition_id`, per-generation: C confirms it really holds each `REUSE` generation, else NACK with the first missing one |
| 6 | `SWITCH` | V -> S, then V -> T | backend connections | S: nothing special, player leaves via the existing reset path. T: `ATTACH_EXPECT{transition_id, player UUID, run_id, readiness_generation}` |
| 7 | existing CRP reset | V -> C | existing mechanism | unchanged; C goes PLAY -> LOGIN |
| 8 | login on T | C <-> T via V | normal FML login, plus `nestworld:ft` login query carrying `transition_id` | T checks `ATTACH_EXPECT`; budgeted batching (done); in WARM, payloads marked `REUSE` are omitted from the FML batch only if C ACKed them in message 5 |
| 9 | `ATTACHED` | T -> V | control plane | `transition_id`, whether the chunk lease was READY at `placeNewPlayer`, backend timings |
| 10 | `COMPLETE` | V -> C (optional) | `nestworld:ft` | final timings for the client log |

## 4. State machine by place and moment

1. **Player in PLAY on S, no transition.** C and V know each other (message 1). T may be asleep.
2. **Trigger** (`/myisland`, portal, API). V mints `transition_id`, wakes T if needed (existing API).
   Player is STILL in PLAY on S.
3. **PREPARING_TARGET on T.** V sends `PREPARE`. T's Module calls `prepareChunks` (non-ticking FULL
   tickets, nearest first, lease <= 1200 ticks) and snapshots sync data. Nothing is loaded
   synchronously on the main thread. T replies `READY` when the lease state is READY, or `LOADING`
   with a deadline. T keeps the lease alive until `ATTACHED`, `CANCEL` or TTL.
4. **MANIFEST_COMPARE on V.** V compares `cache[]` (message 1) with T's manifest. In Stage A and in
   any doubt: mode FULL. Stage B: mode WARM, with REUSE only for generations that match exactly.
5. **PLAN on C.** C validates that each REUSE generation is genuinely in its cache. Any doubt: NACK.
   After NACK or timeout, V downgrades to FULL; the player is still on S.
6. **Point of no return: CRP reset.** After this V cannot return the player to S without a login.
7. **Login on T.** FML handshake with the bounded batching. T verifies `(server_id, run_id,
   readiness_generation)` against the `ATTACH_EXPECT` it received. A mismatch (T restarted, lease
   expired) -> plain FULL login, no REUSE, no chunk guarantee. Never a hang.
8. **placeNewPlayer on T.** T consumes the READY lease only if `state() == READY` right now.
   Then reports `ATTACHED`. Lease is released after the player has first chunks (or at TTL).
9. **COMPLETE.** Timings per hop (client, V, S, T), per FT-OBS-1; no cross-host clock subtraction.

## 5. Failure edges (fail closed)

| Where | Failure | Result |
|---|---|---|
| before step 2 | T container STOPPED/booting, no `REGISTER` within the wake deadline | wake is the existing island API flow; V keeps the player on S with a status message; no NWTP state is created until `REGISTER` |
| step 3 | API reports FROZEN/STOPPED after `REGISTER` | V drops T's readiness; next attempt needs the API to report RUNNING + `minecraft_ready` and a fresh `REGISTER` or `RESUMED` |
| step 3 | T asleep too long, `PREPARE_FAILED`, timeout | ABORT: player stays on S, V tells the player, no CRP reset |
| step 4 | missing/stale/unknown manifest field | FULL |
| step 5 | C NACK or no reply within `deadline_ms` | FULL (player still on S) |
| step 6-7 | T unreachable after reset | V sends the player to lobby (FULL), never a stuck PLAY->LOGIN |
| step 7 | `run_id` / readiness changed | FULL login without REUSE |
| any | T restarts between READY and attach | covered by `run_id`; lease died with the JVM |
| any | player disconnects | V sends `CANCEL`; T releases tickets |

## 6. What exists today vs. what is new

Done on T (47.4.351): `prepareChunks` leases, `snapshotSyncData`, manifest JSON (`/nwtransition`),
login budget, `/nwprepare` and `/nwpreparecancel` for manual proof.

New work: (a) control plane server in T's Module, behind a flag, authenticated; (b) V coordinator
(`nestworldvelocity`, separate code base, not in this repository); (c) C message handling in the
client mod (separate code base). The existing `forge-fast-login` server mod keeps working: its
batch mixin overrides the Module's pacing, so measure with and without it.

## 7. Verification plan

Stage A acceptance: with V stubbed by a test harness, send PREPARE to a real T, assert READY,
cancel, TTL expiry, T restart (`run_id` changes), concurrent PREPAREs, and a real client login that
consumes the READY lease. Measured with FT-OBS-1 timings before any speed claim (P7).
