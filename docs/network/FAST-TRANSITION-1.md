# FAST-TRANSITION-1 — NestWorld seamless backend migration (architecture)

**Status: ARCHITECTURE DESIGN (2026-10-14).**
- Documentation only. No code, Module, Mixin, packet, Velocity or deployment change is part of
  this phase.
- Implementation is forbidden until the gates in §20 are closed and reviewed.

**Scope:** fast player transition between NestWorld Forge backends (Spawn, islands) behind Velocity.

**Confidence words:** CONFIRMED / STRONGLY INDICATED / CORRELATED / HYPOTHESIS / UNKNOWN.
- Everything in this document about the *current* implementation is taken from the task statement
  and is **not yet audited**. FT-AUDIT-1 owns that.
- Everything about the *future* system is design.

## 0. Normative principles
> **P1.** Fast transition eligibility is determined by proven compatibility of client-visible and
> attach-visible state, not by equality of server JAR lists.

> **P2.** A different server-only mod does not by itself invalidate Fast Attach. Only a change to a
> relevant transition contract generation does.

> **P3.** Unknown compatibility always falls back to a safer transition mode; it is never treated as
> compatible.

Corollaries:

| # | Rule |
|---|---|
| P4 | Fail closed. Every error, timeout or ambiguity resolves to WARM_LOGIN or FULL_LOGIN, never to a hang and never to FAST_ATTACH. |
| P5 | No state crosses the static/dynamic boundary (§14) without a proof that it is generation-scoped and immutable within that generation. |
| P6 | No lifecycle hook is skipped without a classification from FT-AUDIT-2 (§9). |
| P7 | No performance claim without the FT-OBS-1 measurements (§18). |

## 1. Context and current transition (to be audited by FT-AUDIT-1)

Components:
- Velocity proxy with the NestWorld plugin `nestworldvelocity`;
- one Forge server JVM per island, plus Spawn/lobby;
- NestWorldCore and the NestWorld Module SPI;
- the NestWorld client mod;
- the existing CRP / seamless transition (repository `Forge-Client-Reset-Packet`);
- the current NestWorld server-mod.

Current path, as stated:
```
PLAY old backend → CRP reset → PLAY→LOGIN → full Forge/FML handshake
  → registry/config negotiation → PlayerList.placeNewPlayer → chunks → PLAY new backend
```

| Observed (stated, not yet measured under FT-OBS-1) | Time |
|---|---|
| first connect, server list → lobby | ~16 s |
| first transition to a previously unseen backend | ~5–8 s |
| transition between already-visited backends | ~1–2 s |

Existing optimizations, as stated:
- CRP soft reset;
- batched Forge handshake;
- same-modset detection;
- registry cache machinery;
- recipe cache / defer / skip;
- partial client mod state retention;
- renderer resource reuse;
- chunk prefetch;
- faster chunk delivery.

**Direction:** stop accelerating the standard Forge login indefinitely. Instead, avoid repeating
static Forge negotiation where compatibility is already proven.

## 2. Goal model
```
           one logical NestWorld client session
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
        Spawn        Island A      Island B
```
For the player, a transition between compatible backends should behave like a dimension change,
not like a new server login.

Target path:
```
PLAY source backend
   ├─ target prepared ahead of time (§10)
   ├─ compatibility manifests compared (§5)
   └─ destination chunks prefetched
        ↓
soft client detach → FAST ATTACH → dynamic target state → first playable chunks → PLAY target
```

## 3. Components and responsibilities

### 3.1 NestWorld client mod
- Retains static client state that is proven compatible, keyed by generation (§6).
- Soft-detaches the old `ClientLevel` without tearing down generation-scoped state.
- Applies the new dynamic world and player state.
- Keeps a local generation cache, and holds and executes the `TransitionPlan` it receives.
- Can always fall back to the normal Forge login.
- Records client-side timing points (§18).

### 3.2 nestworldvelocity: authoritative coordinator
- Knows the client capability, and the source and target manifests (§5).
- Compares generations and selects FULL_LOGIN, WARM_LOGIN or FAST_ATTACH (§7). It is the only
  component that decides.
- Coordinates the backend connection switch, and refuses FAST_ATTACH without proven compatibility.
- The proxy cannot be removed from this design: client mod plus Core Module is insufficient for
  the existing architecture (§15).

### 3.3 NestWorldCore Fast Transition Module
- A NestWorld **Module**, system-level. It is not a Mod API primitive and not a mod-specific
  compatibility addon.
- Allowed surface:
  - Minecraft server lifecycle;
  - Forge login and session lifecycle;
  - `PlayerList`;
  - `ServerPlayer` attach;
  - chunk prefetch;
  - NestWorldCore scheduler;
  - backend readiness;
  - transition diagnostics.
- Whether the Module SPI can reach these hooks at the required load phase is UNKNOWN (§16,
  FT-AUDIT-4).

## 4. Compatibility is semantic, not `hash(all mods)`
Rejected: `compatibility = hash(all installed mods)`.
- Server JAR sets differ for reasons invisible to the client, such as a test island with
  `profiler.jar`, `debug-tools.jar` or `admin-tools.jar`.
- Compatibility is decided per **transition contract generation** (§5–§6).
- A mod influences eligibility only through the generations whose canonical state it changes.

## 5. TransitionManifest (versioned; names provisional until FT-AUDIT-3)
```
TransitionManifest {
  manifest_version            // schema version of this structure
  server_id, run_id           // identity and incarnation (§12)
  manifest_generation         // content hash of all generation fields below
  readiness_generation        // §12
  protocol_generation
  client_capability_generation
  registry_generation
  network_channel_generation
  config_generation
  recipe_generation
  tag_generation
  datapack_generation
  player_attach_generation
  extensions: { name → generation }   // optional, each with a declared mismatch action
}
```

| Field | Covers | Computed by | Changes when | Content-addressed | Cacheable | Mismatch action |
|---|---|---|---|---|---|---|
| `protocol_generation` | MC protocol version, Forge/FML network version, NestWorld transition protocol version | backend Module at startup; client mod for its side | software upgrade | yes (canonical version tuple) | yes | FULL_LOGIN |
| `client_capability_generation` | the client mod's declared transition capabilities (detach/attach ops it supports) | client mod; Velocity stores it per session | client mod upgrade | yes | per session | FAST denied → WARM or FULL |
| `registry_generation` | synced registries: ids, keys, network ids. Blocks, items, entity types, menus, etc., i.e. what FML registry sync transfers | backend Module after registry freeze | mod-set change affecting synced registries | yes (canonical ordered dump) | yes | FAST denied. WARM only if FT-AUDIT-3 proves partial registry resync safe; otherwise FULL |
| `network_channel_generation` | client-required channels and their versions (the FML channel negotiation result) | backend Module | mod or channel version change | yes | yes | FAST denied → FULL (channel negotiation is login-bound until FT-AUDIT-3 shows otherwise) |
| `config_generation` | server→client synced configs that are client-visible | backend Module | config edit or reload | yes (canonical serialized synced subset) | yes | resync (WARM) |
| `recipe_generation` | baked recipe set sent to the client | backend Module, after datapack load and on `/reload` | datapack or recipe change | yes | yes | resync recipes (WARM) |
| `tag_generation` | synced tags | backend Module | datapack or tag change | yes | yes | resync tags (WARM) |
| `datapack_generation` | other client-synced data-driven state not covered above (UNKNOWN set; FT-AUDIT-3) | backend Module | datapack change | yes | yes | resync or WARM |
| `player_attach_generation` | the attach-time contract: which per-attach hooks exist and how they are satisfied (§9) | backend Module, from the FT-AUDIT-2 classification | a mod adding or changing a login or attach hook | yes (canonical list of hook identities plus versions) | yes | FAST denied → WARM or FULL |
| `extensions.*` | future generations declared by Modules/mods. Each declares its mismatch action, and an unknown extension is treated as a mismatch (P3) | declaring component | per extension | required | per extension | declared action; unknown → FAST denied |

Rules:
- A field missing on either side counts as a mismatch, and the safer action applies.
- The manifest is immutable per `(run_id, manifest_generation)`. Any change issues a new
  `manifest_generation`.

## 6. Content generations
```
generation = H(canonical(relevant_state))
```
- `H`: a fixed cryptographic hash, with its algorithm id embedded in `manifest_version`.
- `canonical`: a deterministic serialization with sorted keys and stable ordering, independent of
  JVM, run, load order nondeterminism and wall time.
- Forbidden inputs: `System.nanoTime()`, random startup epochs, backend-specific arbitrary ids,
  JAR file names or paths, unordered map iteration.
- Example: Island A and Island B bake identical recipes, so both have `recipe_generation = P17`.
  A client that holds P17 receives no recipes on transition, even though it changes JVM.
- `/reload` that changes recipes moves P17 to P18, which is a cache miss automatically.
- A generation must be computed from the state **as sent to the client** (post-filtering), not from
  the server's internal superset.

## 7. Transition modes

| Mode | When | What happens |
|---|---|---|
| FULL_LOGIN | first join; no compatibility information; protocol or client-required mod mismatch; registry incompatibility; unknown Fast Attach contract; any validation failure | The existing Forge/FML-safe path, unchanged. |
| WARM_LOGIN | protocol, channels, registries and attach contract match; some data generations differ | The standard login state machine is kept, but components proven identical are neither resent nor rebuilt. Example: registry MATCH, recipes MATCH, tags MISMATCH → reuse registry, reuse recipes, resync tags, continue the Forge login. |
| FAST_ATTACH | the **entire** required Fast Attach contract is proven compatible | No repeated static FML negotiation; only the dynamic player and world session is attached. |

Decision order: protocol → capability → channels → registry → attach contract → data generations.
The first hard mismatch decides the ceiling of the path.

## 8. Server-only mods

| Case | Mod effect | Generations affected | Path |
|---|---|---|---|
| A, purely server-side | commands, profiler, logging, admin tools, diagnostics; nothing client-visible | none | FAST_ATTACH **may remain allowed** (P2), provided it adds no attach-time hook. Otherwise `player_attach_generation` changes. |
| B, server-only data | recipes, tags, datapacks, loot, other synced data | `recipe_generation` / `tag_generation` / `datapack_generation` | partial resync via WARM_LOGIN. **Not** an unconditional FULL_LOGIN when the rest of the contract matches. |
| C, registry or client-required protocol | new blocks, items, entity types, menus, client-required channels, state with no client classes or resources | `registry_generation` / `network_channel_generation` | FAST_ATTACH forbidden → WARM or FULL |

"Server-only" is a claim to verify, not a label to trust. Classification follows from which
generations change, measured by the manifest, not from the mod's declared side.

## 9. Player lifecycle (FT-AUDIT-2): critical, unaudited
The Forge login cannot be assumed skippable. Each item below must be classified before any
FAST_ATTACH design is final:
- `PlayerList.placeNewPlayer`;
- `PlayerLoggedInEvent`;
- `AttachCapabilitiesEvent<Player>`;
- Forge network channel initialization;
- permissions;
- capabilities;
- mod login hooks;
- persistent player state load and save;
- scoreboard and team initialization;
- custom mod session state.

Classes:

| Class | Meaning |
|---|---|
| STATIC_ONCE_PER_CLIENT_SESSION | Valid for the whole client session across backends; may be skipped on FAST if the generation matches. |
| PER_BACKEND_ATTACH | Must run on every attach to a backend; FAST must run it, possibly through a dedicated attach path. |
| PER_PLAYER_INSTANCE | Bound to the `ServerPlayer` object; always runs, because a new backend always creates a new instance. |
| REQUIRED_FOR_FAST_ATTACH | FAST_ATTACH cannot exist without an equivalent. |
| UNSAFE_TO_SKIP | Never bypassed. If FAST cannot satisfy it, FAST is unavailable. |

Rules:
- FAST_ATTACH is **not** a direct bypass of these hooks.
- The expected shape is a server-side attach path that still fires every PER_BACKEND_ATTACH and
  PER_PLAYER_INSTANCE hook, and skips only proven STATIC_ONCE_PER_CLIENT_SESSION negotiation.
- Whether that is achievable on Forge 1.20.1 without breaking mods is UNKNOWN.

## 10. Target preparation
```
/myisland
  → API wakes target backend
  → target Module receives PREPARE_PLAYER(transition_id, player, destination hint)
  → resolve destination (dimension, position)
  → load destination chunk; prefetch view-distance chunks
  → publish manifest (§5) and readiness (§12)
  → TARGET_READY
  → Velocity begins the actual switch
```
- The player stays in PLAY on the source backend throughout.
- Expensive work (wake, chunk load and generation, manifest computation) is outside the visible
  transition latency by construction.
- Preparation is cancellable and idempotent per `transition_id`.
- An abandoned preparation expires (bounded TTL), and its chunk tickets are released.

## 11. Safety model
Never:
- unknown → compatible;
- missing fingerprint → FAST_ATTACH;
- "probably the same modset" → FAST_ATTACH.

Only exact proven compatibility allows an optimization.

Any of the following denies FAST_ATTACH and falls back to WARM_LOGIN or FULL_LOGIN:
- timeout;
- missing manifest;
- version mismatch;
- unknown extension;
- invalid cache;
- protocol error;
- target restart;
- stale generation.

The player must never hang:
- Every non-terminal state has a bounded timeout with a defined fallback edge (§13).
- A fallback after CLIENT_DETACH must lead to a reachable FULL_LOGIN, or to a return to the source
  backend. FT-SAFETY-1 must decide which, per state.

## 12. Freshness and ABA

| Field | Purpose |
|---|---|
| `server_id` | stable backend identity (e.g. `island42`) |
| `run_id` | unique per JVM start; never reused (random 128-bit, or a monotonic persisted counter plus host) |
| `manifest_version` | schema |
| `manifest_generation` | content hash of the manifest's generation fields |
| `readiness_generation` | per-`run_id` monotonic counter, incremented on each TARGET_READY and invalidated by restart, shutdown or Module reload |

Rules:
- Velocity binds a transition to `(server_id, run_id, readiness_generation)`. Island42 run A is
  never confused with Island42 run B, despite the same name.
- A readiness or manifest from a superseded `run_id` is stale and leads to a fallback.
- The attach request carries the tuple, and the target re-checks it at BACKEND_ATTACH, closing the
  "restart between READY and attach" window.
- The client cache is keyed by generation, never by `server_id`: content-addressed reuse across
  backends (§6).

## 13. Transition state machine (proposal; FT-PROTOCOL-1 finalizes)
```
IDLE
 └─▶ PREPARING_TARGET ──timeout / target error──▶ ABORTED (player stays on source)
      └─▶ TARGET_READY
           └─▶ MANIFEST_COMPARE ──missing / stale / unknown──▶ select WARM or FULL
                ├─▶ FULL ─▶ existing CRP + Forge login path ─────────────────┐
                ├─▶ WARM ─▶ login state machine with proven-equal reuse ─────┤
                └─▶ FAST                                                     │
                     └─▶ CLIENT_DETACH ──client refuses / timeout──▶ FULL ───┤
                          └─▶ BACKEND_ATTACH ──run_id/readiness changed,     │
                               │                attach error──▶ FULL ────────┤
                               └─▶ DYNAMIC_SYNC ──protocol error──▶ FULL ────┤
                                    └─▶ FIRST_PLAYABLE ◀──────────────────────┘
                                         └─▶ COMPLETE
```
Error edges:
- Before CLIENT_DETACH, every failure leaves the player on the source backend (ABORTED), or picks a
  safer mode.
- After CLIENT_DETACH, every failure degrades to FULL_LOGIN to the same target. If the target is
  unavailable, FULL_LOGIN goes to the source or lobby.
- Each state has a timeout with its fallback edge; values come from FT-OBS-1 measurements.
- A FULL fallback after a partial FAST must start from a client state FULL accepts. The client mod
  must restore or discard the retained static state. FT-SAFETY-1 must prove the client cannot be
  left half-attached.

## 14. Static vs dynamic state

| Potentially reusable static (generation-scoped, needs a proof) | Always dynamic / per backend |
|---|---|
| registries | `ServerPlayer` |
| mod and channel compatibility | dimension, position |
| recipes | chunks, entities, block entities |
| tags | inventory and capabilities where the backend is authoritative |
| selected config data | scoreboard and team state where backend-specific |
| client mod initialization | world time and weather |
| JEI/REI indexes (derived from registries, recipes and tags; valid only under their generations) | server-specific custom payloads |
| renderer allocations | |
| other immutable generation-scoped state | |

Moving an item between columns requires a written proof: it is immutable within its generation,
and its generation covers every input.

## 15. Velocity responsibilities
```
client mod  ⇄  nestworldvelocity  ⇄  Fast Transition Module (per backend)
```
- Velocity is the authoritative coordinator:
  - holds the session's client capability and generation cache view;
  - collects backend manifests;
  - decides the mode;
  - sequences PREPARE → READY → switch;
  - owns the `transition_id`.
- Backends never decide FAST_ATTACH unilaterally, and the client never decides it either. Both
  only report capability and state.

## 16. Module SPI requirements (FT-AUDIT-4)
MODULE-1 lifecycle is not proof that a Module can install early Forge/Minecraft transformers after
the target classes are loaded. The audit must sort every hook needed by §3.3 and §9 into:
- possible through the normal runtime Module SPI;
- requires a Mixin or transformer at bootstrap;
- requires a Core extension point.

If a Core extension point is needed, it is designed first as a **generic capability** (for example
a generic attach-lifecycle or login-phase extension), never as a hardcoded FastTransition hack in
Core.

## 17. Existing server-mod migration (classification later; nothing removed now)
Target classes per function:
- KEEP IN NORMAL FORGE MOD;
- MOVE TO MODULE;
- MOVE TO NESTWORLDVELOCITY;
- MOVE TO CLIENT MOD;
- OBSOLETE AFTER FAST_ATTACH.

| Function | Provisional note (unaudited) |
|---|---|
| batched handshake | likely remains for FULL and WARM; may become obsolete for FAST |
| registry challenge / cache | superseded by `registry_generation` (MODULE + VELOCITY) |
| recipe defer | superseded by `recipe_generation` reuse (WARM/FAST) |
| chunk prefetch | likely MOVE TO MODULE (§10 preparation) |
| parallel I/O | UNKNOWN; audit |
| chunk flush | UNKNOWN; audit |
| timing instrumentation | unify under FT-OBS-1 (`transition_id`) |

## 18. Observability (contract FT-OBS-1; required from the first implementation stage)

Per transition:
- **Identity:** `transition_id`, `source_server`, `target_server`, `source_run_id`,
  `target_run_id`.
- **Path:** `selected_path` (FULL / WARM / FAST), plus `mismatch_reason` (the first mismatching
  generation or failure) when not FAST.
- **Timings** (monotonic, attributed to client / proxy / backend clock domains): request, target
  wake, target ready, manifest ready, manifest compare, reset begin, backend attach, JoinGame,
  first chunk, player position, first playable, complete.
- **Traffic:** packet count, bytes total, FML bytes, config bytes, recipes bytes, tags bytes,
  chunks bytes, other bytes.
- **Cross-domain timestamps** need a defined correlation method (per-hop deltas, not cross-host
  clock subtraction), to be specified in FT-OBS-1.
- No performance claim is made without this data (P7).

## 19. Performance goals: targets and hypotheses, not guarantees

| | Current (stated) | Target |
|---|---|---|
| warm compatible target | 1–2 s | **< 1 s time-to-first-playable** (engineering target) |
| exploratory, only after measurements | — | ~300–700 ms (hypothesis, not a guarantee) |
| first unknown backend | 5–8 s | improved by WARM reuse; not a FAST target |
| first connect from server list | ~16 s | not a primary Fast Attach target; base client state must still be established |

## 20. Acceptance gates before any implementation

| Gate | Deliverable |
|---|---|
| FT-AUDIT-1 | Current CRP/FML transition execution graph: exact methods, packets, thread and context boundaries. |
| FT-AUDIT-2 | Forge player and login lifecycle classification (§9). |
| FT-AUDIT-3 | Static vs dynamic handshake payload classification (§5, §14). |
| FT-AUDIT-4 | Module SPI bootstrap and hook capability (§16). |
| FT-PROTOCOL-1 | TransitionManifest plus state machine, final (§5, §13). |
| FT-SAFETY-1 | Fallback, stale-generation and restart model (§11–§13). |
| FT-OBS-1 | Timing and traffic instrumentation contract (§18). |

Implementation may be authorized only after these documents are reviewed.

## 21. Not done in this phase
- No Fast Transition Module;
- no NestWorldCore patch;
- no Module SPI change;
- no new Mixins;
- no removal of the current Forge handshake;
- no removal of the server-mod;
- no Velocity protocol change;
- no new packets;
- no production deployment.

## 22. Open questions recorded for the audits
- Can a Forge 1.20.1 client re-enter PLAY on a new backend without a LOGIN phase while keeping
  FML's registry snapshot valid? (FT-AUDIT-1, FT-AUDIT-3)
- Which client-side state does CRP currently tear down that FAST_ATTACH would need to keep, or
  vice versa? (FT-AUDIT-1)
- Can `player_attach_generation` be computed mechanically from registered listeners and
  capability providers, or does it need declarations? (FT-AUDIT-2)
- Where does NestWorld region execution interact with `placeNewPlayer` and attach (player region
  ownership, mailbox admission) on the target backend? (FT-AUDIT-2)
