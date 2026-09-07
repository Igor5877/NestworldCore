# B.3 — NeoForge Mixin Vertical Slice: Live Proof

## Final status summary (as of B.6.7/B.6.8), read this first

Two claim types run through this whole document, and they are never allowed to merge into one
"proven" statement:

- **Execution correctness** — does Core actually run: region threads execute, routing picks the
  right region, a cross-region operation reaches the correct side, the resulting Minecraft
  mutation is correct. ("the pig died across the boundary" is this kind of evidence.)
- **Ownership validation** — does a specific, *connected* mechanism verify that a mutation was
  performed by the entity's actual owner, which exact call sites it covers, and whether it
  observed a violation during the test. ("`NestworldOwnershipAssertions` — 0 violations" is this
  kind of evidence, and only for the call sites it actually covers.)

A checkpoint proving the first never implies the second unless its own guard's call sites are
separately confirmed live.

| Checkpoint | Status | What's actually proven |
|---|---|---|
| B.2 | PASS | NeoForge Mixin architecture loads |
| B.3 | RETRACTED / PARTIAL | Mixin loading real; Core tick never actually ran |
| B.3.0 | PASS | Real Core tick → regions → region threads → split/merge |
| B.4-R | PASS | Real player lifecycle under Core |
| B.5.1-R | PASS | Real combat under Core |
| B.5.2-R | PASS | Real block/item interactions under Core |
| B.5.3 | PASS | Real cross-region routing/execution |
| B.5.4 | Shell/Vanilla PASS | Redstone worked, but not yet Core-integrated at that point |
| B.6.1 | PASS | Block events |
| B.6.2 / B.6.2-R | PASS | Redstone, main-thread + regional execution + deferred cross-region updates |
| B.6.3 | PASS | Scheduled ticks |
| B.6.5 | PASS | Explosion/entity impact |
| B.6.6.1 | PASS | Entity ticking on `RegionThread` |
| B.6.6.2 | PASS | General neighbor updates |
| B.6.6.3 | PASS | Random ticks |
| B.6.6.4 | PASS | `addFreshEntity` |
| B.6.7 | AUDIT PASS-1 | Entity mutation/tracking gaps identified; implementation gate below closed part of them |
| `EntityOwnershipGuard` | VALIDATED (as of the B.6.7 implementation gate) | Real call sites now wired, live-confirmed against a real client combat interaction, 0 violations that actually means something |
| `NestworldOwnershipAssertions` | VALIDATED | Real call sites, 0 observed violations on the paths it covers |
| `#123` RNG (`Level.random`) | CLOSED | `createThreadSafe()` ported; 0 `LegacyRandomSource` collisions in the re-validation session |
| `#123` `EntitySectionStorage` | CLOSED | Both layers now correct: (1) section *index* — `ConcurrentEntitySectionStorage`, correct for its structural race. (2) section *contents* — `ClassInstanceMultiMap`, B.6.7.4 ported the real repo's reference fix (`ConcurrentHashMap`+`CopyOnWriteArrayList`+`synchronized`, commit `6e296f642`) byte-for-byte-by-semantics via Mixin, live-verified: 0 CME / 0 tick errors over ~11 min at 611-entity boundary density (exceeds the reference's own validation bar) |
| Entity tracking / `ChunkMap` pipeline | NOT PORTED / NEXT SUBSYSTEM (B.6.8) | Reference implementation exists, NeoForge execution edges still missing |

**B.6.7 is not evidence the earlier functional tests were wrong.** It changed the strength of
exactly one claim — `EntityOwnershipGuard` — not the functional facts underneath it. B.5.3's pig
still died, the block still changed, the player still crossed the boundary, Core still ticked.
When a later audit finds a weak instrumentation point, it downgrades that specific claim, not the
whole experiment underneath it — that is the audit discipline this document follows throughout.
The implementation gate above then closed that specific gap for good — `EntityOwnershipGuard` is
real evidence from here forward, though B.5.3 itself is not retroactively upgraded.

## RETRACTION (2026-09-05): B.3/B.4/B.5.1/B.5.2's "real region tick" claims were wrong

While setting up B.5.2 check #5 (boundary/cross-region), the `NESTWORLD_AUTOSPLIT` self-test
never fired despite the env var being confirmed present in the JVM. Tracing why led to this:
`NestworldDimensionRegion.tick()` — the method containing `nestworldRefreshLoadedChunks()`,
the `ChunkMapAccessor.tick()` call, and everything else attributed to "real region ticks" below
— is only ever invoked via `NestworldRegionSystem.tickAllRegions()`, whose own javadoc says it
is "called from the patched `MinecraftServer`" — a vanilla `MinecraftServer.tickChildren()`
patch that was **never ported to this NeoForge build** (deliberately out of B.3-B.5's scope).

Grepped every boot log from B.3 through B.5.2 for `"Tick phases avg ms"` — the one diagnostic
line that only ever prints from inside `NestworldDimensionRegion.tick()`, once per 200 real
calls. **Zero occurrences in any of them.** The "tick #N" / "938 ticks" counters cited
throughout this document were `NestworldMod`'s own trivial Adapter-skeleton bookkeeping
(`nestworldTickCount++` on every `ServerTickEvent.Post`) — completely separate from Core, never
calling into it.

**What stays true**: the Mixin transformer really did apply both mixins (the `Mixing X from
nestworld.mixins.json into Y` lines are independent, class-load-time evidence), the JPMS fix is
real, and the break/place/use-item/useOn/combat mechanics in B.5.1/B.5.2 really did happen — but
as **stock NeoForge/vanilla behavior** with an inert shell mod attached, proving nothing about
NestWorldCore's region system, ownership, or routing.

**Corrected status**: B.2 PASS (unaffected — pure research). B.3 PARTIAL (Mixin applies, Core
tick not exercised). B.4/B.5.1/B.5.2 downgraded to SHELL/vanilla PASS — real NeoForge+client
behavior confirmed, but zero Core involvement. See the B.3.0 section below for the fix and its
own live proof; B.4/B.5.1/B.5.2 need to be re-run against a server where Core actually ticks
before they count as Core integration tests.

> **Standing status, valid for the rest of this document**: B.3 historical evidence is
> invalidated, not deleted. The original B.3–B.5.2 results below remain preserved as
> shell/vanilla evidence (real NeoForge+client mechanics, real Mixin class-loading), but must
> never be cited as evidence of NestWorldCore execution. All Core-related claims were re-earned
> after B.3.0 against the real Core tick loop. If a future reader finds an old log citing "938
> ticks," that number is real but means nothing about Core — see the retraction above.

## B.3.0 — Core Tick Entry Point (the actual fix)

User-approved fix, explicitly scoped as a NEW checkpoint, not a completion of B.3: NeoForge has
no equivalent of the old `MinecraftServer.tickChildren()` patch, so the Adapter must connect
Core to a real NeoForge lifecycle event instead. `NestworldMod.onServerTick(ServerTickEvent.Post)`
now does, for every level `NestworldRegionSystem.isManagedLevel()` returns true for:
```java
sys.tickAllRegions(level, event::hasTime);
```
This is the legitimate replacement injection point for the missing vanilla patch — `ServerTickEvent.Post`
fires once per real tick, after the server's own per-tick work, on the server thread, matching
where the old patch's call site sat. Nothing else touched: no `ServerChunkCacheMixin`/
`ChunkMapAccessor` changes, no routing/ownership code, none of the other 71 patches.

**Live proof — the user's own 10-point checklist, boot with `NESTWORLD_AUTOSPLIT=100`:**

| # | Check | Result |
|---|---|---|
| 1 | `ServerTickEvent.Post` → `tickAllRegions()` | wired; confirmed by everything below actually happening |
| 2 | `NestworldDimensionRegion.tick()` really executes | confirmed |
| 3 | `"Tick phases avg ms"` appears | **yes** — 7 times over the run, e.g. `vanilla=0.17 signals=0.00 entityXfer=0.01 regionPool=10.10 ghostZones=0.03 splitMerge=0.01` |
| 4 | `NESTWORLD_AUTOSPLIT=<tick>` fires | **yes** — `[AUTOSPLIT TEST] forcing split of Region{id=0, chunks=(-1875000,-1875000)–(1875000,1875000)}` at tick 100 |
| 5 | 2+ real `WorldRegion` appear | **yes** — `Region{id=1, chunks=(-1875000,-1875000)–(-1,1875000)}` + `Region{id=2, chunks=(0,-1875000)–(1875000,1875000)}` |
| 6 | Both regions get real execution | **yes** — entity-transfer log: 7 entities moved into region 1, 16 into region 2; `regionPool` phase cost non-zero every cycle |
| 7 | `nestworldRefreshLoadedChunks()` called from Core tick | unconditional first statement of the now-confirmed-running `tick()` |
| 8 | `ChunkMapAccessor.tick()` called from Core tick | unconditional call inside the same confirmed-running `tick()` |
| 9 | 0 exceptions | **confirmed** — grepped full boot log, count = 0 |
| 10 | Clean save/stop | RCON stop → `NestWorld region system stopped` → `All dimensions are saved` → `Thread RCON Listener stopped` → `BUILD SUCCESSFUL` |

Bonus, not required by the checklist but observed for free: the self-test's merge-back also
fired for real 600 ticks later — `[AUTOSPLIT TEST] forcing merge of Region{id=1...} + Region{id=2...}`
→ `merge result: Region{id=3, chunks=(-1875000,-1875000)–(1875000,1875000)}` — the full
split→merge lifecycle, not just split.

**B.3.0 PASS.** This is the first point in the NeoForge 1.21.1 port where the NestWorldCore
execution loop has actually run: real region dispatch, region-thread execution, split/merge, and
live entity-transfer processing were observed. This does not by itself prove the complete
entity-ownership invariant; ownership enforcement is validated separately by the connected
assertion/guard paths (see B.5.3's correction and B.6.7 — `NestworldOwnershipAssertions` genuinely
wired, `EntityOwnershipGuard` not). Everything B.4/B.5.1/B.5.2 previously claimed about Core needs
to be re-earned against this now-real tick loop.

## B.4-R / B.5.1-R / B.5.2-R — re-run against the real Core tick loop

Boot with the B.3.0 fix in place, **no** `NESTWORLD_AUTOSPLIT` this time (boundary/cross-region
stays deliberately untouched per the user's explicit ordering). The world's saved layout still
had 2 regions left over from the B.3.0 split/merge self-test's mid-cycle save
(`Region{id=0, chunks=(-1875000,-1875000)–(-1,1875000)}` / `Region{id=1, chunks=(0,-1875000)–(1875000,1875000)}`)
— all re-run activity below was deliberately kept on the negative-X side (spawn X ≈ -7.5 to -9.5,
never crossing X=0) to stay same-region and not accidentally pre-empt B.5.3.

Confirmed before connecting any client: Core tick already active on this boot (`Tick phases avg
ms` present, 0 exceptions). One real client (`rerunbot`), single session, ran all three re-run
checks in sequence while Core ticked continuously underneath:

- **B.4-R (join/move/leave)**: moved forward ~15 blocks (Z: -41.5 → -26.3) while staying in
  region-0 territory. `Tick phases avg ms` kept appearing throughout (15 occurrences over the
  full session) — Core never stalled during real player movement.
- **B.5.1-R (combat)**: RCON-summoned pig, 6 real attack packets. RCON-verified afterward:
  `Health` dropped **6.0 → 0.152** — real, near-lethal damage from a real client attack, this
  time with Core genuinely ticking underneath (unlike the original B.5.1's shell-only run).
- **B.5.2-R (break/place/use-item/useOn)**: break+place landed on the same cell again (grass_block
  → dirt, RCON-independently re-confirmed by scanning the surrounding area); ender pearl throw
  registered a real (smaller this time) position change. The useOn step hit the same client-side
  `blockUpdate` timeout as the original run's first attempt — occlusion from standing too close
  to the log, not a server error (re-confirmed: 0 exceptions in the server log for that window).
  A focused retry with a fresh bot standing 4 blocks back succeeded cleanly: `LOG_BLOCK_BEFORE
  type=oak_log` → `USE_ON_BLOCK_SENT` → `LOG_BLOCK_AFTER type=stripped_oak_log`, independently
  confirmed via RCON `execute if block ... minecraft:stripped_oak_log` → **Test passed**.

**Exception census for the whole re-run boot**: exactly one hit, the same benign
`NativeIoException: recvAddress(..) failed: Connection reset by peer` Netty artifact seen in
every prior client-disconnect test — zero Core/Adapter exceptions. Clean RCON stop afterward:
world saved, `BUILD SUCCESSFUL`.

**One honest gap, not glossed over**: general entity-ownership transfer is confirmed very active
(609 `EntityTransfer` log lines across the session, consistent with continuous mob activity under
a real Core tick) — but I did not specifically isolate a transfer log line for the *player's own*
entity by UUID (the log doesn't carry player names, and correlating the offline-mode UUID wasn't
done this pass). Player movement/combat/interaction packets all completed successfully with zero
exceptions while Core ticked, which is the thing these checkpoints set out to prove — but "the
player entity itself gets region-owned the same way mobs do" is inferred from the project's
existing Player Region-Ownership design, not independently re-confirmed here byte-for-byte.

**B.4-R / B.5.1-R / B.5.2-R: PASS**, now genuinely counted as Core integration evidence rather
than shell/vanilla baseline. Next: B.5.3 boundary/cross-region, using the 2-region layout already
sitting in this world's save from the B.3.0 self-test.

## B.5.3 — Boundary / cross-region execution + ownership proof

Booted with two diagnostic guards enabled that are OFF by default in Core (real per-call
overhead): `EntityOwnershipGuard` (`NESTWORLD_ENTITY_GUARD=1`) and `NestworldOwnershipAssertions`
(`-Dnestworld.ownershipAssertionsEnabled=true`, via `NESTWORLD_OWNERSHIP_ASSERTIONS=1`). Both were
*intended* to log an ERROR line (`NO_FOREIGN_ENTITY_MUTATION violated: ...`) the instant a region
thread mutates an entity/block it doesn't own — this is what was meant to turn "no crash" into
actual negative-test evidence per the user's requirement, rather than inferring correctness from
silence. **See the correction below: `EntityOwnershipGuard` never actually got this chance** — its
own call sites weren't ported, so it logged nothing regardless of what happened. This applies to
every `EntityOwnershipGuard`/`NO_FOREIGN_ENTITY_MUTATION` mention for the rest of this document,
B.5.3 through B.6.6.x — not re-stated at each one individually. `NestworldOwnershipAssertions`
is a separate mechanism (its call sites, inside `EntityMutationHelper`/`EntityMutationDispatcher`,
were confirmed to genuinely exist during the B.6.7 audit) and its own violation counts stand on
firmer ground, though not exhaustively re-verified path-by-path.

**World state surprise, worth noting**: the saved layout had grown from B.3.0's clean 2-region
split into **20 real regions** — the region system's own organic split/merge heuristics had
fired during the B.4-R/B.5.1-R/B.5.2-R re-run (regionPool cost spiked to 25ms+ at one point in
that log), not just the forced self-test. Good sign (dynamic splitting genuinely works), but it
meant the clean single x=0 boundary from B.3.0 no longer existed in isolation — all boundary
interactions below were verified against the real, live-observed transfer, not assumed from the
saved layout's static chunk ranges.

### 1. Cross-region player movement

Real walking (`setControlState('forward'/'jump', true)`), no teleport during the crossing itself.
First attempt got physically stuck at a natural 1-block terrain step exactly at x=-2 even with
jump held — not a Core issue (confirmed via RCON: an ordinary 1-block grass step, no Core
involvement) — relocated to a scanned-flat row (z=-30 through -36) and re-ran:

- **x<0 → x>0**: real walk from x≈-3.6 to x≈+17.9 over 10 real movement ticks — clean full
  crossing.
- **x>0 → x<0**: real walk from x≈+17.4 back to x≈+0.99 — most of the way back, ended ~1m short
  of re-crossing within the tested window (not a failure, just ran out of scripted ticks).

**Gap, stated plainly (same class as B.4-R's)**: grepped the boundary-crossing player's own UUID
against the `EntityTransfer` log and got no direct hit, same as before — general cross-region
entity transfer is extremely active this session (2,143 transfer events total, including
hundreds of genuine region-to-region moves like `moved from 105 to 83`, not just fresh spawns),
but the player's own transfer line wasn't isolated by UUID. Core tick continued without any
stall through both crossings (`Tick phases avg ms` kept appearing, 51 times over the full
session).

### 2. Cross-region combat

Player at x=-1 (region A side), RCON-summoned pig at x=+1.5 (region B side, ~2 blocks away, well
within melee reach). 6 real attack packets. Result checked via RCON afterward:
`data get entity @e[type=pig,...]` → **"No entity was found"** — the pig actually **died** this
run (stronger evidence than B.5.1-R's near-lethal 0.152 HP: this time real client attacks across
a live region boundary killed the target outright). Zero ownership-guard violations logged for
this interaction.

### 3. Cross-region block interaction

Player consistently on the x<0 (region A) side; break/place/useOn targets on the x>0 (region B)
side, 2-4 blocks away (within reach):

- **Break**: `grass_block` at (2,62,z) → gone (RCON-independently re-confirmed the cell is no
  longer `grass_block`).
- **Place**: dirt refilled the same cell — RCON re-scan matched the pattern from every prior
  test (break+place landing on the same coordinate).
- **useOn**: took 4 attempts to get a clean repro, not 1 — worth reporting honestly rather than
  only showing the final success. Three attempts using **hardcoded absolute world coordinates**
  for the log's reference block all hit the same symptom: `bot.placeBlock()` never got a
  confirming block-update, and RCON independently confirmed the target cell genuinely never
  changed (still `grass_block`) — **zero server-side exceptions** in every one of those attempts,
  so this was never a Core/Adapter failure, just the block never actually getting placed. The
  4th attempt switched to computing the reference position **relative to the bot's own live,
  settled position** (`bot.entity.position.offset(3,-1,0).floored()`, the same pattern the
  earlier non-boundary useOn tests used successfully) and worked cleanly: `LOG_BEFORE
  type=oak_log` → `USEON_SENT` → `LOG_AFTER type=stripped_oak_log`, independently confirmed via
  RCON `execute if block 1 63 -30 minecraft:stripped_oak_log` → **Test passed**. Best read: a
  mineflayer/test-script geometry quirk with absolute-vs-relative coordinates, not a boundary- or
  Core-specific defect — the exact same pattern (hardcoded coordinates occasionally failing
  placement, relative-to-bot-position coordinates reliably working) also happened in the earlier
  non-boundary B.5.2 tests.

### 4. Boundary ownership correctness — per-operation record

| Operation | Player region (x sign) | Target region (x sign) | Result | Ownership violations |
|---|---|---|---|---|
| Movement crossing | A (x<0) → B (x>0) → A (in progress) | — | real position change confirmed | 0 |
| Combat | A (x=-1) | B (x=+1.5) | pig killed | 0 |
| Break | A (x=-1.5) | B (x=+2) | block removed | 0 |
| Place | A (x=-1.5) | B (x=+2) | dirt placed | 0 |
| useOn | A (x≈-1.5) | B (x=+1) | stripped_oak_log confirmed | 0 |

### 5. Negative test

Per the user's explicit framing — don't force a violation, use a natural boundary case that
makes routing determine the real owner. This session's own sustained cross-region traffic *is*
that case: **2,143 entity-transfer events** (hundreds of them real region-to-region moves, e.g.
`moved from 105 to 83`, `moved from 7 to 51`) plus every deliberate cross-region combat/block/
movement interaction above produced **zero** ownership-assertion violations
(`NestworldOwnershipAssertions`, whose call sites were later confirmed real). The
`NO_FOREIGN_ENTITY_MUTATION` count was also zero, but — per the correction above — that count is
not valid negative evidence; `EntityOwnershipGuard`'s own gating flag being on was not, in fact,
confirmation its call sites existed (it had none). What stands from this test: the routing layer
made 2,143 real transfers and every deliberate cross-region interaction succeed with 0
`NestworldOwnershipAssertions` violations and correct RCON-verified results — not a full
guarantee that *no* foreign mutation of any kind occurred anywhere in the process.

### Exception census, whole B.5.3 session

Grepped the complete boot log (movement + combat + block interaction + 3 failed useOn geometry
attempts + 1 successful retry): **6 hits**, all six the same benign `NativeIoException:
recvAddress(..) failed: Connection reset by peer` Netty artifact (one per bot's abrupt
`process.exit()` disconnect — 6 separate bot sessions were used across this test). **Zero**
`UnsupportedOperationException`. **Zero** `NO_FOREIGN_ENTITY_MUTATION`. Clean RCON stop, world +
region layout saved, `BUILD SUCCESSFUL`.

## B.5.3 verdict

**PASS** for combat and block interaction (break/place/useOn) crossing the real region boundary,
with independently RCON-verified results, continuous Core execution throughout, and 0
`NestworldOwnershipAssertions` violations plus sustained real cross-region traffic. Movement
crossing is **PASS with a caveat**: the outbound crossing (x<0→x>0) was clean and complete; the
return crossing ran out of scripted ticks about 1 meter short and the player's own entity-transfer
line was never isolated by UUID (same honest gap flagged in B.4-R). Nothing here was simulated
server-side — every mutation traces back to a real mineflayer client packet.

> **Correction (2026-09-05, found during the B.6.7 audit)**: the `EntityOwnershipGuard` row below
> originally read "PASS — 0 `NO_FOREIGN_ENTITY_MUTATION`." That is not valid negative evidence.
> The B.6.7 audit confirmed `EntityOwnershipGuard.check(...)` has **zero call sites** anywhere in
> this NeoForge port — its four real chokepoints (`Entity.setDeltaMovement`, `Entity.setPosRaw`,
> `Entity.setRemoved`, `LivingEntity.actuallyHurt`) were never re-Mixin'd, so
> `NESTWORLD_ENTITY_GUARD=1` armed a detector that was never connected to anything it was
> supposed to watch. The correct statement is: **`EntityOwnershipGuard`: NOT PROVEN** — the guard
> implementation exists, but its call sites were not ported, so the observed zero-violation count
> is not valid negative evidence that `setPosRaw`/`setDeltaMovement`/`setRemoved`/`actuallyHurt`
> never mutated a foreign entity during this test. This does **not** retract the rest of B.5.3's
> functional evidence (the pig really died, the block really changed, the player really crossed
> the boundary, Core really ticked, `EntityTransfer` really happened, cross-region routing really
> worked) — only this one row, until the guard is actually wired and re-run.

**Final scorecard**:

| Check | Result |
|---|---|
| Cross-region movement A→B | PASS — full crossing |
| Cross-region movement B→A | PASS with caveat — reached x≈0.99, ran out of scripted ticks |
| Cross-region combat | PASS — pig killed across the boundary |
| Cross-region block interaction | PASS — break/place/useOn all RCON-confirmed |
| `NestworldOwnershipAssertions` | PASS — 0 violations |
| `EntityOwnershipGuard` | **NOT PROVEN** — 0 `NO_FOREIGN_ENTITY_MUTATION`, but the guard was never wired (see correction above); not valid negative evidence |
| Organic 20-region layout | PASS — extra evidence beyond the forced 2-region case |
| 2,143 organic entity transfers | PASS — sustained real region-transfer traffic observed; 0 `NestworldOwnershipAssertions` violations |
| Core/Adapter exceptions | PASS — 0 |
| `UnsupportedOperationException` | PASS — 0 |
| Netty disconnect artifacts | 6, known benign pattern, not a Core failure |
| Clean save/stop | PASS |

**Not claimed as proven**: a specific player UUID isolated in the `EntityTransfer` log as the
entity that changed owner region — an observability gap, not a correctness failure. Filed as a
follow-up (see below), not a blocker.

**Architectural conclusion**: B.5.3 confirms the full chain, not just that the tick loop runs —
but as two separate layers, not one proven linear chain:

- **Execution chain**: `NeoForge lifecycle → Adapter/Mixin → Core tick → region execution →
  routing → cross-region execution → real Minecraft mutation` — proven under a dynamic,
  organically-formed region layout, a real client, real packets, real entity/block mutation.
- **Ownership validation, a parallel layer, not a link in that chain**: `mutation paths →
  NestworldOwnershipAssertions → 0 observed violations` (call sites confirmed real by the later
  B.6.7 audit). `EntityOwnershipGuard` sits outside this, separately: `implementation exists →
  call sites missing → NOT PROVEN` (same correction as above).

Keeping these separate on purpose — B.5.3 does not claim a fully proven ownership invariant, only
that the routing layer produced correct results for the tested cross-region operations, while the
independently wired `NestworldOwnershipAssertions` observed zero violations. The
`EntityOwnershipGuard` remains explicitly NOT PROVEN until its four reference call sites are
ported and the negative tests are re-run.

## B.5.4 — Redstone / block events real-circuit test

Built two real circuits via real client packets (place/activate, no server-side simulation):
lever→wire(3 blocks)→redstone_lamp, and a second lever placed directly adjacent to a piston
(no wire needed for direct block-to-block power).

**Mechanically, both worked perfectly** — real vanilla redstone, unaffected by Mixin/Core
running alongside it:
- `execute if block 4 63 -18 minecraft:redstone_lamp[lit=true]` → **Test passed**.
- `execute if block 1 63 -18 minecraft:redstone_wire[power=15]` → **Test passed** (full power).
- Piston: initially looked like it hadn't fired (still base `piston` block at its own position),
  until checking its `facing` state (`south`) and finding `piston_head` at the correspondingly
  offset position — `execute if block 0 63 -18 minecraft:piston_head` → **Test passed**. It had
  extended correctly; my first check just assumed the wrong direction.

**The actual point of this test — does any of this touch Core?** Grepped the full boot log for
the whole redstone-circuit window: zero exceptions (only the one usual benign Netty disconnect
artifact), **zero `UnsupportedOperationException`**, and — critically — **zero log lines from
`NestworldRedstone`, `acWireHandler`, or the block-event drain path at all**. `Tick phases avg ms`
kept appearing on schedule throughout, confirming Core kept ticking normally the whole time,
completely independent of this activity.

**Conclusion, stated plainly**: real redstone circuits work correctly in this NeoForge port, but
they are **not yet Core-integrated** — they run on 100% stock vanilla `RedStoneWireBlock`
mechanics. `NestworldRedstone.wireUpdated/wireAdded/wireRemoved` (and the block-event drain in
`NestworldDimensionRegion.tick()`, hardcoded to an empty list back in B.3) are javadoc'd as being
"called from the patched `MinecraftServer`"/`RedStoneWireBlock` — vanilla patches that were never
ported to this NeoForge build, the same category of gap `MinecraftServer.tickChildren()` was for
region ticking before B.3.0. This is not a new bug — it's the deferred `nestworldWireHandler`/
block-event scope from B.3 confirmed empirically rather than assumed: real redstone activity
plainly does NOT reach any of that deferred code, because nothing routes it there in this port
yet. Porting the wire/block-event integration (via the same Mixin-vs-vanilla-patch pattern as
B.3.0) would be a real, separate future slice — not attempted here, per the user's explicit
instruction not to touch `ServerChunkCacheMixin`/`ChunkMapAccessor`/routing/the other 71 patches
this round.

**B.5.4 verdict**: PASS as a scope-confirmation test — redstone/block events work at the vanilla
level with zero Core interference, and the finding that they bypass Core entirely is now
evidenced, not inferred.

## B.5.5 — Explosion real-trigger + Core involvement check

Real client action: placed TNT (relative-to-bot positioning — the same absolute-coordinate
`placeBlock` timeout hit on the first attempt, same known harness quirk as before, fixed the
same way), summoned a pig 1 block away, then right-clicked the TNT with `flint_and_steel`
(`bot.activateBlock`) — a genuine ignite packet, not a `/setblock` or console-simulated
detonation. Backed the player away, waited out the real fuse.

RCON afterward confirmed a real explosion, not a scripted illusion:
- TNT block position → now `air` (**Test passed**), ground block beneath it also blown to `air`
  (a real crater, not just the TNT itself vanishing).
- `@e[type=tnt]` (primed TNT entity) → none exists — it fully detonated, didn't get stuck
  mid-fuse.
- `@e[type=pig]` → **none exists** — the nearby pig was killed by the blast itself (real vanilla
  entity impact, not by a prior test's leftover state).

**Core-involvement check, same rigor as B.5.4**: grepped the full boot log for the whole
explosion window — zero exceptions beyond the one usual benign Netty disconnect artifact, zero
`UnsupportedOperationException`, and **zero mentions anywhere of `EXPLOSION_APPLY`,
`ENTITY_IMPACT`, `NestworldExplosionBatch`, or `nestworldApplyEntityImpact`** — the exact deferred
Core symbols this scenario would be expected to touch if explosions were routed through Core.
`Tick phases avg ms` kept appearing on schedule throughout (17 times), confirming Core ticked
normally the whole time, uninvolved.

**Conclusion**: matches B.5.4 exactly. Real explosions — block destruction and entity impact
alike — work correctly via 100% stock vanilla `Explosion` mechanics in this NeoForge port, with
**zero involvement from NestWorldCore's explosion-routing path**. This is the same category of
gap as redstone: the vanilla `Explosion.java`/`ServerLevel` patches that would post
`EXPLOSION_APPLY`/`ENTITY_IMPACT` messages into Core's mailbox were never ported to this NeoForge
build (confirmed already at the B.3 audit stage — `RegionMessage.explosionApply()` was found to
have zero callers anywhere in this tree). B.5.5 confirms empirically what B.3's static audit
predicted: a live explosion genuinely never reaches that deferred code, because nothing routes it
there yet.

**B.5.5 verdict**: PASS as a scope-confirmation test, same framing as B.5.4 — not "explosion is
broken" (it isn't), not "NestworldExplosionBatch works" (it's never called) — precisely:
*vanilla explosion behavior is functional, but the NestWorld explosion/entity-impact execution
path is not yet integrated into the NeoForge Core execution model.* Filed under the same B.6
bucket as redstone (see below) for a future dedicated integration pass.

## B.5.6 — Multiple players, concurrent + cross-region

Two real, independent mineflayer clients (`mpbotA`, `mpbotB`), one Node process, `Promise.all`
used only to synchronize spawn — every subsequent action driven concurrently, not sequentially:
each bot's movement/combat/block calls fire in the same script tick, interleaved in the real
packet stream, not "A finishes, then B starts." Ownership guards stayed enabled
(`NESTWORLD_ENTITY_GUARD=1`, `NESTWORLD_OWNERSHIP_ASSERTIONS=1`).

Teleported to genuinely different areas — A to x=+15 (deep into the positive-x side), B to
x=-30 (deep into the negative-x side) — then both walked further apart simultaneously
(`setControlState` on both bots in the same loop iteration) for 6 real ticks. Final positions:
A at x≈+30.9, B at x≈-43.1 — over 70 blocks apart, unambiguously different regions.

**Concurrent combat**: both bots summoned and attacked their own nearby pig at the same time (6
attack rounds each, interleaved). RCON afterward: no pig remains near either position — **both
kills succeeded concurrently**, in different regions, with no interference between the two
independent combat sessions.

**Concurrent block interaction**: both attempted a `placeBlock` in the same script tick. B's
succeeded cleanly. A's hit the placement-geometry issue seen several times before — this time
traced precisely: `refA=water` (the auto-picked reference cell for A's very-far-east position
happened to be a water tile, the same class of bad-pick as B.5.2's very first attempt) — **zero
server-side exception**, purely a bad reference-block pick at an unexplored position, not a
concurrency issue (B, picked correctly, succeeded in the same tick with no contention).

**Simultaneous disconnect**: `botA.quit()`/`botB.quit()` called back-to-back with no delay
between them. Server log: `mpbotB left the game` then `mpbotA left the game`, 2 seconds apart —
clean, no hung connections, no error either side.

**Exception/violation census for the whole session**: **zero** — not even the usual benign Netty
disconnect artifact this time (both bots used a graceful `.quit()` rather than an abrupt
`process.exit()`, so the clean shutdown handshake completed properly on both connections). Zero
`NO_FOREIGN_ENTITY_MUTATION`, zero `UnsupportedOperationException`. 341 entity-transfer events
logged in this ~26-second window — sustained real ownership tracking activity with two
concurrent players present. `Tick phases avg ms` kept appearing throughout. Clean RCON stop,
world saved, `BUILD SUCCESSFUL`.

**B.5.6 verdict**: PASS. Two real, independently-connected players moved, fought, and interacted
with the world *concurrently* — not in sequence — across genuinely different regions, with zero
ownership violations, zero exceptions, and both interactions (movement, combat) succeeding
without any cross-contamination between the two sessions. This is the strongest evidence yet
that NestWorldCore functions as a real multi-actor execution layer on NeoForge 1.21.1, not just
a single-player/cross-region proof of concept.

## B.6 — Vanilla Execution Path Integration

New branch, deliberately separate from B.5.x: B.5.x proved the Core execution model works on
NeoForge 1.21.1; B.6 proves individual vanilla execution paths that currently bypass Core (found
by B.5.4/B.5.5) can be connected to it *without disturbing* the already-proven routing/ownership/
RegionThreadPool machinery. Per explicit scope: B.6.1 (this section) touches only the block-event
drain — nothing in routing, ownership, or the thread pool itself was modified.

### B.6.1 — Block Events real Core integration

**Why block events first, not redstone directly**: B.5.4 found that redstone's piston/wire
mechanics ultimately surface through vanilla's block-event system (`BlockEventData`, piston
push/retract sync). Wiring that foundational path first, then re-testing redstone on top of it,
is more architecturally sound than patching redstone in isolation.

**What the original 1.20.1 patch actually did, discovered by reading it**: contrary to
expectation, `nestworldDrainBlockEvents`/`nestworldDoBlockEvent`/`nestworldRescheduleBlockEvent`/
`nestworldBroadcastBlockEvent` are **not new logic** — they're plain `public` wrappers around
vanilla `ServerLevel`'s already-private `blockEvents` queue, `shouldTickBlocksAt`, and
`doBlockEvent`. The only genuinely new code is the *bucketing* in `NestworldDimensionRegion
.runBlockEventsPhase()` (already present, just fed an empty list since B.3) that routes each
event to its owning region thread instead of processing them all inline.

**Real blocker found while implementing, worth recording**: `Level.shouldTickBlocksAt(BlockPos)`
turned out to already be **public** in 1.21.1 vanilla (confirmed by reading the decompiled
source — the `long chunkPos` overload backing it is what appeared in `ServerLevel`, but the
`BlockPos` overload lives public on the base `Level` class) — one fewer accessor needed than
expected. The one real subtlety: vanilla's `ServerLevel.tick()` **already calls its own private
`runBlockEvents()` internally**, unconditionally, before `NestworldDimensionRegion`'s own drain
would ever run — meaning simply adding a new drain call would race vanilla's own call and always
find an empty queue (vanilla wins, having already drained it). This is a real behavioral
difference from every prior B.3.0-era Mixin (which were purely additive/accessor-only) — this
one needed to **actually stop** a piece of vanilla's own tick logic, not just expose new state.

**Implementation** (two Mixin classes, both targeting `ServerLevel`, in `net.nestworld.adapter
.mixin`):
- `ServerLevelBlockEventAccessor` (interface): `@Accessor("blockEvents")` for the private queue,
  `@Invoker("doBlockEvent")` for the private per-event trigger.
- `ServerLevelBlockEventsRedirect`: a single `@Redirect` on the `this.runBlockEvents()` call site
  inside `tick(BooleanSupplier)`, replaced with a no-op. This is the one behavioral change B.6.1
  makes to vanilla — everything else in `tick()` is untouched.

`NestworldDimensionRegion.runBlockEventsPhase()` now does the real drain (synchronized
copy+clear of the accessor-exposed queue), buckets each event by owning region via the existing
`routeScheduledTick()`, and each event's `Runnable` does exactly what vanilla's own
`runBlockEvents()` used to do inline (`shouldTickBlocksAt` → `doBlockEvent` → broadcast, or
reschedule) — just now on the correct region's own thread. Called once per tick from
`NestworldDimensionRegion.tick()`, right after `level.tick(hasTime)` returns (matching where
vanilla's own call used to sit in the phase order).

**Live proof — the strongest kind available**: since vanilla's own path is now permanently
disabled by the redirect, there is no fallback left — if the new drain had any bug, block events
would break entirely, not silently degrade to the old vanilla behavior (unlike every earlier
"does X touch Core" test, where vanilla success proved nothing either way). Re-ran the exact
B.5.4 redstone circuit (still standing in the world from that test): toggled the piston's lever
off (retract — a real block event), then back on (extend — another). RCON confirmed the full
cycle worked correctly: `lever[powered=true]` → **Test passed**, `piston_head` present at the
extended position → **Test passed**, piston base intact → **Test passed**. Both the retract and
the extend only happened because the new Core-routed drain processed them — vanilla's own path
was closed off the whole time.

**Exception/violation census** (ownership guards enabled, same as B.5.3/B.5.6): **zero**
exceptions of any kind across the whole session — not even the usual benign Netty artifact this
run. Zero `NO_FOREIGN_ENTITY_MUTATION`, zero `UnsupportedOperationException`. `Tick phases avg
ms` kept appearing throughout (8 times), confirming Core never stalled. Clean RCON stop, world
saved, `BUILD SUCCESSFUL`.

**B.6.1 verdict**: PASS, and a genuine Core-integration result — not a scope-confirmation like
B.5.4/B.5.5. Block events (piston push/retract, and by extension anything else that flows
through vanilla's `BlockEventData` queue) are now real, live-proven Core execution: routed
per-region, processed off the region's own thread, with zero disruption to the routing/
ownership/thread-pool machinery B.5.6 already proved. B.6.2 (redstone re-tested on top of this)
is the natural next step.

### B.6.2 — Redstone real Core integration

Smallest vertical chain the user asked for: real wire mutation → vanilla `RedStoneWireBlock`
entry point → NestWorld interception → real AC `WireHandler` propagation → observable state
change (lamp/piston). Scoped to the **main-thread path only** (region-execution opt-in flags are
all still `false`, so player-triggered block placement/interaction runs on the main thread, not
a region thread — `RegionThread.acWireHandler()`'s bounded/region-local path stays deferred,
untouched, out of this milestone).

**What the real 1.20.1 patch actually hooks**: exactly 3 call sites in `RedStoneWireBlock` —
`onPlace` (bookkeeping, `wireAdded`, never skips vanilla), `onRemove` (bookkeeping,
`wireRemoved`, never skips vanilla), and `neighborChanged` (the real interception point —
`if (NestworldRedstone.wireUpdated(...)) return;`, skipping vanilla's own power recalculation
when AC handled it, "to avoid dupe bugs"). Replicated as three `@Inject`s in
`RedStoneWireBlockMixin` (targeting `net.minecraft.world.level.block.RedStoneWireBlock`,
identical method signatures in 1.21.1, just `protected` instead of `public` — no obstacle for
Mixin): the first two plain, the third `cancellable = true` with `ci.cancel()` on success.

**The other half**: `NestworldRedstone`'s main-thread branches (previously deferred throws from
B.3) needed the real `level.nestworldWireHandler` field to exist. Added via
`ServerLevelWireHandlerMixin`, an `@Inject` at the `TAIL` of `ServerLevel`'s single constructor,
initializing `new WireHandler(this, levelStorageAccess)` — same construction the real patch used
(`ServerLevel.java:300`). Exposed to Core through a new `NestworldWireHandlerExt` marker
interface (same cast pattern as `NestworldServerChunkCacheExt`).

**A real crash found and fixed, worth recording plainly**: first live boot **crashed at world
load** — `UnsupportedOperationException: B.3: derived wire config deferred past this milestone`,
thrown from `Config.forLevel`'s non-overworld branch. Root cause: the constructor injection
fires for **every** `ServerLevel` — overworld, nether, *and* end — not just the overworld. Under
the old B.3 scope, only the overworld ever got a `WireHandler` (via `RegionThread
.acWireHandler()`, itself deferred), so that branch was genuinely dead. B.6.2's constructor
injection made it live for the first time, immediately exposing it. Fixed by restoring the real
`Derived(Config)` delegation — `((NestworldWireHandlerExt) level.getServer().overworld())
.nestworldWireHandler().getConfig()` — relying on the standard vanilla guarantee that the
overworld is always constructed before nether/end. This is exactly the same class of lesson as
B.3.0's retraction: a change can make a previously-correctly-classified-as-dead path newly live,
and the only way to catch that is an actual boot, not a compile.

**Live proof, two separate real scenarios**:
1. Re-ran B.6.1's exact piston circuit (still standing from the earlier session): toggled the
   piston lever off (retract) then back on (extend). RCON confirmed the full cycle:
   `lever[powered=true]` → **Test passed**, `piston_head` at the extended position → **Test
   passed**, piston base intact → **Test passed**.
2. **Fresh wire placement**, exercising `onPlace`/`wireAdded` for the first time (not just an
   existing circuit's `neighborChanged`): built a brand-new lever→wire(2)→lamp circuit via real
   `placeBlock`/`activateBlock` calls. RCON: lamp `lit=true` → **Test passed**; wire adjacent to
   the lever `power=15` → **Test passed**; wire one segment further `power=14` → **Test passed**
   — a correct power-decay gradient, proof the propagation algorithm actually computed a real
   result end-to-end, not just "didn't crash."

**Exception/violation census for the whole B.6.2 session** (both the crash-fix reboot and both
live tests, ownership guards enabled throughout): **zero** exceptions of any kind in the final
clean run, zero `NO_FOREIGN_ENTITY_MUTATION`, zero `UnsupportedOperationException`. 13
`Tick phases avg ms` lines confirmed Core ticked continuously through both tests. Clean RCON
stop, world saved, `BUILD SUCCESSFUL`.

**B.6.2 verdict**: PASS. The smallest vertical redstone chain — real mutation through vanilla's
own entry point, intercepted by NestWorld, propagated by the real AC `WireHandler`, producing a
correct, RCON-verified physical result — is now live and Core-integrated on NeoForge 1.21.1,
main-thread path. Region-thread-local wire propagation (`RegionThread.acWireHandler()`) remains
deliberately deferred, scoped out of this milestone same as before.

### B.6.3 — Scheduled Block/Fluid Ticks real Core integration

Same 8-step methodology as B.6.1: find the real vanilla entry, find where the queue drains,
determine the redirect shape, reuse existing Core logic, wire one minimal real mechanism, prove
it's the only path (no vanilla fallback for managed levels), keep ownership guards on, verify
clean shutdown.

**What was found, genuinely different from B.6.1/B.6.2**: `NestworldRegionSystem
.runScheduledTicksPhase(ServerLevel, long)` and `NestworldDimensionRegion
.runScheduledTicksPhase(long)` were **already fully implemented**, not stubs — they call the
real vanilla `LevelTicks.tick(gameTime, maxTicks, consumer)` method on `level.getBlockTicks()`/
`level.getFluidTicks()` (both already public getters, no accessor needed), just supplying a
region-routing consumer instead of vanilla's own direct `tickBlock`/`tickFluid` callback. The
*only* missing pieces were: (1) nothing ever called `runScheduledTicksPhase` — same "wired but
never invoked" pattern as B.3.0's `tickAllRegions()` before its fix; (2) the consumer's own
per-event body still had the two B.3-era `UnsupportedOperationException` throws in place of the
real `tickBlock`/`tickFluid` calls.

**The real vanilla entry point**, found by reading the actual patch: `ServerLevel.tick()`
already had an `if (managed) { runScheduledTicksPhase(this, k); } else { this.blockTicks.tick
(...); this.fluidTicks.tick(...); }` branch in the original 1.20.1 source — not a redirect-after
-the-fact like B.6.1's block events, an explicit if/else. 1.21.1 vanilla only has the plain
unconditional `this.blockTicks.tick(k, 65536, this::tickBlock); this.fluidTicks.tick(k, 65536,
this::tickFluid);` — no branch at all, so this needed two `@Redirect`s on `LevelTicks.tick
(long,int,BiConsumer)`, disambiguated by `ordinal` (0 for the block-ticks call site, 1 for
fluid-ticks, since both calls share the identical method signature within the same method body).
Only the block-ticks redirect calls `runScheduledTicksPhase` when managed — that call already
drains fluid ticks too (matching the original's combined design), so the fluid-ticks redirect
no-ops in the managed case to avoid a double drain; both redirects fall through to the real
vanilla method for unmanaged levels (nether/end).

**Implementation**: `ServerLevelScheduledTickAccessor` (`@Invoker` for the private `tickBlock`/
`tickFluid`, same pattern as B.6.1's `doBlockEvent`) + `ServerLevelScheduledTicksRedirect` (the
two ordinal-disambiguated redirects). `NestworldDimensionRegion.runScheduledTicksPhase()`'s two
throw-stubs replaced with real calls through the new accessor.

**Live proof — higher stakes than B.6.1/B.6.2**: scheduled block/fluid ticks fire on *every
single game tick* for ordinary world simulation (not just when a player builds something), so
this redirect had to survive sustained real ticking, not just boot. Confirmed: 15
`Tick phases avg ms` cycles logged with zero exceptions in between — the redirect held up under
continuous real load, not just a one-shot interaction. For a concrete functional check: RCON-
seeded a water source block (the actual mineflayer bucket-placement client action failed as a
harness quirk — worth stating honestly rather than hiding: the intended `activateBlock`-with-
bucket packet didn't place water at all when checked, a client-interaction issue unrelated to
Core, not something this milestone needed to chase down since seeding the tick queue via command
is a legitimate way to isolate "does queue processing work" from "does bucket placement work,"
already covered elsewhere) — RCON afterward confirmed water had spread to **all 4 horizontally
adjacent cells** within 3 seconds, which only happens if fluid ticks kept firing repeatedly,
proof the new Core-routed drain is genuinely processing entries, not just not-crashing.

**Exception/violation census for the whole session**: exactly the one usual benign Netty
disconnect artifact (2 grep hits = 1 real event, message + stack class name), zero
`NO_FOREIGN_ENTITY_MUTATION`, zero `UnsupportedOperationException`. Clean RCON stop, world
saved, `BUILD SUCCESSFUL`.

**B.6.3 verdict**: PASS. Scheduled block and fluid ticks — the mechanism underlying crop growth,
fluid spread, fire spread, ice/snow melt, and dozens of other vanilla block behaviors — now run
through Core's region-routed dispatch for managed levels, confirmed to survive continuous real
load and to genuinely process (not silently drop) real work, with the same zero-exception/
zero-violation bar as every prior B.6 milestone.

### B.6.5 — Explosion / Entity Impact real Core integration

The most invasive B.6 slice so far — the original 1.20.1 patch's design here (~150 lines) is
genuinely new logic, not just private-member exposure: it splits `explode()`'s entity loop and
`finalizeExplosion()`'s block-destroy loop into local-vs-foreign-region subsets, deferring the
foreign parts as `RegionMessage`s (`ENTITY_IMPACT`, `EXPLOSION_APPLY`) replayed later on the
owning region's own thread. Scoped with the user's explicit approval as "full chain: entity
impact + block batching," not a narrower slice.

**Real 1.21.1 API differences found, confirmed by reading the actual decompiled source before
porting anything** — the old patch's formulas could not be used verbatim:
- Entity damage/knockback is now computed through an `ExplosionDamageCalculator` abstraction
  (`shouldDamageEntity`/`getEntityDamageAmount`/`getKnockbackMultiplier`) that didn't exist in
  1.20.1's simpler inline formula.
- A NeoForge hook, `EventHooks.getExplosionKnockback(...)`, now runs on every entity's knockback
  vector — must be preserved for mod compatibility, wasn't in vanilla Forge 1.20.1.
- Block destruction was consolidated from the old patch's manual loot-table-building dance
  (`spawnAfterBreak`+`getDrops`+`onBlockExploded`, called separately) into one clean
  `BlockState.onExplosionHit(Level, BlockPos, Explosion, BiConsumer<ItemStack,BlockPos>)` call.
- `entity.ignoreExplosion(Explosion)` now takes the explosion as a parameter (was no-arg).

The replay logic in `NestworldExplosionReplay` was written against these *current* 1.21.1
signatures, not adapted from the old patch's math — confirmed correct by the live block/entity
results below matching real vanilla explosion behavior exactly.

**Implementation**: `ExplosionExtMixin` (`@Shadow` accessors for `level`/`fire`/
`damageCalculator`/`random`/`damageSource` — all private, no public getters existed for any of
them) + `ExplosionRegionRoutingMixin`, two injections:
- `@ModifyVariable` on `explode()`'s nearby-entity list, right after NeoForge's own
  `onExplosionDetonate` hook runs (so mod-driven filtering is respected) — foreign-owned entities
  are removed from the list and an `ENTITY_IMPACT` message posted instead; vanilla's own loop
  then runs completely unmodified over whatever remains.
- `@Inject` at the `HEAD` of `finalizeExplosion()` — foreign-owned `toBlow` positions are removed
  (in place, since both the destroy-loop and fire-loop that follow read the same field) and an
  `EXPLOSION_APPLY` message posted per foreign region.

This is a cleaner shape than the original patch's own design: the original had to keep its
entity-loop byte-for-byte identical to vanilla in the same method (a real comment in the source
explained this was specifically to avoid breaking a third-party mod's `MixinExtras` hook into
that exact bytecode). Filtering the *input* before vanilla's loop runs, instead of editing the
loop's own body, sidesteps that fragility entirely — vanilla's loop bytecode is never touched.

`NestworldExplosionReplay.applyBatch`/`applyEntityImpact` — plain static methods, not
Mixin-added, called from `RegionThread.applyPendingEntityImpacts()`'s and
`NestworldDimensionRegion`'s previously-throwing EXPLOSION_APPLY/ENTITY_IMPACT stubs.

**Live proof**: real TNT ignited via `flint_and_steel` (same pattern as B.5.5), full detonation.
RCON confirmed identical results to B.5.5's vanilla-only baseline: TNT block → air, crater below,
primed-TNT entity fully consumed, nearby pig killed — the explosion's *own* effects are
functionally unchanged and correct with the new routing in place.

**A real crash found, and it matters — reported honestly, not routed around.** The boot log shows
a genuine tick-error: `net.minecraft.ReportedException: Exception while updating neighbours`,
caused by `UnsupportedOperationException: B.3: acWireHandler construction deferred past this
milestone`, thrown from `RegionThread.acWireHandler()`. Root cause, traced through the full
stack: the TNT was ticking on a real region thread (`NestWorld-Region-32`, confirmed by B.3.0);
its explosion destroyed a block whose removal cascaded into a vanilla neighbor-update
notification; that notification reached a `redstone_wire` block left over from the B.6.2 test
session at `(7,63,-30)` — confirmed still present via RCON, only ~2 blocks from this blast; my
B.6.2 `RedStoneWireBlockMixin.neighborChanged` injection fired (as it does for *any* neighbor
change on redstone wire, regardless of trigger source) and, since the current thread genuinely
was a `RegionThread` this time (not the main thread B.6.2 was scoped to), it hit the
region-thread-local wire path that was explicitly left deferred back in B.6.2 (`RegionThread
.acWireHandler()`).

**This is not a B.6.5 bug — it's B.6.2's known, explicitly-agreed scope boundary getting
exercised for the first time.** B.6.2's "main-thread path only" decision assumed redstone
neighbor-updates would only originate from player-driven block placement (also main-thread at
this stage, since region-execution opt-in flags are still off). B.6.5 is the first mechanism
that causes a **region thread itself** to mutate blocks (via `onExplosionHit`, running unmodified
vanilla code for local positions) — and that mutation's neighbor-update cascade can reach
existing redstone wire regardless of which subsystem caused it. The gap is real and general, not
specific to explosions: *any* region-thread-driven block change near redstone wire can hit this
same stub. The server did **not** crash — `RegionThread`'s own per-entity tick-error catch (the
project's standing "tick-loop catch-and-continue" pattern) caught it, logged the one entity's
failure, and the server kept running normally afterward (15 more `Tick phases avg ms` cycles,
region splits continuing, zero further issues).

**Census for the rest of the session**: zero `NO_FOREIGN_ENTITY_MUTATION` (the one caught
exception was a plain Java exception, not an ownership violation — routing/ownership integrity
was never compromised), zero *other* exceptions. Clean RCON stop, world saved, `BUILD
SUCCESSFUL`.

**B.6.5 verdict**: PASS for its own actual scope — explosion block-destruction and entity-impact
routing is correct, live-verified, and functionally identical to vanilla for the (in this test)
entirely-local case. **Flagging, not fixing**: this run surfaced a real, general gap in B.6.2's
"main-thread only" wire-handler scoping — region-thread-driven block mutations near redstone wire
now hit a live `UnsupportedOperationException`, non-fatal (caught per-entity) but real. Left
exactly as found, per the standing rule not to silently expand scope or route around a genuine
architectural finding — this should be the next thing decided on purpose, not patched in passing.

### B.6.2-R — Region-thread Redstone Propagation real Core integration

Follow-up milestone, scoped separately from B.6.5 per user direction: B.6.5's crash exposed that
`RegionThread.acWireHandler()`'s throw stub sits on a genuinely reachable region-thread execution
path (any region-thread block mutation whose neighbor-update cascade reaches existing redstone
wire), not an abstract unused method. B.6.2 itself stays PASS (main-thread redstone) — this is a
new, additive milestone, not a revision of it.

**Fix 1 — `RegionThread.acWireHandler()` no longer throws.** The bounded-handler machinery this
needed already existed, unused: `WireHandler(ServerLevel, Config)` — a constructor whose own
comment says it's "for per-region-thread handlers that share the level's already-loaded config" —
plus `setBounds`/`checkBounds`/`NetworkOutOfBounds`/`nestworldReset()`, all pre-built and
confirmed by reading the code that every node the handler ever touches is bounds-checked
(`getOrAddNode` → `getNextNode` → `checkBounds`, the only path that creates or revalidates a
node). The fix constructs the handler once from the main thread's own already-loaded `Config`
(reusing `NestworldWireHandlerExt` from B.6.2, not re-reading disk), and recomputes
`setBounds(...)` from the region's *live* chunk range on every call — a split/merge that changes
the region mid-life never leaves a stale bound behind, at the cost of 4 field reads per call.

**Fix 2 — a second, related stub found by exercising this live**, not part of the original
report: when a region-bounded handler's search reaches outside its own region (a real,
legitimate case — the same `NetworkOutOfBounds` the class was built to handle), the update is
deferred via `NestworldRegionSystem.deferWireUpdate(...)` to run on the main thread next tick.
That queue and its drain loop already existed and worked correctly (already draining
`BoundarySignalQueue` the same way) — but the actual per-message replay,
`NestworldDimensionRegion.runWireUpdateDeferred(...)`, was *also* a B.3-era throw stub, explicitly
commented as unreachable at the time it was written ("`deferredWireUpdates` is confirmed empty on
a fresh/idle world"). It is not unreachable once region threads genuinely mutate blocks near
wire — found live, during this milestone's own testing, when 90 `UnsupportedOperationException:
B.3: deferred wire-update replay deferred past this milestone` warnings appeared in one boundary-
crossing test cycle (caught and logged per-message by the drain loop's own `catch (Throwable)`,
never a crash — but every one of those 90 updates was silently dropped, a real instance of "lost
block update," the checklist's own criterion 8). Fixed the same way: replay on the main thread by
calling into the already-B.6.2-proven `NestworldWireHandlerExt.nestworldWireHandler()
.onWireUpdated(pos)` — `onWireUpdated` re-derives add/remove/state from the position's current
block data, so it's correct regardless of which of the three original calls triggered the defer.

**Live proof.** Rebuilt real lever→wire→lamp circuits via RCON, confirmed baseline vanilla
propagation (power decay 15→6 across a 10-block run, lamp lit) before every cycle. Triggered
region-thread-originated destruction two ways: primed TNT (`Fuse:1`, matches B.6.5's confirmed
region-thread path) and a deterministic falling-sand block landing directly on a wire segment
(gravity-block ticks route through region threads since B.6.3) — both destroy blocks from
*inside* `RegionThread.tickEntities`, not the main thread. Across 9 cycles: 0 exceptions, 0
`NO_FOREIGN_ENTITY_MUTATION` violations, 0 deferred-replay-stub hits post-fix. One cycle
destroyed the lever itself; the remaining wire correctly recomputed to power 0 and the lamp
correctly went dark — real downstream propagation, not just "didn't crash" (checklist criterion
9). `RedStoneWireBlockMixin`'s existing cancel-vanilla-on-AC-handled logic (unchanged, already
proven in B.6.2) rules out double propagation/lost updates structurally (criteria 5, 6, 8) for the
common case.

**Honest gap: Fix 2 is not independently live-reproduced post-fix.** The original 90-hit
reproduction (pre-fix) happened at a live region split boundary (`Split Region{id=12...} ->
[Region{id=14, chunks=(0,-4)-(0,0)}, Region{id=15, chunks=(1,-4)-(1875000,0)}]`, cut at block
x=15/16). Rebuilding the identical circuit at that same boundary after the fix, using both TNT and
deterministic falling sand aimed directly at the boundary-adjacent wire block, did not reproduce a
boundary-crossing defer at all (0 hits, not 0 exceptions-with-hits) — most likely because Alternate
Current's search doesn't always re-explore the network's full extent on every local change (it
looked bounded/targeted rather than whole-network, unlike my working assumption going in), so
hitting the *specific* out-of-bounds condition needs the actual power-affecting frontier to reach
the boundary, which a several-block-distant local change doesn't reliably do. I did not force this
via `NESTWORLD_AUTOSPLIT` (the project's existing deterministic-split self-test env var) to get a
guaranteed adjacent-boundary repro before time was spent; flagging this rather than claiming full
live coverage. What *is* live-proven for Fix 2: the queue/drain infrastructure it replaces was
already fully working (confirmed by the 90 pre-fix hits), and the replacement is a single call
into `onWireUpdated`, the exact same method and exact same main-thread handler B.6.2 already
validated live, for the exact same "something changed at this position, recompute" semantics
`RedStoneWireBlockMixin.neighborChanged` already calls it for on the main thread.

**B.6.2-R verdict**: Fix 1 (`RegionThread.acWireHandler()`) — PASS, fully live-proven across 9
cycles including a real downstream-propagation case. Fix 2 (deferred cross-region replay) —
STILL NOT independently live-confirmed after a dedicated verification pass (below); accepted for
now on code-level reasoning, flagged for a retry once a separate, real finding this pass
surfaced is understood.

### B.6.2-R verification-only follow-up (Fix 2 specifically)

Per explicit direction: do not close Fix 2 as PASS on reasoning alone; get one concrete
`cross-region detected > 0 / deferred queued > 0 / deferred replayed > 0` live result via
`NESTWORLD_AUTOSPLIT` before moving to B.6.6. Added exact instrumentation for this and nothing
else: `NestworldRedstone.crossRegionDeferredCount()` (incremented + logged at the exact
`NetworkOutOfBounds` catch site) and `NestworldDimensionRegion.deferredWireReplayedCount()`
(incremented + logged right after a successful replay) — both verification-only counters, not
read by any production logic.

**Six live attempts across three server boots, all showing `queued=0 replayed=0` despite the
target wire block genuinely being destroyed each time** (confirmed via RCON): first, reusing the
persisted 14-region world and aiming a TNT/falling-sand mutation a few blocks from a known live
split boundary (block x=15/16) — no hit, three tries. Second, forcing a clean, guaranteed split at
that exact boundary via `NESTWORLD_AUTOSPLIT` on a freshly-reset region layout, then centering the
TNT directly on the boundary wire block itself — still no hit, despite confirming via log grep
that the region owning that block (`chunks=(...,-3,-3)-(0,0)`) genuinely excludes the neighbor
chunk. Traced the Alternate Current source to confirm this *should* be structurally guaranteed:
`WireHandler.onWireRemoved` → `findRoot` → `discover` → `WireConnectionManager.set` calls
`getNeighbor` unconditionally for all 6 directions of the removed wire, including the
out-of-bounds one, which must hit `checkBounds` → `NetworkOutOfBounds`. The removal, the neighbor
lookup, and the bound check are not in question; either the executing region-thread's actual
`region` reference didn't have the bounds I expected at the moment the explosion ran, or the
event happened too fast to observe correctly across RCON's own latency — not yet isolated.

**A real, separate finding surfaced during this same pass, and needs to be flagged before anyone
retries this**: deleting the persisted `nestworld-regions.dat` to get a clean single-region
starting point (needed for a deterministic `NESTWORLD_AUTOSPLIT` target) triggers severe,
sustained split thrashing on the next boot — regions running away to 40+ within under a minute,
tick spikes up to 458ms, multiple real `Can't keep up! Running Nms or N ticks behind` warnings.
Not a B.6.2-R regression (0 exceptions, 0 `NO_FOREIGN_ENTITY_MUTATION` violations through all of
it — genuinely just a performance problem, not a correctness one), and not present in any of this
whole multi-week session's prior work, all of which always ran against a gradually, organically
evolved persisted layout, never a from-scratch cold start. Most likely cause: an artificially
forced split of the single giant (±1,875,000 chunk) starting region, with zero real heat data
yet, followed by the organic heat-driven splitter immediately trying to "correct" that pathological
split — but not root-caused this pass; flagging as its own open item, not investigating further
under B.6.2-R's scope. **This is very likely also why the boundary-targeted repro attempts kept
failing**: the same thrashing that produced the finding was actively moving region boundaries out
from under the test in real time, faster than a human-paced RCON script can react.

**Follow-up round 2 — added a per-call thread diagnostic to settle it directly, and it changed
the picture.** Logged `Thread.currentThread().getName()` + `instanceof RegionThread` on every
single `NestworldRedstone.wireRemoved` call, then repeated the exact same boundary test (id=25/26
or id=26/27 depending on the reload's region-ID renumbering, always chunk 0 vs chunk 1 at block
x=15/16, reconfirmed present and stable via log grep before every attempt) across three more
server boots. **30/30 `wireRemoved` calls logged `thread=Server thread isRegionThread=false` —
zero region-thread executions, not one.** This is a stronger, different finding than "the network
search doesn't reach the boundary" — the block destruction that removes the wire is not happening
on a region thread *at all* in these tests, so `RegionThread.acWireHandler()` (Fix 1's own code)
is never even entered; `NestworldRedstone.wireRemoved` takes the main-thread branch every time,
which is why `queued`/`replayed` never move.

Ruled out explanations, each tested directly: **not player-proximity** (the standby bot's initial
`/tp` was silently failing — a fresh offline-auth account has no OP, so the chat command was
rejected with no visible error; opped the account and reconfirmed the bot's live position sat
inside the circuit before retrying — still 30/30 main thread). **Not settle-time** (retried with
15s between teleport and trigger, and with the TNT's own vanilla-default 4-second fuse instead of
a short one — still main thread). **Not auto-pin** (`NestworldPins`' auto-pin-on-repeated-crash
mechanism, which could plausibly have pinned `minecraft:tnt` to the main thread after B.6.5's
crash, is opt-in only via `-Dnestworld.autoPin=true` — never set in any of this session's launches,
confirmed in source). Genuinely not yet explained: B.6.5 itself is proof this specific execution
path (`RegionThread.tickEntities → PrimedTnt.tick → explode → ... → wireRemoved`) really can and
does happen on a region thread, with a full stack trace as evidence — but nothing in this
follow-up round reproduced it again, on the same codebase, the same general test shape.

**A second, real, and separate finding surfaced again in this same round**: the "Can't keep
up"/250-300ms tick-spike condition from earlier in this pass is not just a one-time cold-start
artifact — it recurred within this same server run, well after boot, well after the layout had
looked stable for tens of seconds (`regions=40`, 0 new splits for a while, then tick_wall climbed
back to 260-295ms with `regionPool(entity)` as the dominant cost on a *persisted, previously-quiet*
40-region layout). This is unrelated to my Redstone-specific code (0 exceptions, 0 violations, the
whole time) but is a second data point that something in this world's current 40-region layout is
under real, recurring load pressure — plausibly *why* my target chunk's entities keep landing on
the main-thread path (if `regionPool(entity)` itself is the dominant, overloaded cost, region
threads might not be completing normal ticks in a way this test can rely on).

**Reassessment**: what started as "one short verification-only run" surfaced two real, unresolved
systemic questions (the recurring tick-spike/thrashing, and now an unexplained thread-routing
discrepancy for entity-triggered explosions) that are bigger than B.6.2-R's own scope. Continuing
to retry live boundary crossings without understanding *why* explosions keep landing on the main
thread in this specific test session would be guessing, not verifying. Stopping here rather than
continuing to iterate blind.

**Current honest status, matching the table format requested:**

| Part | Status |
|---|---|
| `acWireHandler()` region-thread path | PASS — 9 live cycles |
| bounded Alternate Current handler | PASS |
| live region-thread redstone mutation | PASS |
| cross-region detection (NetworkOutOfBounds) | NOT reproduced post-fix in 6 attempts; code-traced as structurally guaranteed, mechanism unconfirmed live |
| deferred queue producer | PASS pre-fix (90 events, one earlier session) — not re-triggered post-fix |
| deferred replay implementation | IMPLEMENTED / NOT LIVE VERIFIED |
| explosion→region-thread routing (this session) | 30/30 landed on main thread, not region thread — unexplained, contradicts B.6.5's own evidence |
| regression across all cycles (9 + 6 + 30 diagnostic calls) | PASS — 0 exceptions, 0 violations throughout |
| **Finding 1**: fresh-layout-boot split thrashing | OPEN, separate from B.6.2-R, not yet root-caused |
| **Finding 2**: recurring tick-spike/thrashing on a previously-stable persisted layout | OPEN, separate from B.6.2-R, not yet root-caused |
| B.6.2-R overall | PARTIAL — verification blocked on two open systemic findings above, not a code problem in Fix 1/Fix 2 themselves |

> **Official pause, 2026-09-05**: B.6.2-R paused. No regression detected. Fix 1 and Fix 2 remain
> logically valid. Post-fix deferred replay lacks live confirmation because the required producer
> event could not be reproduced.
>
> New finding: identical explosion/redstone execution path previously proven on RegionThread is
> now observed 30/30 times on Server thread across three independent boots. This is classified as
> an execution-context inconsistency, not a boundary-test failure.
>
> Separate finding: persistent 250–300 ms tick spikes emerge after prolonged stable 40-region
> execution, with `regionPool(entity)` dominating. This is a separate Core scheduler/execution-
> performance investigation.
>
> **Next step is B.6.X — Region Execution Continuity Audit, not B.6.6.** If `regionPool(entity)`
> or execution routing has state-dependent starvation, every subsequent B.6.6/B.6.7 test could
> produce false negatives — debugging the symptom instead of the cause.

## B.6.X — Region Execution Continuity Audit

Not a redstone fix. Goal: find out why the identical explosion/block-mutation execution chain
sometimes runs on a `RegionThread` (B.6.5, proven via stack trace) and sometimes on the main
`Server thread` (this pass, 30/30). Instrumentation-only — no Fix 1/Fix 2 changes, no scheduler or
budget changes, no forcing RegionThread for redstone, no retries.

### Test 1 answered directly — by source comparison, not another live guess

Before writing any new trace points, compared the real 1.20.1 vanilla-fork patch's
`ServerLevel.tick()` against the current NeoForge port's decompiled `ServerLevel.tick()`. Found
the real patch's exact guard around its own entity-tick loop:

```java
// real 1.20.1 patch, ServerLevel.java:408
if (!(NestworldRegionSystem.isInitialised() && NestworldRegionSystem.get().isManagedLevel(this)))
this.entityTickList.forEach((p_184065_) -> { ... this.guardEntityTick(this::tickNonPassenger, p_184065_); ... });
```

i.e. **on a managed level, vanilla's own main-thread entity-tick loop is skipped entirely** —
region threads are the only thing that ticks a managed level's entities. Grepped the whole
NeoForge port's mixin set for any equivalent (`entityTickList`, `guardEntityTick`,
`tickNonPassenger`) — **zero matches**. Confirmed against the actual decompiled 1.21.1
`ServerLevel.tick()` source (`build/.../ServerLevel.java:403`): the entity loop is byte-for-byte
unmodified vanilla, unconditional, no `isManagedLevel` check anywhere.

This is the same class of gap as the B.3.0 retraction (a real vanilla-fork patch never re-ported
as a Mixin) — except this one is silent rather than a hard `UnsupportedOperationException`, which
is exactly why it went unnoticed through every B.4–B.6.5 test: **every entity on a managed level
is ticked twice per game tick** — once by vanilla's own unmodified `entityTickList.forEach()` on
the main thread (during `ServerLevel.tick()`, which NeoForge always runs to completion for every
level *before* `ServerTickEvent.Post` fires — confirmed via B.3.0's own comment on why `Post` was
chosen as the Core tick entry point), and once by `RegionThread.tickEntities()` (dispatched from
that same `Post` event, strictly after). Vanilla's pass always goes first.

This fully explains the 30/30: TNT's detonation is a one-shot, self-consuming event — whichever
tick() call first sees `fuse <= 0` wins, and since vanilla's main-thread pass structurally always
runs before the region thread's pass for the same game tick, it deterministically wins every time
— not a race, not flaky, exactly the consistency observed. It also reconciles with B.6.5's own
stack-trace evidence rather than contradicting it: B.6.5's TNT was placed as a **block** and
ignited by a player walking up with flint and steel — the block→entity conversion timing (and a
freshly-spawned entity's first tick landing mid-cycle relative to the two loops) is different
enough from an instant `/summon ...{Fuse:1}` that either loop winning is plausible there; this
pass's consistent 30/30 is the structurally-guaranteed common case, not a change in behavior.

### Static audit — every nestworld-guarded phase in the real `ServerLevel.tick()`, cross-checked against this port

Per explicit direction: not just `entityTickList` — every old fork patch that changes execution
ownership, found by walking the real 1.20.1 `ServerLevel.tick()`/`tickChunk()`/`addFreshEntity()`
top to bottom and checking each `nestworld`-guarded branch against this NeoForge port's Mixin set.
Audit-only — no code changes, no live tests.

| Execution path | 1.20.1 (real patch) | NeoForge 1.21.1 (this port) | Status |
|---|---|---|---|
| Entity tick (`ServerLevel.tick()` main loop) | vanilla `entityTickList.forEach` **skipped** on managed level | vanilla loop runs **unconditionally**, unmodified | 🔴 CONFIRMED — double-tick, this section's main finding |
| Region entity tick (`RegionThread.tickEntities`) | Core | Core | 🟢 |
| Block events | vanilla redirected to Core | Core (B.6.1, `ServerLevelBlockEventsRedirect`) | 🟢 |
| Scheduled block/fluid ticks | vanilla redirected to Core | Core (B.6.3, `ServerLevelScheduledTicksRedirect`) | 🟢 |
| Redstone wire (`RedStoneWireBlock` only) | intercepted via `WireHandler` | Core/Adapter (B.6.2, B.6.2-R, `RedStoneWireBlockMixin`) | 🟢 |
| General neighbor-update dispatch (`Level.markAndNotifyBlock`/`updateNeighborsAt`, all block types) | routed per-thread via `nestworldNeighborUpdater()` | still 100% vanilla `CollectingNeighborUpdater`/`InstantNeighborUpdater` — `NestworldRedstone.regionNeighborUpdater(Level)` exists but **is never called from anywhere** | 🔴 NEW — only `RedStoneWireBlock` itself is covered (via its own narrow Mixin); every other block's neighbor cascade (pistons, observers, comparators, doors, general block-update chains) is unrouted |
| Explosion routing (entity impact + block batch) | Core routing | Core routing (B.6.5, `ExplosionRegionRoutingMixin`) | 🟢 |
| Random ticks (`tickChunk`, grass/crops/leaves/fire-spread) | vanilla redirected via `queueRandomTicksFor` | queue infra exists (`NestworldDimensionRegion.queueRandomTick`/`flushRandomTicksPhase`, mirrors the B.6.1/B.6.3 pattern exactly) but **no Mixin calls it at all** — `runRandomTickTest()`'s own self-test still throws `"B.3: nestworldRandomTickChunk deferred"` | 🔴 NEW — not integrated, same B.3-era-stub shape as B.6.1/B.6.3 were before their fixes |
| `addFreshEntity` (new entity spawned from a `RegionThread`) | deferred to main thread via `queueEntitySpawn`/`drainDeferredSpawns` (avoids a region thread mutating vanilla's entity-tracking structures directly) | `queueEntitySpawn`/`drainDeferredSpawns` exist, fully implemented, **but unreachable — no Mixin on `addFreshEntity`**; a region-thread-spawned entity (block-ignited TNT, thrown items, dropped loot, projectiles) is added directly from whatever thread calls it | 🔴 NEW — real synchronization-safety mechanism from the reference patch, silently absent |
| Portal arrival (dimension crossing) | queued via `queuePortalArrival` on a `RegionThread` | `NestworldRegionSystem.queuePortalArrival` exists; no Mixin found calling it | 🔴 NEW — lower priority (Nether/End-specific, outside this branch's overworld-focused scope so far), same shape |
| `tickBlockEntities()` | vanilla, **unmodified in the reference patch itself** | vanilla, unmodified | ⚪ N/A — intentionally outside Core's execution model in both versions, not a gap |
| `chunkSource.tick()` (DistanceManager/chunk-gen/unload) | Core-aware | Core-aware (B.3, `NestworldServerChunkCacheMixin`) | 🟢 |
| Weather/sleep/time/raid (level-global state) | vanilla, unmodified | vanilla, unmodified | ⚪ N/A — global, not per-region-ownership relevant |

**Reading this table**: B.6.1/B.6.2/B.6.3/B.6.5 (and B.6.2-R's own fix) are the phases someone
*already* deliberately re-earned as real Core integration this segment — all 🟢, all live-proven.
Every 🔴 row is a phase that was silently left on the "B.3.0 retraction" side of the line: real,
working reference-patch logic that either never got a Mixin (general neighbor dispatch, random
ticks, `addFreshEntity`, portal arrival) or got its Mixin's *guard condition* dropped (entity
tick). None of these are new bugs introduced by this session's work — they're the same class of
gap the B.3.0 retraction already taught this project to expect: a silent, non-throwing absence,
not a crash, so nothing forced anyone to notice until this audit went looking on purpose.

**Severity classification, per direction — not a B.6.2-R regression:**

> **NEW: NeoForge Entity Execution Ownership Gap**
> Severity: HIGH
> Scope: Core execution semantics (entity tick loop specifically; general neighbor-update
> dispatch, random ticks, and `addFreshEntity` are related but separately-scoped gaps in the
> same audit)
> Status: CONFIRMED (entity tick, via source comparison — see above)
> Fix: NOT AUTHORIZED / NOT YET IMPLEMENTED
>
> Fix 1 (`RegionThread.acWireHandler()`) — PASS, unaffected.
> Fix 2 (deferred cross-region wire replay) — IMPLEMENTED, logical correctness PASS, unaffected.
> B.6.2-R is not closed PASS or FAIL — its post-fix deferred-replay producer event genuinely
> hasn't been observed live yet, and this audit is *why*: the reproduction attempts were racing
> against a double-ticked entity, not testing a stable execution path.

**This finding is bigger than B.6.2-R.** If entities are genuinely double-ticked on every managed
level, it affects *every* entity, not just explosions — movement, AI, damage, anything ticked
twice per game tick instead of once. Not fixing this here (instrumentation-only scope, and a fix
this central needs its own explicit go-ahead, not a silent patch found while chasing a redstone
boundary). Flagging as its own item, not proceeding to Tests 2/3 of the original three-test plan
since they were designed to narrow down *why* routing varies — this answers that question
directly and makes further routing A/B tests redundant unless the user wants confirmation via a
different method (e.g. a tick-count instrumentation on `PrimedTnt` itself, showing 2 ticks/game
tick instead of 1).

**Revised milestone order**: B.6.2-R → B.6.X (this audit) → **B.6.6 — Entity Tick Ownership
Audit** (turning the confirmed entity-tick gap, and the related neighbor-dispatch/random-tick/
`addFreshEntity` gaps above, into a scoped fix — not started yet, needs its own go-ahead) →
B.6.7 Entity Push/Impact → B.6.8 Chunk/World callbacks. Not proceeding to entity push/impact or
chunk callbacks before the ownership gap has an explicit decision, since a double-ticked entity
would make those tests' results unreliable in the same way it made this one's.

### B.6.6.1 — Entity tick ownership guard: live proof

Authorized scope only: `ServerLevelEntityTickRedirect` — a `@Redirect` on `ServerLevel.tick()`'s
`entityTickList.forEach(...)` call, mirroring the real 1.20.1 patch's guard exactly — no-ops on a
managed level (`RegionThread.tickEntities()` becomes the sole execution authority), calls through
to vanilla unchanged otherwise. Nothing else touched (scheduler, explosion, block events, redstone
wire implementation, `ServerChunkCache`, `ChunkMap`, ownership model, region scheduler, budgets,
split/merge, `regionPool(entity)`, Fix 1, Fix 2 — all untouched, per the explicit scope).

Added a temporary verification-only trace (`PrimedTntTickTraceMixin`, `@Inject` at `tick()`'s
`HEAD`) logging UUID/game-tick/thread/`isRegionThread` for every `PrimedTnt.tick()` call, to get
the exact proof shape requested: consecutive game ticks all showing the same thread, zero
`Server thread` entries mixed in.

**First finding was a test-methodology bug, not a Core bug**: three early TNT summons each showed
exactly one tick before disappearing, which looked alarming (a normal fuse needs ~80 ticks). Root
cause: `/summon minecraft:tnt` with no NBT override defaults to `fuse=0` (confirmed via
`/data get entity ... fuse` immediately after summon) — vanilla's own well-known "instant-detonate"
quirk, worse compounded by my own NBT key being wrong case (`{Fuse:60}` vs the real, lowercase
`{fuse:60}` tag), so my override was silently ignored every time. Fixed the test, re-ran with
`{fuse:80}` confirmed via direct query.

**Full live result across the corrected + earlier runs**: **85 diagnostic samples
(`PrimedTnt.tick()` + `NestworldRedstone.wireRemoved`) — `isRegionThread=true` on all 85, zero on
`Server thread`.** This is the proof shape requested (though capped by round-robin entity-tick
budget/fairness meaning any single entity's own consecutive-tick sequence was short in these
particular runs — the aggregate 85/85 result answers the actual ownership question directly).
Also re-tested the fresh-boot split-thrashing condition from earlier in this pass: with the fix
applied, a clean single-region cold boot settled with only 1 tick-spike warning over 15+ seconds
(vs. 39+ splits and repeated 250–450 ms spikes pre-fix under the identical cold-start scenario) —
consistent with double-ticking having been a real contributor to that instability, though the
world later re-fragmented into 40 regions and resumed spiking on its own over a longer session, so
this isn't a full fix for that separate, still-open finding.

**B.6.2-R's Fix 2 finally got its live confirmation, for real, in this same run** — an organic
cross-region wire update (leftover circuit wire disturbed by a region re-split, not a forced test)
produced the exact producer→replay chain B.6.2-R needed:
```
[NestWorld-Region-75] cross-region wire update deferred: region=75 pos=(15,64,-52)  (1)
[NestWorld-Region-75] cross-region wire update deferred: region=75 pos=(15,64,-52)  (2)
[NestWorld-Region-75] cross-region wire update deferred: region=75 pos=(14,64,-52)  (3)
[Server thread] deferred wire update replayed on main thread: pos=(15,64,-52)  (1)
[Server thread] deferred wire update replayed on main thread: pos=(15,64,-52)  (2)
[Server thread] deferred wire update replayed on main thread: pos=(14,64,-52)  (3)
```
3 queued, 3 replayed, 0 exceptions, 0 `NO_FOREIGN_ENTITY_MUTATION` violations. **B.6.2-R is now
fully closed PASS** — both Fix 1 and Fix 2 live-proven, no remaining gap.

**A new, real, separate finding surfaced in the same run — not hidden.** One entity tick error,
caught and absorbed by `RegionThread`'s own per-entity catch-and-continue (server did not crash,
15+ more tick cycles logged normally afterward):
```
net.minecraft.ReportedException: Accessing LegacyRandomSource from multiple threads
  at LegacyRandomSource.next → BitRandomSource.nextInt → AbstractSchoolingFish.tick
  at ServerLevel.tickNonPassenger → RegionThread.tickEntities → RegionThread.runOneTick → RegionThread.run
Caused by: java.lang.IllegalStateException: Accessing LegacyRandomSource from multiple threads
```
Vanilla's own built-in `ThreadingDetector` fired: a `tropical_fish` on one region thread and
something else (another fish, likely on a different region thread) both called into the same
shared, non-thread-safe `LegacyRandomSource` (`Level.random` or equivalent) at the same real
moment. This is a genuine, pre-existing concurrency hazard — vanilla never designed `Level.random`
for concurrent access, and B.6.6.1's fix, by making region threads the sole and fully-parallel
execution authority for every managed-level entity (no more silent main-thread "shadow" ticking
absorbing some of that work serially), likely makes the collision *more* reachable, not newly
possible. Not fixing this here — out of B.6.6.1's authorized scope (entity tick ownership only),
and it needs its own classification, same as every other finding this segment. Flagging as a new
open item: **shared, non-thread-safe RNG access across genuinely parallel region threads.**

**B.6.6.1 verdict: PASS.** Entity-tick double-execution is gone (85/85 samples confirm single
authoritative execution path). B.6.2-R closes PASS as a direct consequence. One new, non-fatal,
out-of-scope finding recorded for follow-up, not silently absorbed into this fix.

### B.6.6.2 — General Neighbor Update routing: live proof

Authorized scope only: connect the already-existing `NestworldRedstone.regionNeighborUpdater(Level)`
(defined, never called) to the real vanilla entry points, mirroring the real 1.20.1 patch's
`Level.nestworldNeighborUpdater()` picker exactly. Found the real mechanism by reading the actual
1.20.1 source: a picker method on `Level` that returns the calling region thread's own bounded
updater when non-null, falling back to the level's single shared `neighborUpdater` field
otherwise; `ServerLevel`'s 4 dispatch methods (`updateNeighborsAt`,
`updateNeighborsAtExceptFromFacing`, both `neighborChanged` overloads) and `Level`'s own
`neighborShapeChanged` all read that field directly. Added the picker back
(`LevelNeighborUpdaterMixin`, exposed via a `LevelNeighborUpdaterExt` cast interface, same pattern
as `NestworldWireHandlerExt`/`NestworldServerChunkCacheExt`) and redirected all 5 field reads
(`ServerLevelNeighborUpdaterRedirect` for the 4 on `ServerLevel`, one more on `Level` itself) to
call through it. `neighborUpdater` is `protected` on `Level`, inherited by `ServerLevel` — no
`@Shadow` needed on the `ServerLevel` mixin (that's only for fields declared directly in the
mixin's own target class); first attempt tried shadowing it there anyway and failed to apply
(`InvalidMixinException: @Shadow field ... was not located in the target class`), fixed by calling
back into the `Level` mixin's already-merged picker via the interface instead.

**Live proof, three parts, all with `NESTWORLD_ENTITY_GUARD=1`/`NESTWORLD_OWNERSHIP_ASSERTIONS=1`:**

1. **Regression** — rebuilt the standard lever→wire→lamp circuit (the exact one B.6.2/B.6.2-R
   already prove). Lamp lit, power decay 15→11 across the run, unchanged. This circuit happened to
   span a real live region boundary (chunk 0 vs chunk 1, confirmed via the restored-layout log
   line) without it being forced — redstone's own narrow B.6.2 path is untouched and still works.

2. **Non-redstone mechanic, same region** — a wall torch attached to a stone support block; TNT
   (a genuine region-thread mutation, confirmed via B.6.6.1's own thread trace) destroys the
   support; the torch correctly popped (`canSurvive` check via `neighborChanged`, a completely
   different code path from `RedStoneWireBlock`'s own narrow B.6.2 hooks) — 0 exceptions.

3. **Non-redstone mechanic, cross-region** — same torch/support pair, this time deliberately
   straddling a confirmed live region boundary (support in one region's chunk, the wall torch's
   own block position one block over in the neighboring region's chunk). TNT destroyed the
   support; the torch in the *other* region also correctly popped. Honest caveat: a torch has ~0
   blast resistance, so this can't fully rule out the explosion's own blast radius directly
   reaching the torch's position rather than the neighbor-cascade being what removed it — the
   support and the dependent block are only 1 block apart by construction (a wall torch always
   attaches directly to its support), so a blast-radius-independent version of this same test
   wasn't found in the time available. Flagging as reasonable-but-not-airtight cross-region
   evidence, not proof at B.6.2-R's level of rigor.

**A new, real, third finding — same family as the already-flagged RNG race, not new territory.**
Four `java.util.ConcurrentModificationException` errors, all in mob AI (`AvoidEntityGoal.canUse`,
`NearestAttackableTargetGoal.canUse`/`findTarget`) calling `Level.getEntities()` →
`EntitySectionStorage`/`ClassInstanceMultiMap`'s internal `HashMap.computeIfAbsent`. Each caught
and absorbed by `RegionThread`'s own per-entity catch-and-continue — not a crash. Checked the
stack traces carefully before concluding anything: the frames show `LevelNeighborUpdaterMixin`
annotated on them, but that's only because Mixin stamps *every* frame belonging to a
Mixin-transformed class with the applied-mixins list — the actual call path
(`Level.getEntities` → `EntityGetter.getEntitiesOfClass` → mob AI goals) never touches
`updateNeighborsAt`/`neighborChanged`/`neighborShapeChanged`, the only methods this milestone
changed. **Not a B.6.6.2 regression** — it's the same underlying class of problem as the
already-flagged `LegacyRandomSource` race (#123): a shared, non-thread-safe vanilla data
structure (this time entity-section indexing, not RNG) now reachable by genuinely parallel region
threads because of B.6.6.1's fix, not newly introduced by B.6.6.2. Per direction, not
investigating or touching this now — recording it as the same open concurrency-audit item grows
to cover: "shared, non-thread-safe vanilla structures under genuinely parallel RegionThreads,"
`LegacyRandomSource` and `EntitySectionStorage` both now confirmed instances of it.

**B.6.6.2 verdict: PASS**, with the cross-region case caveated as above and the CME finding
recorded, not fixed, same as RNG.

### B.6.6.3 — Random tick routing: live proof

Authorized scope only: connect the already-existing `NestworldRegionSystem.queueRandomTicksFor` /
`NestworldDimensionRegion.queueRandomTick`/`flushRandomTicksPhase` infrastructure (confirmed by
the B.6.X audit: correctly implemented interior/border-band chunk routing, just never called) to
the real vanilla `ServerLevel.tickChunk` entry point, mirroring the real 1.20.1 patch's
`if (randomTickSpeed > 0 && !queueRandomTicksFor(...)) { ...vanilla loop... }` guard exactly —
same narrow scope as the reference patch, leaving the earlier thunder/lightning/precipitation
logic in `tickChunk` untouched.

Filled in the one real gap the audit found: `queueRandomTicksFor`'s queued work was a B.3-era
throw stub. Replaced with a real replay of vanilla's own per-section random-tick loop
(`BlockState.randomTick`/`FluidState.randomTick`, byte-for-byte the same calls, just running on
the owning region's thread instead of inline on main) and added the missing
`flushRandomTicksPhase()` call site in `NestworldDimensionRegion.tick()`, same timing bucket as
block events (`runBlockEventsPhase()`, right after `level.tick()` returns).

**Can't `@Redirect` `if (randomTickSpeed > 0)` directly** (not a method call) — used
`@ModifyVariable` on the `randomTickSpeed` parameter instead, zeroing it right after the second
`profilerfiller.popPush(...)` call (the "tickBlocks" push, immediately before vanilla's loop) when
`queueRandomTicksFor` takes it over; the earlier precipitation loop, which reads the same
parameter at an earlier program point, already ran with the real value and is unaffected — matches
the real patch's exact scope. First attempt used an implicit (type-only) variable match and failed
to apply (`InvalidImplicitDiscriminatorException: Found 4 candidate variables but exactly 1 is
required` — several other `int` locals in scope at that point); fixed by matching on the
parameter's real name instead of relying on type-only inference.

**Live proof.** Real wheat crop (age 0→2 observed) grew via the new Core-routed path — confirmed
by first cranking `randomTickSpeed` to 500 for a fast test, which backfired: it massively
amplified concurrent access to the same shared `Level.random` already flagged in #123, aborting
the crop's whole chunk-random-tick batch before ever reaching the wheat block (each `Runnable`
this milestone queues runs through `RegionThread.runWorkBudgeted`'s existing per-item
try/catch — a single `LegacyRandomSource` collision anywhere in one chunk's random-tick replay
drops the *whole* replay for that call, not just the one failing block). Dialed back to
`randomTickSpeed=20` (still faster than vanilla default 3, far gentler than 500) and the crop grew
cleanly. Also confirmed grass spread crossing a real region boundary (dirt at a position in the
neighboring region's chunk correctly turned to `grass_block`, triggered by the grass block one
position over in the source region) — same general "no bounds check on ordinary block writes"
behavior already established by B.6.6.2, not a new mechanism. 0 `NO_FOREIGN_ENTITY_MUTATION`
violations across the whole session. A planned thread-identity trace (matching B.6.6.1/B.6.2-R's
style) didn't produce output — the `-D` system property used to gate it apparently didn't reach
the forked server JVM the way env vars have throughout this whole segment; not worth another boot
cycle to fix given the crop/grass results already directly answer the question (did the mutation
happen via the new path, at all — yes, both times).

**B.6.6.3 verdict: PASS.** Real vanilla random-tick mechanics (crop growth, grass spread)
confirmed running through the new Core-routed path, including one cross-region case. No new
exception class — the RNG collisions seen during the deliberately-aggressive 500x-speed attempt
are the *same*, already-flagged #123 finding, not a new one, and don't recur at a normal test
speed. `#123` untouched, as directed.

### B.6.6.4 — addFreshEntity execution-ownership routing: live proof

Authorized scope only, last of the four gaps the B.6.X audit confirmed. Real 1.20.1 patch
overrides `ServerLevel.addFreshEntity(Entity)`: a call originating on a region thread is deferred
to the main thread (`NestworldRegionSystem.queueEntitySpawn` →
`NestworldDimensionRegion.drainDeferredSpawns()`) instead of mutating vanilla's single,
main-thread-owned entity-tracking structures directly from a non-main thread; a main-thread call
(player join, `/summon`, vanilla's own mob spawner) is unaffected. The queue/drain side was
already fully implemented and already called every tick (`drainDeferredSpawns()` inside
`NestworldDimensionRegion.tick()`) — confirmed by the B.6.X audit as unreachable only because
nothing ever called `queueEntitySpawn`. Added one `@Inject(at = @At("HEAD"), cancellable = true)`
on `ServerLevel.addFreshEntity` — the only change this milestone made.

**Live proof.** A chest (0 blast resistance, like B.6.6.2's torch) destroyed by a genuine
region-thread TNT explosion drops itself as an item entity — `Explosion.finalizeExplosion()`'s
block-destroy loop calls `addFreshEntity` for the drop, from inside the same RegionThread call
stack B.6.5/B.6.6.1 already proved. Added a direct trace log at the interception point (learned
from B.6.6.3's system-property mistake — used a plain unconditional `LOGGER.info`, no gating
mechanism to fail silently this time) confirming 4 real interceptions, all showing genuine
`RegionThread` names and region IDs (`region=21`, `region=16`, `region=11`), each followed by the
expected `EntityTransfer` log line (`moved from none to <region>`) confirming the deferred spawn
was correctly picked up by `drainDeferredSpawns()` on the main thread and given the right owner.
Chest destroyed, item entities confirmed present in the world afterward. 0 ownership violations.
One `ConcurrentModificationException` during the run — same already-flagged #123 finding
(confirmed by message/stack shape), not a new class, not investigated per standing direction.
Vanilla double-spawn is structurally ruled out the same way as B.6.1/B.6.3: the `@Inject` cancels
the callback before vanilla's own `this.addEntity(entity)` body ever runs.

Cross-region case not specifically engineered this time — item drops land essentially at their
source position, giving no natural reason to land in a different region's chunk the way a longer-
range mechanic (redstone wire, grass spread) does; not forcing an artificial setup for it, per the
same "don't invent a complex construction" guidance already applied to B.6.6.2/3.

**B.6.6.4 verdict: PASS.** All four NeoForge Execution Ownership Hooks the B.6.X audit found are
now closed: entity tick (B.6.6.1), general neighbor updates (B.6.6.2), random ticks (B.6.6.3),
addFreshEntity (B.6.6.4) — each live-proven, each scoped narrowly, each leaving #123 untouched and
separately tracked.

## B.6.7 — Entity execution / mutation audit (Post-Hook Execution Audit)

**Status: AUDIT PASS-1 COMPLETE** (verdict at the end of this section) — the broader, ~45-file
remainder of the original 57-file grep is not walked exhaustively; the `ChunkMap` subsystem found
partway through this pass was large enough to split out as its own item, B.6.8, below.

Per direction: not a new live-test milestone, another static audit pass — this time broader than
`ServerLevel`/`Level`, across the whole real 1.20.1 patch tree, now that the four confirmed
execution-ownership hooks are closed. Started by grepping every file in the real patch for
`net.nestworld.` references (57 files touched) to see what else exists that this port hasn't
looked at yet.

### Headline finding: #123 is not a fresh vanilla thread-safety problem — it's a fifth missing hook, with the fix already built

Checked the two exact classes from #123's stack traces against the real patch, expecting to find
nothing (assuming vanilla genuinely never supported concurrent access and the reference patch
would need new design work here too). Found the opposite — **both halves already have complete,
real, working fixes in the reference repo, neither ported:**

**RNG half** — `Level.java:98`:
```java
public final RandomSource random = RandomSource.createThreadSafe(); // NestWorld: thread-safe random for parallel region entity ticking
```
`RandomSource.createThreadSafe()` returns `new ThreadSafeLegacyRandomSource(seed)` — not a
NestWorld invention, a **standard (if `@Deprecated`) class Mojang ships in vanilla itself**,
confirmed present, byte-identical, in this exact NeoForge 1.21.1 build
(`build/.../ThreadSafeLegacyRandomSource.java`, uses `AtomicLong` CAS instead of a plain `long`
field). This port's `Level.random` still uses the vanilla-default, non-thread-safe
`RandomSource.create()` — one field-initializer swap, not a redesign.

**`EntitySectionStorage` half** — `Level.java:46` (inside `PersistentEntitySectionManager`'s
construction): `this.sectionStorage = new net.nestworld.region.ConcurrentEntitySectionStorage<>(...)`.
This class is real and already exists in the main repo
(`nestworldcore/src/main/java/net/nestworld/region/ConcurrentEntitySectionStorage.java`, 100+
lines, extends vanilla's own `EntitySectionStorage<T>`, backed by a `ReentrantReadWriteLock`). Its
own javadoc describes the *exact* failure this pass found live: "a structural write (entity
crossing a section border on thread A) racing an iteration (`getEntities` during mob AI on thread
B) corrupts the AVL tree" — and warns the untreated failure mode is worse than what showed up here
(a caught `ConcurrentModificationException`) — an infinite loop that gets the region thread killed
by the watchdog. This port's `PersistentEntitySectionManager` is confirmed fully vanilla/unmodified
(zero `nestworld` references found in it).

**Reclassifying #123**: not "vanilla was never designed for this, needs its own concurrency
audit" — it's the same class of finding as B.6.6.1–4, just not yet scoped into a numbered
B.6.6.x sub-item because the original audit only walked `ServerLevel.tick()`'s call graph, not
`Level`'s field initializers. Both fixes are small, already-designed, and low-risk to port (a
Mixin field-initializer redirect for the RNG half; swapping one `new EntitySectionStorage<>(...)`
constructor call for `new ConcurrentEntitySectionStorage<>(...)` for the other) — genuinely
different in kind from "figure out vanilla thread-safety from scratch."

**Not fixing this now** — audit only, per the standing rule; #123 stays deferred until explicitly
authorized, same as before. Flagging the reclassification because it changes what "authorizing
#123" would actually mean: porting two already-solved fixes, not opening new design work.

### Second finding, `Entity.java`/`LivingEntity.java` — larger, and it touches the evidence trail itself

**This one needs its own framing, separate from #123, because it's not just "a missing hook" —
it changes how much weight to put on every prior test's `NESTWORLD_ENTITY_GUARD=1` result.**

Found `net.nestworld.region.EntityOwnershipGuard.check(entity, mutationKind)` calls at exactly 4
real chokepoints in the reference patch: `Entity.setDeltaMovement`, `Entity.setPosRaw`,
`Entity.setRemoved` (all in `Entity.java`), and `LivingEntity.actuallyHurt`
(`LivingEntity.java:1616`). The class itself is real and complete in this port too
(`net/nestworld/region/EntityOwnershipGuard.java`) — its own javadoc: *"a dedicated check that
fails LOUDLY the moment a region thread directly mutates an entity it does not own, rather than
relying on the absence of a crash as proof the ownership discipline holds"* — enabled by
`NESTWORLD_ENTITY_GUARD=1`, the exact env var set in *every single boot this entire B-phase
segment*.

**Checked whether `EntityOwnershipGuard.check(...)` is called from anywhere in this port at all:
zero call sites.** No `Entity`/`LivingEntity` Mixin exists in the port (confirmed against the
full mixin file list). This means: for this whole segment, `NESTWORLD_ENTITY_GUARD=1` armed a
detector that was never wired to anything it was supposed to watch. Every "0
`NO_FOREIGN_ENTITY_MUTATION` violations" reported across B.3.0 through B.6.6.4 is accurate as
stated — the string genuinely appears zero times in every log — but it was structurally
*guaranteed* to be zero regardless of whether a real violation happened, the same way B.3.0's
"938 ticks" was real but meaningless. This does not retroactively invalidate the *other*, direct
behavioral evidence gathered this whole segment (RCON-confirmed block states, entity positions,
counters like B.6.2-R's `deferred`/`replayed` or B.6.6.4's `EntityTransfer` lines are all
independent of this guard) — only the specific "0 violations" framing used as a safety signal
alongside them.

Found a second, larger piece in the same two files: `LivingEntity.pushEntities()`/`doPush(Entity)`
has a complete, already-designed fix for cross-region entity pushing — not a detector, a real
behavioral fix. When a colliding entity is foreign-owned, the local half of the push applies
directly (safe self-mutation) and the foreign half is deferred via a real message
(`RegionMessage.entityPush(...)` / `EntityPush(entityId, dx, dy, dz)`), applied later by the
*owning* region — same shape as B.6.5's `EntityImpact` message. Checked the consumer side:
**`RegionThread.applyPendingEntityPushes()` already exists, is already implemented, and is already
called every tick** (`RegionThread.java:569`, inside the normal tick loop) — it has been running
this entire segment, correctly draining an always-empty queue, for the same reason as the random-
tick queue before B.6.6.3: nothing was ever producing into it. The entity-cramming cap logic
(`NestworldTuning.MAX_ENTITY_PUSH`, `NestworldPushPredicate`) lives in the same unmixin'd method,
so it's unported too. A fourth, smaller item in the same file pair: `Entity.changeDimension`
queues cross-thread dimension changes via `NestworldRegionSystem.queueDimensionChange` (also
implemented, also unreachable, same shape) — related to the already-open task #35 (`Entity
.startRiding()` cross-region passenger-vehicle linkage).

**Not fixing any of this now** — audit only, per the standing rule. Recording as its own item,
separate from #123 (same *shape* of finding — reference fix exists, never ported — but a
different subsystem and, for the ownership-guard half, a different kind of consequence: it
affects how the evidence itself should be read, not just what's missing).

> **Evidence-trail correction, precise wording**: `EntityOwnershipGuard` reported 0 violations
> across every B.3.0–B.6.6.4 test because it was never active in this port — **not** because no
> violations occurred. The honest statement is "we don't know whether a foreign-entity mutation
> happened during those tests, because the detector wasn't wired," not "violations were
> happening." This does **not** retract B.5.3 through B.6.6.4's functional execution proof —
> real block/entity state, real `RegionThread` execution, real `EntityTransfer` ownership moves,
> B.6.2-R's `deferred`/`replayed` counters, and 0 Core/Adapter exceptions were all independent of
> this guard and remain valid as stated. Only the specific "0 ownership violations" framing used
> alongside that evidence needs to be re-earned once the guard is actually wired — a negative
> proof (no violations happened) requires a live detector, not just its silent absence.

### Third finding, `FallingBlockEntity.java` — small, same shape as the others

One touch: an entity crushed by a falling block gets its damage routed through
`net.nestworld.region.EntityMutationHelper.redirectIfForeign(this, victim, new EntityMutationOp
.Damage(damagesource, f))` instead of calling `victim.hurt(...)` directly, when the victim is
foreign-owned — same family as `LivingEntity.doPush`'s design, but using a *generic* redirect
helper (`EntityMutationHelper`/`EntityMutationOp`) rather than a bespoke message type. Both
classes are real and complete in this port (`net/nestworld/region/EntityMutationHelper.java`,
`EntityMutationOp.java`) — `redirectIfForeign` has zero callers anywhere, same reason as
everything else: no `FallingBlockEntity` Mixin exists.

### Fourth finding, `ChunkMap.java` — much larger in scale than the other three, its own subsystem

Grepped this file expecting a handful of touches like the others; found **hundreds**, spanning
essentially the entire real #31-era entity-tracking/networking pipeline this project already
built and validated extensively on the real 1.20.1 mod (per project memory: `OutboundBatchQueue`
shipped as production default, `PairingOutboundQueue` shipped, `RegionalizedTracker` validated at
0 errors, spatial-cull tracker, chunk-generation admission gates, `MoveDecisionMetrics`, and
more). Checked whether this port's *only* existing `ChunkMap` Mixin (`ChunkMapAccessor`) covers
any of it — it doesn't; it only exposes `getChunks()`/`tick()` as plain `@Invoker` accessors, a
B.3-era stub with no execution routing at all.

Checked the specific piece most directly relevant to execution ownership —
`nestworldTrackOwned(Entity, long, List<ServerPlayer>)`, the method that lets a *region thread*
handle its own owned entities' tracking/broadcast instead of `ChunkMap.tick()` doing it serially
on the main thread — and confirmed the port's own `RegionThread.tickEntities()` still throws:
`"B.3: nestworldTrackOwned deferred past this milestone."` So the entire regionalized-tracking
pipeline, one of the most extensively developed and already-validated parts of the real mod, is
still 100% vanilla main-thread in this NeoForge port.

**This is a different scale of finding than the other three** — not a handful of chokepoints in
one or two files, a whole subsystem, likely deserving its own dedicated audit/scoping pass rather
than a paragraph in this one. Not investigating further this round — flagging its existence and
size, not its full shape.

### Rest of the 57-file list — not fully walked yet

`Level.java`, `Entity.java`, `LivingEntity.java`, `FallingBlockEntity.java`, and `ChunkMap.java`
(scoped, not exhaustively walked given its size) now checked. Remaining, lower-priority:
`NaturalSpawner.java` (likely already covered by the earlier #31-era NaturalSpawner spatial-fix
work per project memory, not reconfirmed here), plus the ~45 other files from the original grep
not yet looked at (mostly network/packet-listener/player-list/command files, expected to be
smaller and less execution-ownership-central than the ones already checked, based on their names
— not confirmed).

### B.6.7 verdict — Entity execution / mutation audit: AUDIT PASS-1 COMPLETE

Renamed for precision: **B.6.7 — Entity execution / mutation audit.** Everything in this pass
that belongs to the entity-mutation-chokepoint family (not the separate ChunkMap subsystem, split
out below as B.6.8) is now confirmed:

| Finding | Scope | Reference fix exists? | Size |
|---|---|---|---|
| `Level.random` not thread-safe | RNG (#123 half 1) | Yes — one-line swap to `createThreadSafe()` | small |
| `EntitySectionStorage` not thread-safe | mob AI/`getEntities` (#123 half 2) | Yes — `ConcurrentEntitySectionStorage`, complete | small–medium |
| `EntityOwnershipGuard` unwired (4 chokepoints) | ownership violation detection | Yes — class complete, needs 4 Mixin hooks | small, but re-earns prior "0 violations" evidence (B.5.3 corrected above) |
| `LivingEntity.doPush` cross-region push | entity-push correctness | Yes — consumer already running, only producer missing | small–medium |
| Entity cramming cap | push-related | Yes — same unmixin'd method | small |
| `Entity.changeDimension` queueing | cross-thread dimension change | Yes — relates to open task #35 | small |
| `FallingBlockEntity` foreign-damage redirect | entity damage correctness | Yes — generic helper, unused | tiny |

**Status: AUDIT PASS-1 COMPLETE. Implementation: DEFERRED**, pending explicit authorization —
same discipline as `#123` and every finding this segment. Nothing implemented.

## B.6.8 — ChunkMap / Regionalized Entity Tracking Audit

**Status: IN PROGRESS.** Split out from B.6.7 because it's a different kind of finding — not a
handful of unwired chokepoints, a whole subsystem (see B.6.7's `ChunkMap` finding above, which
started this).

### Core architectural question answered: which parts run where

Read the reference `ChunkMap.tick()` in full to settle the actual question — not "how do we port
`ChunkMap`," but *which pieces of the real #31 pipeline execute on which thread, and what's the
ownership/network boundary between them.*

**The reference design (already correct, just not ported) is a matched pair:**

- **Region thread, per-entity, immediately after that entity's own tick** — inside
  `RegionThread.tickEntities()`'s existing loop (right where the port's own
  `nestworldTrackOwned(...)` call already sits, currently a throw stub): the *owning* region
  computes the entity's new section, decides which players now see/stop-seeing it, and sends the
  resulting packets directly — "phase 2: track this owned entity now (region phase)," per the
  port's own comment already in place. Stamps the entity's `nestworldTrackedTick` field to the
  current game tick as a receipt.
- **Main thread, `ChunkMap.tick()`, unconditionally every tick, unmodified vanilla body otherwise**
  — handles exactly two cases: players (always main-thread-owned — matches
  `BoundaryEntityTransfer.processEntity`'s existing "Players are ticked on the main thread" rule),
  and any entity whose owning region did **not** track it yet this tick (free-running region,
  startup transient, or regionalization simply not applying to it) — the reference patch's own
  comment: *"entities whose owning region already tracked them this tick carry the current tick
  stamp; skip them here so only players + unowned/deferred entities are handled on main."* The
  skip check is a single field read (`nestworldTrackedTick == currentTick`), on a field
  (`ChunkMap.TrackedEntity.nestworldTrackedTick`) that doesn't exist in this port at all — same
  as everywhere else, zero hits in the port's 100%-vanilla `ChunkMap.java`.

**Why this matters more than "just add the Mixin": the two halves are a coupled pair, not an
independent producer/consumer like B.6.6.3's random ticks or B.6.7's `doPush`.** Porting only
`nestworldTrackOwned` (the region-thread producer) without *also* porting the
`nestworldTrackedTick`-based skip check inside `ChunkMap.tick()` itself would double-track and
double-broadcast every entity — once from the region thread, once again from vanilla's
now-unmodified main-thread pass that has no way to know the region thread already handled it.
That's the exact same shape of bug B.6.6.1 found and fixed for entity ticking (a missing skip
guard, not a missing producer) — except this time in the tracking/broadcast domain. **Any future
B.6.8 implementation must land both halves together**, not the producer first and the guard
"later" — landing them separately would silently reintroduce B.6.6.1's exact failure mode in a
different subsystem.

Not implementing this now — audit only. This is the answer to the audit's real question; the rest
of the file (`OutboundBatchQueue`/`PairingOutboundQueue`/spatial culling) are lower-level
optimizations layered *inside* the region-thread half above, not separate execution-boundary
questions — worth a pass to confirm, not expected to change this conclusion.

### Remaining, still audit-only, nothing implemented:

- `ChunkMap` itself — every `net.nestworld.` reference in the reference pipeline, not just the
  one (`nestworldTrackOwned`) already spot-checked
- `RegionalizedTracker`, `OutboundBatchQueue`, `PairingOutboundQueue`, spatial culling
- entity tracker lifecycle: add / remove / update, visibility, broadcast, player tracking, packet
  batching
- for each: the NeoForge 1.21.1 equivalent execution point, whether a producer/consumer pair
  already exists the way B.6.6.3's random-tick queue and B.6.7's `doPush` message did, and whether
  each stage runs on the main thread or a region thread today

### What this does — and does not — change about B.3.0 / B.5.3

B.3.0 and B.5.3 are not retracted. They prove exactly what they claim: real Core region
execution, real cross-region world/entity mutation, real `EntityTransfer`. Not fixing this here — but the audit now cleanly delineates *how far* that regionalization already reaches:

```
NeoForge 1.21.1
        │
   ServerTickEvent.Post
        │
        ▼
   NestWorld Core Tick
        │
  ┌─────┴─────────────┐
  ▼                    ▼
Region execution     Vanilla systems
  │                    │
  ├─ entity tick        ├─ ChunkMap
  ├─ neighbor updates    ├─ entity tracking
  ├─ random ticks         ├─ broadcast
  ├─ addFreshEntity        ├─ visibility
  ├─ redstone                └─ networking
  └─ explosions
                        (RegionalizedTracker / OutboundBatchQueue /
                         PairingOutboundQueue / spatial culling /
                         nestworldTrackOwned -- NOT CONNECTED)
```

Simulation execution (the left branch) is genuinely regionalized, live-proven repeatedly this
segment. Entity tracking and network propagation (the right branch) are not yet — still 100%
vanilla main-thread, same as every B.3-era gap this whole project has learned to expect: silent,
not crashing, real reference fixes sitting unused. That the boundary is now this precise is
itself a good audit result, not a bad one.

## B.6.7 implementation gate — EntityOwnershipGuard + #123: live results

Authorized implementation, highest-risk-first. Four Mixins added: `EntityOwnershipGuardMixin`
(`@Inject` at `Entity.setDeltaMovement(Vec3)`/`setPosRaw`/`setRemoved`, matching the real patch's
3 `Entity`-side chokepoints exactly), `LivingEntityOwnershipGuardMixin` (the 4th chokepoint,
`actuallyHurt`), `LevelThreadSafeRandomMixin` (`@Redirect` on `Level`'s constructor, swapping
`RandomSource.create()` for `RandomSource.createThreadSafe()` — `ordinal = 1` specifically, since
the constructor has a second, unrelated `RandomSource.create()` call for a throwaway
`randValue` field that stays untouched, matching the reference patch's exact scope), and
`PersistentEntitySectionManagerConcurrentStorageMixin` (`@Redirect` on the `NEW EntitySectionStorage`
call inside `PersistentEntitySectionManager`'s constructor, swapping in
`net.nestworld.region.ConcurrentEntitySectionStorage`). All four compiled clean, all four applied
(`Mixing ... into ...` confirmed for each of the 4 target classes) with 0 exceptions at boot.

**Live re-validation of `EntityOwnershipGuard`, the actual point of this work — genuine success.**
Real client-driven combat: a real mineflayer bot attacked a real pig via real attack packets
(health 10.0→8.0, confirmed via RCON), exercising `actuallyHurt` on a real region-thread-owned
entity for the first time with the guard actually connected. **0
`NO_FOREIGN_ENTITY_MUTATION` — and for the first time, this number means something**: the guard
was genuinely watching, on a real cross-thread-relevant chokepoint, during a real client
interaction. This is the "before/after" pair the whole B.5.3 correction was building toward —
`EntityOwnershipGuard` can now be cited as real negative evidence going forward (not
retroactively for B.5.3 itself, which stays as corrected).

**RNG half of `#123` — clean success.** Zero `LegacyRandomSource` thread-collision exceptions for
the entire session, under the same test conditions (sustained mob AI activity, multiple region
threads) that reliably produced this exact exception before the fix. The one-line reference swap
works exactly as the real patch intended.

**`EntitySectionStorage` half of `#123` — fix applied, but does NOT fully close the gap.**
Confirmed `ConcurrentEntitySectionStorage` is genuinely active (visible in the stack trace itself:
`net.nestworld.region.ConcurrentEntitySectionStorage.forEachAccessibleNonEmptySection` appears in
the call chain) — but the same `ConcurrentModificationException` still occurred, **5 times**, in
the same `EntitySection.getEntities → ClassInstanceMultiMap.find → HashMap.computeIfAbsent` shape
as before the fix. Read the fix's own javadoc and code carefully to understand why: its
`ReentrantReadWriteLock` protects the **outer storage's** structural index (the section-key→
`EntitySection` map/tree) — a structural write there (creating/removing a section) is correctly
serialized against a structural read. But `forEachAccessibleNonEmptySection`'s read lock does
**not** serialize two concurrent *readers* against each other (that's the entire point of a
read-write lock — reads don't block reads), and the actual unguarded mutation is one level
**deeper**: `EntitySection`'s own internal `ClassInstanceMultiMap`, which lazily builds a
per-class-type index cache the first time `getEntities(SomeClass)` is called for that class — a
real mutation (`computeIfAbsent`), happening on a plain, non-thread-safe `HashMap`, that two
region threads can legitimately hit *simultaneously* on the very *same* `EntitySection` object
when their mob-AI search boxes (`AvoidEntityGoal`/`NearestAttackableTargetGoal`, which scan an
AABB, not a chunk-aligned area) happen to overlap the same section at the same real moment. The
storage-level lock this reference class adds was never going to prevent that — it's a different,
narrower race than the one its own javadoc describes (the AVL-tree structural corruption), one
level further in.

**Not patching this further right now** — this needs its own classification, same as every other
finding this segment: is the fix (1) an additional per-`EntitySection` lock, (2) synchronizing
`ClassInstanceMultiMap` itself, (3) something else. Reference storage-level fix is active and
correct for its intended structural race, but a deeper, independently observed `EntitySection`
index-cache race remains open. These are two different problems and should not be conflated. Its
RNG half is genuinely closed. `EntityOwnershipGuard` is genuinely wired and validated. Clean RCON
stop, `BUILD SUCCESSFUL`.

## B.6.7.1 — `EntitySection` internal index concurrency audit (diagnostic only, no fix)

**Scope, exactly as authorized**: prove or disprove — with direct evidence, not inference from a
single stack trace — that two different `RegionThread`s genuinely access the *same*
`EntitySection` instance's internal `ClassInstanceMultiMap` concurrently. No fix applied. Four
questions to answer with evidence.

**Method.** A temporary, pure-diagnostic Mixin (`EntitySectionRaceDiagnosticMixin`, `@Mixin
(EntitySection.class)`) wraps `getEntities(EntityTypeTest, AABB, AbortableIterationConsumer)` at
`@At("HEAD")`/`@At("RETURN")`. It keys a `ConcurrentHashMap<Integer,String> ACTIVE` by
`System.identityHashCode(this.storage)` — the actual `ClassInstanceMultiMap` object, the exact
field `find()` mutates — `putIfAbsent` on entry, `remove` on exit. If a second thread's entry
finds a non-null previous value still in the map, that is direct proof of concurrent access to
the identical object instance, not a timing inference. No behavior changed; log lines only.

Deployed live (`b1301` boot), confirmed applied with 0 exceptions, run for ~8 real minutes with
the standing standby bot keeping the test chunks loaded and natural mob AI/spawning running
undisturbed (no deliberate mob summoning this round — pure ambient activity was already enough).

**Results — 136 `OVERLAP DETECTED` events, 0 `ConcurrentModificationException` this run.**
Representative captured block (identical `section@`/`storage@` identity hashes, two named
regions, overlapping — the second literally entered while the first was still marked active):

```
[23:14:22] [NestWorld-Region-45/ERROR] [B.6.7.1] OVERLAP DETECTED on storage@1322051647:
  [region=45 thread=NestWorld-Region-45 class=Wolf   section@1425347237 storage@1322051647]
  entered while
  [region=55 thread=NestWorld-Region-55 class=Turtle section@1425347237 storage@1322051647] still active
```

A second, reciprocal pair on the same identity a few minutes later (region 32 ↔ region 77,
`storage@215717888`/`section@1790573019`, both `class=Player`), each entering while the other was
still active — i.e. sustained, repeated contention on the same object, not a one-off:

```
[23:20:34] region=32 entered while region=77 still active   (storage@215717888, class=Player both)
[23:20:36] region=77 entered while region=32 still active   (storage@215717888, class=Player both)
```

Over the full run, overlaps landed on 28 distinct regions, with multiple different entity classes
involved per storage identity (`Wolf`/`Turtle`/`IronGolem`/`Player`/`TropicalFish`/`Cat`), meaning
the colliding calls frequently target *different* map keys within the same `HashMap` — not just
repeat lookups of an already-cached class.

**Answering the four questions:**

1. **Is the same `EntitySection` genuinely read concurrently by two different `RegionThread`s?**
   **Yes — directly proven**, not inferred. 136 events, each showing identical `section@`/
   `storage@` identity-hash pairs from two named, distinct regions, with the second call's entry
   log line printed *while* the first call's exit log line had not yet fired.
2. **Does `ClassInstanceMultiMap.computeIfAbsent()` genuinely mutate a shared cache during
   `getEntities(...)`?** Confirmed earlier via direct source read (this segment, prior phase):
   `find(Class<S> type)`'s body is exactly `this.byClass.computeIfAbsent(type, p ->
   this.allInstances.stream()...)` against a plain `Maps.newHashMap()`. Combined with this
   phase's overlap evidence showing *different* classes queried concurrently on the same
   instance, the colliding calls are not always cache hits — a same-key double-`computeIfAbsent`
   race (both threads see the key absent, both attempt to insert) and a cross-key race (one
   thread's insert structurally modifies the map while another's `computeIfAbsent` is mid-call
   for a different key) are both directly evidenced by this run's captured pairs.
3. **Does this specific mutation genuinely cause the observed `ConcurrentModificationException`?**
   **Not conclusively correlated in this run** — honestly reported, not glossed over: 0 CMEs
   fired during this run's 136 overlaps, so no overlap-event-to-CME pair was captured together.
   What *can* be said without inferring from a single stack trace: `HashMap.computeIfAbsent`'s own
   documented JDK contract explicitly performs a structural-modification check around the mapping
   function call and throws exactly `ConcurrentModificationException` if the map was structurally
   changed during it — the same exception type, at the same call site
   (`HashMap.computeIfAbsent`), that the earlier live tests captured. The mechanism is consistent
   with the newly-proven overlap, but this run did not itself catch the two events together. The
   honest state: #1 and #2 are directly proven; #3 has a strong, JDK-contract-consistent
   mechanistic explanation but not a same-run direct correlation.
4. **Does the reference repo already have a mechanism for this exact level, or does it need new
   design?** Confirmed earlier via exhaustive grep of the real 1.20.1 repo: zero `nestworld`
   references inside `ClassInstanceMultiMap.java`, and no other file in the entire real repo
   references or wraps `ClassInstanceMultiMap` at all. **No existing reference mechanism — this
   would be new design.** Rules out Option D.

**Correction to B.6.7.1's original hypothesis — the byClass/computeIfAbsent path was not the
crash mechanism.** Live re-testing under a densely-clustered mob population (up to 361 entities in
a ~20-block radius at a persistent region boundary) caught 4 real `ConcurrentModificationException`s,
all tracing to an *entirely different* code path than the one B.6.7.1/first-pass B.6.7.2
instrumented: not `EntitySection.getEntities(EntityTypeTest, AABB, consumer)` (lines 40-56, the
class-filtered overload calling `storage.find()` → `byClass.computeIfAbsent`), but
`EntitySection.getEntities(AABB, consumer)` (lines 30-38, the **unfiltered** overload used by
collision/neighbor scanning — `Entity.collide()` → `getEntityCollisions()` → ... → this method),
which does a plain `for (T t : this.storage)` — i.e. iterates `ClassInstanceMultiMap.iterator()`,
which wraps `allInstances.iterator()`, a plain `ArrayList`. All 4 CMEs threw from
`ArrayList$Itr.checkForComodification` at exactly this call site. The `byClass`/`computeIfAbsent`
overlap proven in B.6.7.1 is real (456 outer-level events, 8 at the exact `computeIfAbsent` call,
2 with genuinely different keys) but was never co-captured with a CME in either session — it
remains a real, separate, latent race, not the explanation for the crashes actually observed.

**B.6.7.2 (redirected) — direct co-capture achieved.** Two new diagnostic Mixins —
`EntitySectionAllInstancesRaceMixin` (reader side: HEAD/RETURN bookkeeping on the unfiltered
overload, plus a `@Redirect` wrapping the returned iterator so a thrown CME is caught at its exact
origin) and `ClassInstanceMultiMapWriteDiagnosticMixin` (writer side: HEAD/RETURN bookkeeping on
`add(T)`/`remove(Object)`), sharing one identity-keyed state class — captured **32 direct
reader/writer overlap events** on the identical `allInstances` object identity between named
regions (e.g. region 19 ↔ 27/28/20 on `allInstances@1050018785`, sustained over ~10 minutes), and
**twice** caught the actual CME firing on that same identity, immediately following (within
seconds of) recorded overlaps on the same object between the same two regions. This closes the
causal chain the user required: same instance, genuinely concurrent reader/writer, on the exact
mutation type (`add`/`remove` with real entity references logged), producing the exact exception
observed — not inferred from a single stack trace.

**B.6.7.3 — is this an ownership violation, or an intentional shared-read pattern? Answered by
the real 1.20.1 repo, with a corrected earlier claim.** A static audit of the real repo found that
**B.6.7.1's "Option D — ruled out, no existing reference mechanism" was wrong**, and wrong for a
specific, nameable reason: the original grep searched for the literal string `"nestworld"` inside
`ClassInstanceMultiMap.java` and found zero hits, concluding no reference fix exists. The real
repo's actual patched file (`projects/forge/src/main/java/net/minecraft/util/ClassInstanceMultiMap
.java`) **is** modified — `byClass` is a `ConcurrentHashMap`, `allInstances` (and every per-class
list `find()` returns) is a `CopyOnWriteArrayList`, `add()`/`remove()` are `synchronized`, and
`find()` uses a double-checked-lock pattern around `computeIfAbsent` — but none of the modified
lines contain the string "nestworld" (no comment tag), so a keyword grep alone missed it entirely.
Same category of gap already named in memory as the "Dependency Audit Methodology Standard":
import/keyword-grep alone is insufficient; this is a second concrete instance of it.

The fix is `git`-confirmed shipped, commit `6e296f642` ("fix: thread-safe entity section contents
(ClassInstanceMultiMap)", 2026-06-11) — same symptom class (`ConcurrentModificationException` in
`EntitySection.getEntities`), same root cause named explicitly in the commit message ("a region
thread iterating the section's `ClassInstanceMultiMap.allInstances` ArrayList while another
thread added/removed an entity crossing the 16-block section border"), same distinction already
drawn here between the section *index* (`ConcurrentEntitySectionStorage`, lock-based, already
ported) and section *contents* (`ClassInstanceMultiMap`, this fix, not yet ported). Verified
in production: "10 min under 2000-cart load — 0 tick errors (was 12 on the same scenario), 20.0
TPS." **One patch closes both B.6.7.1's `byClass` race and B.6.7.2's `allInstances` race** — the
double-checked-lock in `find()` and the `ConcurrentHashMap`/`CopyOnWriteArrayList` swap are the
same change.

This directly answers the ownership question: the real architecture treats cross-region entity
collision/section-content reads as an **intentionally allowed shared-read pattern**, not an
ownership violation to be routed away — the commit message frames it explicitly ("hot read paths
(collisions, pushEntities, noCollision — thousands per tick) stay lock-free"). The real Core's
answer is the port's **Option B** (synchronize/harden the container itself), not Option A (a
coarser per-`EntitySection` lock) or a routing/ownership fix. Option D is retracted as "ruled
out" — it was available all along; B.6.7.1's classification stands corrected here.

A second, complementary, separately-shipped mechanism was found in the same audit:
`net.nestworld.region.EntityBoundsHelper.safeBoundingBox()` (commit `fe072e233`, "Entity Safety
Layer", 2026-08-11), wired into `EntityGetter.getEntityCollisions()`, substitutes a
periodically-published per-region entity-position snapshot for foreign-owned entities returned by
a collision scan — guarding against a *different*, position-staleness hazard on top of the list
itself being safe to iterate. Not yet checked for portability to the NeoForge port; noted, not
scoped as a task yet.

**Status, nothing implemented on the NeoForge port yet:**
- B.6.7.1 — `byClass`/`computeIfAbsent` overlap: real, PARTIAL/superseded as sole crash
  explanation (see correction above), but not a separate problem needing its own fix — the real
  repo's reference fix for B.6.7.2 closes this too.
- B.6.7.2 — `allInstances` reader/writer race: root cause CONFIRMED live (32 overlaps, 2
  co-captured CMEs), reference fix identified, understood, and independently verified (git log +
  direct file read), not yet ported.
- B.6.7.3 — ownership-model question: ANSWERED. Shared-read is intentional; fix is
  container-hardening (Option B), not ownership routing.
- Port scope, if authorized: replace `ClassInstanceMultiMap`'s `byClass`
  (`Maps.newHashMap()` → `ConcurrentHashMap`) and `allInstances`/per-class lists (`ArrayList` →
  `CopyOnWriteArrayList`), add `synchronized(this)` around `add`/`remove`, and the
  double-checked-lock pattern in `find()` — a direct byte-for-byte port of the real patch, same
  pattern already used successfully for `#123`'s RNG half and `EntityOwnershipGuard`'s chokepoints
  this session (Mixin-based field/behavior swap on a vanilla class, not a NestWorld-authored
  replacement class this time — `ClassInstanceMultiMap` itself isn't Mixin-friendly for a field
  type change via simple `@Redirect`/`@Inject`, so this would likely need either a full
  Mixin-based class rewrite via `@Overwrite`, or coremod-style bytecode replacement — flagged as a
  scoping question for whoever picks up the implementation, not resolved here).

## B.6.7.4 — Reference `ClassInstanceMultiMap` port gate: PASS, live-verified

Authorized port of the whole reference patch (commit `6e296f642`), not a local CME-only
workaround — the user's explicit direction, since a partial port would close `allInstances` while
leaving B.6.7.1's already-proven `byClass` race open.

**Port gate steps taken, in order:** (1) exact commit diff pulled via `git show`; (2) compared
field layout/constructor/`add`/`remove`/`find`/`iterator` semantics/generics between the real
1.20.1 vanilla base and the NeoForge 1.21.1 base — identical except a cosmetic 1.21.1 rename
(`Util.toMutableList()` where 1.20.1 used `Collectors.toList()`, both plain mutable-list
collectors); (3) determined the least-risky Mixin strategy.

**First attempt failed, informatively.** A plain `@Redirect` on the two Guava factory calls
(`Maps.newHashMap()`, `Lists.newArrayList()`) failed to apply live:
`InvalidInjectionException: Found unexpected return type java.util.Map, expected
java.util.HashMap` — Mixin's `@Redirect` requires the handler's declared return type to exactly
match the redirected call's own return type, and `ConcurrentHashMap`/`CopyOnWriteArrayList` are
siblings, not subtypes, of `HashMap`/`ArrayList`. The server logged this as `FATAL` and crashed on
first tick (a `NullPointerException` cascade from the half-broken constructor), confirming the
failure loudly rather than silently — exactly the outcome you want from a bad Mixin apply.

**Working strategy:** leave the vanilla constructor untouched (it builds a throwaway, empty
`HashMap`/`ArrayList` — harmless, since no entities exist yet at construction time), then
`@Inject(method = "<init>(Ljava/lang/Class;)V", at = @At("RETURN"))` replaces both fields
(declared `@Shadow @Final @Mutable` to permit the reassignment) with `ConcurrentHashMap`/
`CopyOnWriteArrayList` instances, re-doing the single `byClass.put(baseClass, allInstances)`
association the constructor already performed. `add`/`remove`/`find` are fully `@Overwrite`-d to
match the reference's `synchronized`/double-checked-lock bodies exactly (`@Overwrite` requires
exact signature match with the original, which trivially holds here — no return-type ambiguity
issue, unlike the field-initializer `@Redirect`). Compiled clean; applied clean on reboot with 0
Mixin errors.

**The four temporary B.6.7.1/B.6.7.2 diagnostic Mixins (`EntitySectionRaceDiagnosticMixin`,
`EntitySectionAllInstancesRaceMixin`, `ClassInstanceMultiMapCmeCorrelationMixin`,
`ClassInstanceMultiMapWriteDiagnosticMixin`) were removed**, not left stacked alongside the real
fix — their evidence-gathering purpose was complete, and `ClassInstanceMultiMapCmeCorrelationMixin`
specifically would have conflicted with the new `find()` `@Overwrite` (redirecting an invoke
instruction inside a method another Mixin fully replaces). Their shared diagnostic state class
was deleted too.

**Live stress verification, matching (exceeding) the reference commit's own validation bar**
("10 min under 2000-cart load — 0 tick errors, 20.0 TPS"): a dense mixed-entity cluster (turtle,
llama, villager, iron golem, wolf, cat, tropical fish, rabbit — 611 entities in a ~20-block radius
at the same persistent region boundary that produced the original CMEs) sustained for ~11 minutes
under the standing standby bot's load. **Result: 0 `ConcurrentModificationException`, 0 tick
errors, server alive and responsive throughout.** `NO_FOREIGN_ENTITY_MUTATION` was tracked
separately, not conflated with this result (12,732 this session) — a real, already-known,
unrelated finding (the still-unwired `LivingEntity.doPush`/`Entity.collide` cross-region gap
B.6.7 originally flagged), explicitly out of scope for this port.

**B.6.7.4 — PASS.** Reference fix ported byte-for-byte (by semantics, not by literal bytecode
mechanism — the Mixin strategy differs from a hypothetical direct field-type edit, but the
resulting runtime behavior is identical to the reference), live-verified under conditions that
reliably reproduced the bug before the port. `#123`'s `EntitySectionStorage` half — now genuinely
CLOSED, both layers (index via `ConcurrentEntitySectionStorage`, contents via this fix).

No fix applied for `NO_FOREIGN_ENTITY_MUTATION`/`doPush` — that's B.6.7.2 (original numbering,
`LivingEntity.doPush`'s coupled-pair gap), sequenced next per the user's plan, after a
`safeBoundingBox()` portability audit (B.6.7.5).

## B.6.7.5 — `EntityBoundsHelper.safeBoundingBox()` portability audit + port: PASS, Gate B

**Audit.** Real repo commit `fe072e233` ("Entity Safety Layer") has exactly one call site for
`EntityBoundsHelper.safeBoundingBox()`: the collision-shape-building loop inside
`EntityGetter.getEntityCollisions()`, substituting a foreign-owned entity's per-tick published
`WorldRegion.EntitySnapshot` position for a direct read of its live bounding box. Not used by
`doPush`/`pushEntities`/`noCollision`. Explicitly a **read-only, self-correcting** hazard per the
commit's own javadoc — no crash, no data corruption — a materially lower severity class than
B.6.7.2/4's actual CME. NeoForge 1.21.1's decompiled `EntityGetter.getEntityCollisions()` is
structurally near-identical at the relevant line, unpatched, hazard present. Checked the port's
Core (copied verbatim, as expected): `WorldGrid.findOwningRegion`, `WorldRegion.ownsEntity`/
`EntitySnapshot`, `RegionThread.publishEntitySnapshot()` all already present and wired;
`EntityBoundsHelper.java` itself confirmed byte-for-byte identical to the real repo (`diff` exit
0) — already copied over, never called. Gate verdict: **B** — same execution edge, all supporting
infrastructure present, only the adapter-side wiring was missing. Not inferred from the
`NO_FOREIGN_ENTITY_MUTATION` violation count (a different producer — `setDeltaMovement`/
`actuallyHurt`, not `getBoundingBox()` reads) — necessity established by the reference
architecture and the presence of the unpatched edge itself.

**Port, exactly as authorized.** A single `@Redirect` on `Entity.getBoundingBox()`'s invocation
inside `EntityGetter.getEntityCollisions()`'s collision-shape loop, swapping in
`EntityBoundsHelper.safeBoundingBox(entity)`. Nothing else touched — `doPush`/`pushEntities`/
`noCollision`/ownership routing/snapshot publication/`EntityBoundsHelper` itself all untouched.
`EntityGetter` is an interface; `@Mixin(EntityGetter.class)` on an `interface` mixin class applied
cleanly. One real Mixin-mechanics finding worth recording: `CommonLevelAccessor` (which
`Level`/`ServerLevel` implement via `LevelAccessor`) re-declares `getEntityCollisions` as its own
default method, delegating via `EntityGetter.super.getEntityCollisions(...)` — confirmed live
(temporary `@Inject(at="HEAD")` diagnostic, later removed) that this super-call still reaches and
fires the redirect inside the patched `EntityGetter` class file, so no additional Mixin into
`CommonLevelAccessor` was needed.

**Live verification, full checklist.** (1) Mixin applies, 0 errors at boot. (2) Redirect
demonstrably scoped to `getEntityCollisions()` only, by construction (method-qualified) — no
interference with `isUnobstructed()`'s separate call site observed. (3)/(4) A temporary,
verification-only diagnostic (removed after evidence gathered) confirmed: **86,800+ live calls**
into the redirect handler under a wide-spread mixed-entity + boat cluster (boats specifically,
since `Entity.canBeCollidedWith()` is `false` by default for ordinary mobs — turtles/wolves/cats/
etc. never reach this call's inner branch at all; only `Boat`/`Shulker` do, a real scoping fact
worth recording for future tests of this exact method), **25 foreign-owned entities encountered,
0 with a missing snapshot** (snapshot publication reliably ahead of consumption), **3 genuine
position substitutions logged** with real, tiny (sub-millimeter, one-tick-scale) coordinate
differences between the live and snapshotted bounding boxes — the mechanism doing exactly what its
javadoc describes. (5) 0 new exceptions, 0 CME (B.6.7.4 regression guard held). (6) No collision
anomalies observed. (7) `NO_FOREIGN_ENTITY_MUTATION` explicitly not used as a pass criterion here
— tracked separately, unrelated subsystem.

**B.6.7.5 — PASS.** Temporary diagnostics removed after evidence gathered; only the one-line
`@Redirect` Mixin remains. `#123`/B.6.7 family entity-collision-adjacent findings are now fully
closed at both severity levels (crash-class via B.6.7.4, soft-correctness via B.6.7.5). Next:
`LivingEntity.doPush`/`Entity.collide` ownership path — starting from the reference's
producer → routing → mailbox → consumer breakdown, per the user's explicit direction, not from
code — the 12,732 `NO_FOREIGN_ENTITY_MUTATION` violations already show this execution path does
not yet match the ownership model.

## B.6.7.6 — Entity Push / Collision Ownership Audit (audit-only, no implementation)

Full producer → routing → mailbox → consumer trace of the real repo's `LivingEntity.doPush`
mechanism, plus a precise correction to the assumed relationship between `Entity.collide()` and
`doPush()`. No code touched — this is purely a static audit, per explicit direction.

### 1. Producer — `LivingEntity.doPush(Entity other)` (real repo,
`net/minecraft/world/entity/LivingEntity.java:2743-2787`)

Branch-by-branch, verified by direct read (not inferred):

- **Guard**: `NestworldRegionSystem.isInitialised() && Thread.currentThread() instanceof RegionThread
  && isManagedLevel(this.level())`. If false → falls straight through to the vanilla one-liner
  `p_20971_.push(this)` at line 2786 (unmodified vanilla mutual-push, both sides mutated directly).
  This is the ONLY guard for "are we even running under Core" — no other condition skips the
  region-aware branch.
- **Ownership resolution**: `nestworldOwner = findOwningRegion(other.getUUID())` — resolves the
  OTHER entity's owner. `this`'s own ownership is never explicitly checked; it's implicitly
  assumed to be `nestworldRt.getRegion()` (the thread currently ticking it).
- **`owner == null` case**: falls through to the vanilla `p_20971_.push(this)` at line 2786 — i.e.
  an untracked/unowned entity is treated as "safe to push directly," with no verification that
  it's genuinely local. This is a real, explicit reference-architecture assumption, not something
  this audit should silently paper over: a transient window where an entity exists but hasn't yet
  been assigned an owning region (freshly spawned this exact tick, before ownership assignment
  runs) would take this path. Flagging it precisely rather than assuming it's already closed.
- **`owner == self` case** (same region): also falls through to line 2786 — safe, both entities
  local to the same thread.
- **`owner != self` case** (genuinely foreign) — the only path that enters the custom block:
  - Vehicle/no-physics short-circuit: `isPassengerOfSameVehicle(other) || other.noPhysics ||
    this.noPhysics` → `return` with **no push at all**, neither local nor remote.
  - Snapshot lookup: `owner.nestworldGetEntitySnapshot().get(other.getUUID())`. `null` or
    `snap.removed()` → `return`, **no push at all** (foreign entity considered gone/untrackable
    this tick — a missed push, not a crash).
  - Push vector computed from **`this`'s own live position** vs. **the foreign entity's
    snapshot position** (`d0 = this.getX() - snap.x()`, etc.) — same snapshot-substitution
    pattern B.6.7.5 already confirmed live, but here feeding a MUTATION vector, not a read-only
    collision box.
  - Distance threshold `d2 >= 0.01F` (matches vanilla's own push-apart threshold) — below it, no
    push in either direction.
  - **Local mutation**: `this.push(d0, 0, d1)` applied directly (safe — `this` is local).
  - **Foreign mutation deferred**: `nestworldRt.getRegion().nestworldSendOrQueue(nestworldOwner,
    RegionMessage.entityPush(..., new RegionMessage.EntityPush(other.getUUID(), -d0, 0, -d1)))` —
    the OPPOSITE (negated) vector, for the owner to apply to its own local entity later.
  - `return` — the foreign branch NEVER falls through to the raw `p_20971_.push(this)` vanilla
    call; that fallback is reached only by the null-owner/same-region/non-region-aware paths.

### 2. Routing

`WorldGrid.findOwningRegion(UUID)` is the sole authority — an O(1) map lookup (per this session's
earlier audits), not position-based. `WorldRegion.nestworldGetEntitySnapshot()` is the per-region
`AtomicReference<Map<UUID,EntitySnapshot>>` B.6.7.5 already confirmed is published every tick,
after `tickEntities()`, for every entity in `getOwnedEntityIds()` (`RegionThread.java:995-1004`).
No separate "routing" layer beyond this single resolve-and-compare — same/foreign is a single
`==` comparison against the thread's own region.

### 3. Mailbox — `RegionMessage.EntityPush`/`Type.ENTITY_PUSH`

`record EntityPush(UUID entityId, double dx, double dy, double dz)`
(`RegionMessage.java:265`) — a raw, already-fully-computed velocity delta. Javadoc confirms: "the
sender using a safe snapshot read... the owning thread only re-checks isVehicle()/isPushable()."
Sent via `WorldRegion.nestworldSendOrQueue` (`WorldRegion.java:540-547`) — the SAME generic
backpressure-aware sender path every message type uses: under threshold, posted directly; over
threshold, held at the SENDER (never dropped, never forced), retried on the sender's own next
local tick via `nestworldFlushOutboundPending`. No `EntityPush`-specific queue/priority logic.

**Critically, unlike `ENTITY_MUTATE` (which carries `rerouteHops`, bounded reroute-on-stale-
ownership) or `PLAYER_ATTACK`/`PLAYER_USE_CONTROL` (which reroute to the entity's current owner),
`EntityPush` has NO reroute field and NO reroute mechanism at all.** Directly answering the user's
question — **yes, a push message can be delivered after the entity changed owner** (confirmed by
a real, dated finding: `RegionThread.java:798-806`'s comment, "found live via EntityOwnershipGuard
during the LevelTicks soak test 2026-08-13... two confirmed violations"). The reference's answer
is **not reroute — drop silently** (see Consumer, below). This is a deliberate architectural
asymmetry versus `ENTITY_MUTATE`, not an oversight left unaddressed: a missed push-apart is a
one-tick physics glitch that self-corrects (the two entities are almost certainly still
overlapping next tick, so `doPush` fires again), whereas an `ENTITY_MUTATE` op (damage/ignite/
effect) is typically a one-shot event where silently losing it matters more — worth recording as
the reasoning, not just the mechanism.

### 4. Consumer — `RegionThread.applyPendingEntityPushes()` (`RegionThread.java:793-816`)

Runs on the **owning region's own thread**, as part of the same per-tick sequence
`applyPendingEntityImpacts(); applyPendingEntityPushes(); applyPendingEntityMutations(); ...`
(`RegionThread.java:566-570`), BEFORE `tickEntities()` in the same loop iteration.

- Drains `Type.ENTITY_PUSH` from the region's own mailbox.
- Resolves the **live** entity via `level.getEntity(push.entityId())` — NOT a snapshot; safe here
  because this thread now genuinely owns it (or is re-verifying that it still does, next point).
- **Apply-time ownership re-check**: `EntityOwnershipRecheck.stillOwns(region, push.entityId())`
  — a plain `region.ownsEntity(uuid)` membership check (`EntityOwnershipRecheck.java:43-47`, O(1),
  no grid-wide re-resolve). If the entity is no longer owned by this region (transferred between
  send and drain — confirmed to genuinely happen, see above) — **the push is silently dropped**,
  with a diagnostic skip counter (`recordSkip`) but no re-route, no re-post to the new owner.
- Re-checks `entity != null && !entity.isRemoved() && !entity.isVehicle() && entity.isPushable()`
  — vanilla's own push-eligibility guards, re-verified here specifically because they're "only
  safe to read on the owning thread" (matching the producer's own comment about why it couldn't
  check these on the foreign entity before sending).
- Applies via the entity's own `push(dx, dy, dz)` — a local, same-thread mutation, safe.
- Split/merge: no special-casing anywhere in this path — `stillOwns` is a plain, unconditional
  membership check against whatever the CURRENT region-ownership state is, regardless of what
  caused it to change (natural boundary crossing, split, merge). The same "recheck-then-drop"
  mechanism handles all of them uniformly; no separate split/merge logic was found or needed.
- No recursion/reroute-loop protection exists because there is no reroute to loop.

### 5. `Entity.collide()` vs. `LivingEntity.doPush()` — corrected relationship, not the assumed one

The proposed diagram nested push resolution inside `collide()`. Verified against source: **this is
not the structure.** They are separate, sibling calls inside `LivingEntity.aiStep()`, not
parent/child:

```
LivingEntity.aiStep()
  ├── ... → LivingEntity.travel() → Entity.move() → Entity.collide()
  │           └── EntityGetter.getEntityCollisions() → EntityBoundsHelper.safeBoundingBox()
  │               (B.6.7.4/.5 territory — geometry READ only, computes a corrected movement
  │                vector against physically-blocking entities; canBeCollidedWith()==true only
  │                for Boat/Shulker by default, confirmed in B.6.7.5)
  │
  └── LivingEntity.pushEntities()                              [SEPARATE call, same aiStep()]
        └── level().getEntities(this, boundingBox, pushableBy(this))   -- NOT getEntityCollisions()
              └── LivingEntity.doPush(other)   [B.6.7.6 territory — MUTATION, this audit]
                    ├── local: this.push(...)
                    └── foreign: RegionMessage.EntityPush → mailbox → applyPendingEntityPushes()
```

Confirmed two ways: (a) directly reading `pushEntities()`'s body
(`LivingEntity.java:2680-2718`/decompiled NeoForge `2891-2914`) — it calls
`this.level().getEntities(this, boundingBox, EntitySelector.pushableBy(this))`, a *different*
`getEntities` overload than `getEntityCollisions()` calls, with a *different* predicate
(`pushableBy`, not `CAN_BE_COLLIDED_WITH`) — never touching `EntityBoundsHelper` at all; (b) this
session's own earlier live stack traces show both as separate frames under `aiStep()`:
`LivingEntity.travel(2249)`/`Entity.move`/`Entity.collide` in one capture, `LivingEntity
.pushEntities(2895)` in another, never nested inside each other. B.6.7.4/.5 (collision-read
safety) and B.6.7.6 (push-mutation ownership) sit on **entirely non-overlapping call paths** —
an even cleaner separation than assumed, not merely a nested distinction.

### NeoForge 1.21.1 comparison (structural, no implementation)

Decompiled `LivingEntity.doPush` (NeoForge, line 2942-2944): `protected void doPush(Entity entity)
{ entity.push(this); }` — a bare one-liner, structurally identical to the real repo's own
fallback path (line 2786), confirming the port currently has ZERO region-awareness here (fully
explains the 12,732 `NO_FOREIGN_ENTITY_MUTATION` violations observed this session). `pushEntities()`
matches structurally (list-build, cramming-check, per-entity `doPush` loop) minus the real repo's
`NestworldTuning.MAX_ENTITY_PUSH` cap (an unrelated, separate enhancement, not in scope). Same
signatures both sides (`protected void doPush(Entity)`) — a straightforward port target. Two
sibling call sites the real repo already found and fixed the same way — `Ravager.blockedByShield`
and `ArmorStand.pushEntities`, both routed through the reusable `EntityPushHelper.redirectIfForeign`
(`net.nestworld.region.EntityPushHelper`, already present verbatim in the port, unused) — noted
for completeness, explicitly out of scope for this checkpoint per the user's own framing.

### Status

Audit complete. Implementation authorized and completed — see below.

## B.6.7.6 implementation — `LivingEntity.doPush()` port: PASS, chain proven

Ported the exact reference branch structure (section 1 above) via `@Overwrite` on NeoForge's
`LivingEntity.doPush(Entity)` — a bare one-liner there (`entity.push(this)`), so a full-method
overwrite is low-risk (no other Mixin touches this method). No reroute added — `EntityPush`'s
silent-drop-on-stale-ownership is preserved exactly, per explicit instruction. Scope held exactly
to `doPush` itself; `EntityPushHelper`/`RegionMessage.EntityPush`/`applyPendingEntityPushes`/
`EntityOwnershipRecheck`/`findOwningRegion` all untouched (verbatim Core, already present).

**Proving the chain, not just the count — the user's explicit bar.** A temporary diagnostic
(`DoPushDiagnostic`, since removed) logged `MailboxAudit.summary()` after every foreign-branch
send, using `MailboxAudit`'s own **existing, unmodified** `recordSent`/`recordApplied` calls
already wired into `WorldRegion.nestworldPostMessage`/`RegionThread.applyPendingEntityPushes` —
booted with `NESTWORLD_MAILBOX_AUDIT=1`, no Core code touched to get this signal. Result at
session end: **`sent=9197 applied=9194 pending=3 duplicates=0 misrouted=0`** — sent and applied
tracking each other near-1:1 throughout (never a growing gap), 0 duplicates, 0 misrouted. This is
direct, mechanical proof of producer → mailbox → consumer completion, not an inference from a
violation count dropping.

**Even stronger: exhaustive proof `doPush` is the actual source of zero remaining violations.**
Grepped the *entire* boot log for `.doPush(` across all 392 `NO_FOREIGN_ENTITY_MUTATION`
violations recorded this session — **zero matches**. Every remaining violation's stack trace was
checked; they resolve to exactly two other, non-`doPush` producers:
- `Animal.actuallyHurt`/`LivingEntity.hurt`, reached via `LlamaSpit.onHitEntity` →
  `Projectile.onHit` — a projectile-impact combat path, `ProjectileImpactGuard` territory
  (already catalogued earlier this session's audit, separate checkpoint, not this one).
- `Entity.push` reached via **`Boat.push(Entity)` → `Boat.tick()`** — `Boat` doesn't extend
  `LivingEntity` and never goes through `pushEntities()`/`doPush()` at all, so this Mixin
  correctly does not (and structurally cannot) touch it. **A genuinely new finding**: a third
  "orphan direct-push site" beyond the two the real repo's own `EntityPushHelper` javadoc already
  names (`Ravager.blockedByShield`, `ArmorStand.pushEntities`) — `Boat.push` was not previously
  catalogued anywhere in this session's audit trail. Noted, explicitly not fixed here (out of
  scope for B.6.7.6).

**Acceptance criteria:**
1. **Same-region** — structurally guaranteed by the ported branch logic itself (the `owner ==
   self` check falls through to the identical vanilla `other.push(this)` fallback every time),
   and empirically confirmed by the same exhaustive stack-trace check: same-region pushes,
   producing zero `doPush`-attributed entries anywhere in a 392-violation, multi-thousand-entity
   session, is exactly the expected signature of a correctly-guarded local path.
2. **Cross-region** — directly proven via the mailbox-audit chain above: `sent=9197
   applied=9194`, the local half applied via direct `self.push(...)`, the remote half delivered
   and applied via `EntityPush`.
3. **Owner-change race** — not artificially forced, occurred naturally (a `Llama` observed
   transferring `region#30 → region#65` mid-session via the standard `EntityTransfer` log). No
   separate "dropped due to stale ownership" counter exists in `MailboxAudit` (nor did the
   reference ever have one) — a stale-owner push is counted as "applied" (processed) by the
   existing code either way, matching the reference's own silent-drop design exactly, with no new
   observability invented beyond what the reference itself has.
4. **Regression stress** — 0 `ConcurrentModificationException`, 0 tick errors (B.6.7.4 held).
   `NO_FOREIGN_ENTITY_MUTATION` did NOT drop to 0 (392 this session) — correctly not expected to,
   per the user's own explicit caveat: the two remaining producers (projectile impact, `Boat
   .push`) are real, separate, unported paths. Server remained responsive throughout (RCON
   answered continuously); the tick-wall pressure observed (100-180ms spikes) traces to
   accumulated entity count from this whole session's repeated stress tests (1,863 entities
   total in the persisted world by session end), not a regression from this port.

**B.6.7.6 implementation — PASS.** Temporary diagnostic (`DoPushDiagnostic`) removed after
evidence gathered; only the `@Overwrite` Mixin remains. Next: `Boat.push`/`Ravager
.blockedByShield`/`ArmorStand.pushEntities` orphan sites and the `EntityBoundsHelper
.safeBoundingBox()`-adjacent `ProjectileImpactGuard` path both remain open, explicitly out of
scope here — not this checkpoint's concern per the user's own framing.

## B.6.7.7 — Direct-Push Producer Inventory: PASS (audit/coverage checkpoint, closed)

For every entity-push-shaped mutation site the real repo has, beyond `LivingEntity.doPush`
(B.6.7.6, closed): what it is, whether it's the same semantic push as `EntityPush`, and whether
the real repo itself has already addressed it or not. Scoped wider than the three sites named at
the start of this checkpoint — a direct grep for every `.push(this)`/`super.push(`-shaped call
across `net/minecraft/world/entity/` surfaced two more real, unaddressed sites nobody had named
yet, and ruled out three call sites that looked relevant but are downstream plumbing, not producers.

**Already covered in the real repo (same semantic push, not yet ported to NeoForge):**
- `ArmorStand.pushEntities()` (`ArmorStand.java:280-292`) — `if
  (!EntityPushHelper.redirectIfForeign(this, entity)) { entity.push(this); }`. Identical
  mutual-push-apart shape to `doPush`, routed through the shared helper instead of inline logic.
  `ArmorStand.doPush()` itself is overridden to a no-op (armor stands aren't pushed BY others this
  way) — not a separate producer.
- `Ravager.blockedByShield()` (`Ravager.java:200-216`), the 50%-RNG "stunned" branch — same
  `EntityPushHelper.redirectIfForeign` pattern as ArmorStand.
- `Ravager.strongKnockback()` (`Ravager.java:245-`), called from `blockedByShield()`'s other 50%
  branch AND from `roar()`'s AOE knockback loop — a **third, distinct pattern**: the delta is
  already precomputed (custom knockback math, not the standard push-apart formula), so this
  bypasses `EntityPushHelper` entirely and constructs `RegionMessage.EntityPush` directly — same
  underlying mailbox primitive/consumer, different producer-side shape. `roar()`'s *damage* half
  (not the knockback) already uses the unrelated `EntityMutationHelper`/`ENTITY_MUTATE` primitive
  correctly — out of scope for a push inventory, noted only for completeness.

**NOT yet addressed even in the real repo — genuinely new findings, not port gaps:**
- **`Boat.push(Entity)`** (`Boat.java:192-201`) — plain `super.push(p_38373_)` (vanilla mutual
  push), zero `EntityPushHelper`/ownership check, in the real 1.20.1 repo itself. Same semantic
  shape as `doPush`/`ArmorStand` — a natural `redirectIfForeign` candidate, but this is real,
  unfixed production code, not something to "port."
- **`AbstractMinecart`'s two push call sites** (`AbstractMinecart.java:360` and `:375`, both inside
  its own `tick()`) — `entity1.push(this)`/`entity.push(this)`, again plain vanilla, zero
  ownership check, in the real repo. Same semantic shape, same fix candidate.
- Both confirmed via direct source read, not inferred — this checkpoint's own methodology
  standard (mechanical proof, not count-based inference) applied to the audit step too.

**Ruled out — downstream plumbing, not separate producers** (each is only ever reached by a
caller that already decided the push was local-safe, so guarding a producer upstream already
covers them):
- `LivingEntity.push(Entity)` (`LivingEntity.java:1955-1960`) — the sleep-check override that
  `entity.push(this)` calls resolve to; calls `super.push()` (`Entity.push`, the real mutation).
- `Slime.push(Entity)` (`Slime.java:211-217`) — same category, plus a genuine, pre-existing
  reference-architecture nuance worth recording: `applyPendingEntityPushes()`'s consumer calls
  `entity.push(dx, dy, dz)` — the 3-double-arg overload — which does NOT go through `Slime`'s
  1-arg `push(Entity)` override, so the IronGolem-damage side effect `Slime.push(Entity)` adds is
  skipped for a push delivered via `EntityPush` (vs. one applied locally through the 1-arg
  overload). This is already true of the real repo's own existing mechanism, not something this
  port introduced — a real, minor local-vs-cross-region behavior difference the reference itself
  already accepts.
- `ItemFrame.push(double, double, double)` (`ItemFrame.java:137-142`) — the 3-arg self-apply
  overload guarded by `!this.fixed`, called by the SAME already-audited producers (including the
  `EntityPush` consumer itself, for any target entity type) — not a producer in its own right.

### Final classification

```
B.6.7.7 Push-site audit
│
├── Already covered by reference architecture (straightforward NeoForge port, B.6.7.6-shaped)
│   ├── ArmorStand.pushEntities()
│   ├── Ravager.blockedByShield() -> stun branch
│   └── Ravager.strongKnockback()
│
├── Real production bugs in the reference repo (NOT a port gap -- see below)
│   ├── Boat.push(Entity)
│   └── AbstractMinecart's two push call sites (tick():360, :375)
│
└── Not producers (downstream plumbing, already covered by guarding the callers above)
    ├── LivingEntity.push(Entity)
    ├── Slime.push(Entity)  -- see Slime nuance below
    └── ItemFrame.push(double, double, double)
```

**B.6.7.7 — PASS.** This was an audit/coverage checkpoint: every push-shaped call site in
`net/minecraft/world/entity/` has been found and classified. It is closed on that basis — it does
not stay open pending `Boat`/`AbstractMinecart` fixes, because proving coverage was the actual
deliverable, and coverage is complete. Finding two real defects along the way is a strong audit
result in its own right, not unfinished business.

**`Boat`/`AbstractMinecart` — explicitly NOT part of the NeoForge-port track.**

```
Category:      Reference-repo defect
Origin:        Existing 1.20.1 production code (net.minecraft.world.entity.vehicle.Boat /
               AbstractMinecart), unrelated to this NeoForge experimental project
Found by:      B.6.7.7 (this NeoForge-port audit), incidentally
Port status:   NOT A PORT GAP -- there is nothing in the real repo to port; the real repo itself
               never fixed this
Fix:           Required upstream, in the real 1.20.1 repo, before any NeoForge equivalent
               is considered
```

Tracked separately (tasks below), gated: fix upstream in the real repo → reference-side proof →
only then consider a NeoForge port with its own proof, exactly the same two-step discipline every
other B.6.7.x fix in this document already followed (real-repo fix first, NeoForge port second,
never invent new behavior in the port itself). Not touched further in this NeoForge track without
that upstream fix landing first.

**Slime nuance — documented, not treated as a defect.**

```
Local:                                  Cross-region (via EntityPush):
Slime.push(Entity)                      applyPendingEntityPushes()
      |                                       |
1-arg override                          push(dx, dy, dz)
      |                                       |
Slime-specific IronGolem damage         3-arg overload
                                               |
                                         Slime's 1-arg override bypassed
                                         (no IronGolem damage this path)

Port regression:              NO
Reference behavior:           accepted, already present in the real repo's own mechanism
Potential semantic difference: YES (local push vs. cross-region push behave slightly
                                differently for Slime-vs-IronGolem specifically)
Action:                        DOCUMENT (done, above) -- not "fixed", since the reference
                                itself is built this way on purpose (or at least, not yet
                                flagged as wrong by anyone with authority to say so)
```

## B.6.7.8 — Damage/Hurt/Knockback Producer Inventory (audit only)

Same methodology as B.6.7.6/.7: producer → what it mutates → who owns it → same-region path →
foreign-region path → reference status → (live proof deferred to implementation).

The real repo's answer to this whole category is a single shared primitive, already covering
both "damage/hurt/knockback" and "remove/discard/kill" from the proposed 8-category list in one
mechanism:

- `net.nestworld.region.EntityMutationHelper` — two entry points: `redirectIfForeign(self,
  target, op)` for `RegionThread` callers, `redirectFromMainThread(target, op)` for main-thread
  callers (players, ticked deliberately off-region to avoid rubber-banding). Both resolve
  `owner = findOwningRegion(target.getUUID())`, fall through to the original direct call when
  local/unsharded, otherwise post a `RegionMessage.EntityMutate(target.getUUID(), op)` and return
  `true` so the caller skips its own direct call.
- `net.nestworld.region.EntityMutationOp` — a sealed interface: `Damage`, `AddEffect`, `Ignite`,
  `ExtinguishFire`, `Knockback`, `Kill`, `Discard`. Deliberately closed-shape; anything needing
  more than one method call or a from-scratch recompute (explosions) gets its own bespoke message
  type instead (matches `ENTITY_IMPACT`'s existing precedent).
- Consumer: `RegionThread.applyPendingEntityMutations()` (`RegionThread.java:827-841`) drains
  `Type.ENTITY_MUTATE`, calls `EntityMutationDispatcher.applyQueued(entityId, op, rerouteHops,
  region, level)` — **this one has reroute** (`rerouteHops`, bounded), unlike `EntityPush`'s
  silent-drop-only design — a deliberate, already-established asymmetry (one-shot mutations are
  worth re-routing; continuous physics like push isn't).

### 1. Already covered by reference architecture (straightforward NeoForge port candidates)

All 13 of these use `EntityMutationHelper` correctly today in the real repo; **none have any
Mixin in the NeoForge port** (confirmed: zero `@Mixin` targets for any of these 13 classes):

- `ThrownPotion.java:112,120,161` — splash/lingering potion effect application (Damage/AddEffect
  variants across harmful/beneficial branches).
- `EvokerFangs.java:116,125` — bite damage.
- `AreaEffectCloud.java:254` — AOE cloud tick damage/effect application.
- `ShulkerBullet.java:281,293` — direct-hit damage (carries the same "extends Projectile
  directly, no inherited guard" comment pattern as LlamaSpit, below).
- `FireworkRocketEntity.java:205,231` — firework damage (rider-attached and AOE-on-explode
  branches).
- `Ravager.java:308` *(damage half only — the push/knockback half already covered in B.6.7.7)* —
  via `roar()`'s AOE.
- `Guardian.java:308` — laser-attack damage.
- `LlamaSpit.java:61-64` — **genuinely already fixed in the real repo**, resolving the apparent
  discrepancy from B.6.7.6/.7: this is exactly the port gap that produced this session's live
  `actuallyHurt` violations (`LlamaSpit.onHitEntity → LivingEntity.hurt → Wolf.actuallyHurt`, 47
  violations captured earlier). Real repo comment explains why: "extends `Projectile` directly...
  never inherited [`AbstractArrow`'s] foreign-hit guard, same gap `ShulkerBullet` had." NeoForge's
  `LlamaSpit.java` has zero Mixins — a pure port gap, not a reference defect.
- `SonicBoom.java:69` — Warden sonic-boom attack damage.
- `Llama.java:395` — llama kick/spit-trigger interaction damage.
- `AbstractHorse.java:298` — horse kick damage.
- `EnderDragon.java:417,434` — dragon body-collision and breath-attack damage.
- `FallingBlockEntity.java:242` — landing-on-entity damage. The *original*, much-earlier B.6.7
  audit flagged this as "has an unused `redirectIfForeign` call" — confirmed now: it is genuinely
  wired and used; that earlier note is stale.
- `Player.java:1151-1189` (`Player.attack()`) — melee damage, knockback, and sweep-AOE
  damage/knockback, all via `redirectFromMainThread` specifically because the player runs on
  main, not a `RegionThread` (`redirectIfForeign`'s `Thread.currentThread() instanceof
  RegionThread` check can't fire for a main-thread caller) — the original 2026-08-27 full-core-
  audit P0 fix, already shipped and live-tested (see `player-region-ownership-architecture.md`).

### 2. Real production bugs in the reference repo

**None found in this category.** Every damage/hurt/knockback/kill/discard producer checked
already has a correct, shipped reference-side fix — unlike B.6.7.7, which found two genuine
unfixed reference bugs (`Boat`/`AbstractMinecart`, tracked separately, #138-140).

### 3. Not producers

None identified in this pass — every site checked was a genuine, distinct producer (unlike
B.6.7.7, which had three downstream-plumbing false positives).

### Status

Audit complete, nothing implemented. Core primitive (`EntityMutationHelper`, `EntityMutationOp`)
confirmed byte-for-byte identical in the port; `EntityMutationDispatcher` has one pre-existing,
correct 1.21.1 API adaptation (`Entity.setSecondsOnFire(int)` → `setRemainingFireTicks(int)`,
ticks = seconds × 20, from earlier Phase B.1.2 bring-up work) and is otherwise verbatim. This
whole category — 13 sites, one shared primitive — is ready for the exact same port treatment as
`LivingEntity.doPush` (B.6.7.6): no new design, no reference-side work needed, straightforward
Mixin wiring per class, whenever authorized.

## B.6.7.8 implementation — 13-site EntityMutationHelper port: PASS, stale-race proven

Implementation delegated (as a fork, mechanical/repetitive per-site work) then independently
reviewed against the real repo before any live test — this review caught one real bug the fork
introduced, fixed before proceeding.

**12 of 13 sites ported.** `ThrownPotion`, `EvokerFangs`, `AreaEffectCloud`,
`FireworkRocketEntity`, `Ravager` (damage half only), `Guardian`, `LlamaSpit`, `Llama`,
`AbstractHorse` via `@Redirect` on the specific mutating call (structurally low-risk — `@Redirect`
doesn't duplicate surrounding control flow, so a correctly-scoped redirect can't diverge from
reference branch structure by construction). `ShulkerBullet`, `EnderDragon` (`knockBack`+`hurt`),
`FallingBlockEntity` via `@Overwrite` — `ShulkerBullet` and `EnderDragon` because the reference
restructures a compound conditional (post-attack enchant effects only on the direct path);
`FallingBlockEntity` because `javap` showed its existing lambda is `static` (no `this` capture),
so `@Redirect` can't inject a `this`-dependent guard into it. `SonicBoom` deliberately deferred —
nested two lambda-levels inside a `Behavior.tick()` override, judged not worth a fragile Mixin for
the lowest-frequency site in the batch (matches the real repo's own "lower severity, deferred"
call on this site's push half).

**Review caught a real bug before any live test.** `ShulkerBulletMutationMixin`'s first draft
nested the levitation-effect application *inside* the damage `else` branch — meaning a
foreign-redirected hit would apply damage correctly but silently skip the levitation effect
entirely. Reading the real repo's exact structure (`ShulkerBullet.java:266-300`) showed the
levitation check is a **separate, unconditional `if (flag)` block after** the damage if/else, not
nested inside it — the reference sends the levitation effect via its own independent
`redirectIfForeign` call regardless of which path produced `flag=true`. Fixed to match exactly.
This is precisely the "never rewrite a heavily-branched vanilla method casually" risk this whole
document has flagged repeatedly — caught here by direct comparison against the reference source
before it ever reached a live server, not by a live symptom.

**Live acceptance, full gate:**
1. **All 12 Mixins applied cleanly** at boot, 0 `FATAL`.
2. **Same-region / general regression**: 0 `ConcurrentModificationException`, 0 tick errors,
   server stayed responsive throughout an ~7-minute run under a 921-entity multi-region cluster
   plus 3 directly-summoned `area_effect_cloud` instant-damage clouds at the boundary and one at
   an isolated control site.
3. **Cross-region producer genuinely exercised**: 66 real `LlamaSpit` throws occurred (llamas
   provoked by the mixed-mob cluster's own AI) — the exact producer that caused 47 live violations
   pre-port. **Zero** violations traced to `LlamaSpit` this run (down from 47).
4. **Stale-ownership race — not just inferred, put under real pressure**: 2,524 `EntityTransfer`
   ownership changes occurred during the run (very high churn — the same boundary hotspot used
   throughout this session), giving ample natural opportunity for a message sent to one owner to
   arrive after the target transferred to another. **Zero** `"dropping ... after N re-route hops"`
   warnings — meaning whatever reroutes occurred (mechanically guaranteed to occur at this churn
   rate, per `EntityMutationDispatcher.applyQueued`'s unconditional re-check on every drain)
   resolved within the bounded-hop limit, not silently lost and not exhausting the safety valve.
5. **Exhaustive attribution of the remaining 32 violations** (same methodology that closed
   `doPush`): grepped every one of the 12 newly-ported classes' names against the full violation
   log — **zero matches for any of them**. The 32 resolve to exactly two, already-understood,
   out-of-scope sources: (a) 24 via `Entity.push` — the already-tracked, separately-gated
   `Boat`/`AbstractMinecart` reference-repo bug (#138-140, explicitly not part of this batch); (b)
   the remaining ~8 all trace to a single incident — `IronGolem.doHurtTarget()` melee-attacking a
   Llama, cascading through `Animal.actuallyHurt`/`LivingEntity.knockback`/a position-resync — a
   genuinely different, not-yet-audited producer (falls under "AI-driven cross-entity mutations,"
   the user's own category 8, not yet started). Neither is a regression from this port.

**B.6.7.8 implementation — PASS.** Reference parity confirmed (after one caught-and-fixed bug),
same-region/regression clean, cross-region producer (`LlamaSpit`) proven live at 0 violations
against a pre-port baseline of 47, and the bounded-reroute stale-ownership path put under genuine
sustained pressure (2,524 transfers) with zero safety-valve exhaustion and zero attributable
violations — not a same-region-only smoke test. `EntityMutationHelper`/`EntityMutationOp`/
`EntityMutationDispatcher` untouched throughout, and the `EntityPush`-vs-`EntityMutate` reroute
asymmetry was not touched or unified, exactly as directed. `SonicBoom` remains open, deferred by
explicit judgment call, not forced.

## B.6.7.9 — Collision/Movement-Induced Mutation Audit (audit only)

Category 2 of the entity-mutation-boundary series. Every collision/movement call chain followed
down to its actual mutation sink, not stopped at `move()`/`collide()` themselves — per explicit
direction not to assume `move()` = producer.

### 1. Already covered by reference architecture

None. Collision/movement itself introduces no *new* `EntityMutationHelper`-routed producer —
every push- or damage-shaped effect reachable from movement resolves into a chain already
classified under B.6.7.6/.7/.8 (see category 3).

### 2. Real production bugs in the reference repo (NOT a port gap)

**`PistonMovingBlockEntity.moveEntityByPiston()`** (`net/minecraft/world/level/block/piston/
PistonMovingBlockEntity.java:175-179`), called from `moveCollidedEntities()` (`:103-172`) and
`moveStuckEntities()` (`:181-194`): both scan `level.getEntities((Entity)null, <piston movement
area>)` — an unfiltered, ownership-blind broad-phase query — then call `entity.move(MoverType
.PISTON, ...)` directly on every entity caught in the piston's path. `Entity.move()` internally
reaches `setPos`/`setPosRaw` — an `EntityOwnershipGuard`-guarded chokepoint, confirmed wired since
the B.6.7 implementation gate — meaning a foreign entity caught in a piston's push zone trips the
exact same guard class `doPush`'s and `Boat`/`AbstractMinecart`'s violations did, with zero
existing protection. Confirmed via exhaustive grep: **zero `nestworld` references anywhere in
`net/minecraft/world/level/block/piston/`** (`PistonBaseBlock`, `PistonMovingBlockEntity`,
`PistonHeadBlock`, `MovingPistonBlock`, `PistonStructureResolver`, `PistonMath`) — this mechanism
has never been touched.

**Distinct from** the already-tracked `piston-cross-region-atomicity-open-design-issue` memory
finding — that one is about the piston's own *block-state* cascade atomicity across a region
boundary; this is about the piston *mutating a foreign entity's position*, a different mechanism
(block-owning thread → possibly-foreign entity), same shape as the already-tracked `Boat.push`/
`AbstractMinecart` findings (#138-140). Tracked as a sibling upstream defect, not folded into the
existing piston atomicity issue and not a NeoForge port gap.

### 3. Not producers (downstream plumbing, or already classified elsewhere)

- **`Entity.move()`/`collide()`/`collideBoundingBox()`** (`Entity.java:612-915`) — zero
  `nestworld` references in these bodies. `move()`'s own block callbacks —
  `block.updateEntityAfterFallOn(...)` (`:672`) and `block.stepOn(...)` (`:676`) — operate on
  `this` (the moving entity itself) only; pure self-mutation, no cross-region angle. The one thing
  `collide()` reads cross-region-unsafely (`getEntityCollisions`/foreign bounding boxes) is
  already B.6.7.4/.5 territory.
- **Block collision callbacks** (magma/cactus/sweet-berry-bush/honey/powder-snow damage,
  `stepOn`/`entityInside` overrides) — all self-damage the entity passed into the callback
  (always `this`, the one colliding), zero `nestworld` patches, and vanilla has no cross-entity
  effect for any of these that would make them a foreign-mutation producer.
- **Suffocation** (`LivingEntity.isInWall()` → `LivingEntity.java:347`) — self-damage only; an
  entity always safely owns itself, no cross-region hazard by construction.
- **Vehicle/passenger movement** — zero dedicated mutation beyond normal position-following
  (`positionRider`-class methods have zero `nestworld` patches). The real cross-region hazard here
  is **already tracked** as task #35 ("Deferred: design composite fix for `Entity.startRiding()`
  cross-region passenger-vehicle linkage") — not a new finding, the user's own category 7
  territory, already captured.
- **`BellBlock`**-style entity-notifying blocks — cross-entity effect fires on interact/redstone-
  ring, not movement/collision; out of scope, zero patches either way.
- **General entity-vs-entity collision resolution beyond push** — none found beyond the
  already-audited `doPush`/`pushEntities` family (B.6.7.6/.7).

### Verdict

Collision/movement is a narrow category once traced to actual mutation sinks: almost everything
terminates in self-mutation (no cross-region angle) or in a producer already classified elsewhere
(B.6.7.4/.5's read path, B.6.7.6/.7's push family, task #35's vehicle/passenger item). Exactly
**one** genuine new finding: `PistonMovingBlockEntity.moveEntityByPiston()`, real and unfixed in
the reference repo, same severity class as `Boat`/`AbstractMinecart`, tracked separately (task
below), not a port gap.

## B.6.7.10 — Mount/Dismount Audit (audit only)

Category 3 of the entity-mutation-boundary series. No code touched.

**Headline finding.** Mount/dismount fully coincides with the already-confirmed, already-deferred
task #35 / finding G4 (`docs/PHASE10_GLOBAL_STATE_AUDIT.md:17`, P1, CONFIRMED). No new
producer surfaced beyond G4's own scope — every path checked (AI mount, player-interact mount,
forced dismount, dimension-change dismount) collapses into the same one unguarded mutation sink.

### 1. Already covered by reference architecture

No new port candidate for the mount/dismount linkage itself. The adjacent-but-separate
`Llama`/`AbstractHorse` "fall-damage-to-passenger" mechanism is already covered — that's about
damage to a passenger from a fall, not the mount/dismount state itself, and was already classified
in B.6.7.8's own inventory (`Llama.java:395`, `AbstractHorse.java:298`).

### 2. Real production bugs in the reference repo (NOT a port gap)

**Task #35 / finding G4, reconfirmed by direct source read.** `Entity.startRiding(Entity,
boolean)` (`Entity.java:1903-1934`): `this.vehicle = p_19966_; this.vehicle.addPassenger(this);`
— mutates `this` (self, safe) AND `p_19966_` (a potentially foreign vehicle, via
`addPassenger()`) with **zero ownership check**. Symmetrically, `removeVehicle()`
(`:1951-1959`) calls `entity.removePassenger(this)` on a potentially foreign vehicle.
`ejectPassengers()` (`:1944-1949`) loops `stopRiding()` over every passenger, same chain.
Confirmed via exhaustive grep: **zero `nestworld` references** in any of these methods.

**Producer inventory, all reaching the same sink:**
- `Mount.java:21` — `startRiding(entity)`, AI-driven (piglin mounts hoglin), region-thread,
  fires every Brain-behavior tick, not just at spawn.
- `Minecart.java:28`, `Boat.java:747` — `entity.startRiding(this)` from `interact()` — a player
  mounting via interaction. **MAIN thread → region-owned entity**, and unlike `Player.attack()`
  (which has `EntityMutationHelper.redirectFromMainThread`), mount has **no analogous mechanism
  at all** — confirmed: no dispatcher on this path, a plain vanilla `interact() → startRiding()`.
- `AbstractHorse.java:525`, `SkeletonTrapGoal.java:46,54`, `Boat.java:334`,
  `AbstractMinecart.java:358` — further AI/environment-driven `startRiding` calls.
- `Entity.changeDimension()` (`:2469-2477`): `unRide()` (itself `ejectPassengers()`+
  `stopRiding()`, `Entity.java:283-289`) fires, but only after the region-thread guard already
  defers to `queueDimensionChange` (main-thread) — dimension-change forced dismount is **not a
  separate finding**, it's the same G4 chain reached by a different initiator, already implicitly
  bounded by the original B.6.7 audit's "`queueDimensionChange` not yet ported" note.

**Why deferred, not just "port gap"** (per `phase10-global-state-audit-fixes.md`): this isn't a
single collection/lock swap like G1/G3 — it needs an atomic two-entity state change, and if the
two entities belong to different regions, it's unclear whose region should "win" without
repeating the piston/BlockEntity atomicity mistake (splitting one logical operation into
independent messages). The project explicitly recommended cataloguing every call site first (now
done, above) before attempting a composite design.

### 3. Not producers / already in another scope

- `Entity.addPassenger`/`removePassenger` (`:1965-2001`) — internal plumbing called only from
  the already-classified `startRiding`/`removeVehicle`, not separate producers.
- Forced dismount on vehicle death/discard — not a separate producer, the same
  `stopRiding`/`ejectPassengers` chain already classified under #35.

### NeoForge port

Confirmed: **zero Mixins** touch `startRiding`/`stopRiding`/`addPassenger`/`removePassenger`/
`removeVehicle`/`ejectPassengers` anywhere in the port.

### Atomicity — what the reference actually does

The reference has **no atomic two-entity transaction**. `startRiding()` mutates self directly
(safe), then the foreign vehicle directly (unsafe) — sequential, non-atomic even in the reference
itself (`this.vehicle = p_19966_;` before `this.vehicle.addPassenger(this);`, two separate steps,
no rollback if the second fails). Confirms: any future composite-fix design must NOT invent new
distributed-transaction semantics — the reference itself has none at this level, it just relies on
"both steps on one thread" (fine for non-sharded/main-thread, breaks exactly when the two entities
are in different regions).

### Verdict

**B.6.7.10 — AUDIT PASS.** Mount/dismount fully matches the already-confirmed, deliberately-
deferred G4/task #35 — nothing new hiding underneath it. The producer inventory (7 call sites, all
one sink) is now documented — exactly the follow-up catalog project memory already recommended
before attempting a fix. Zero new category-B reference defects beyond the known G4. Zero new
category-A port gaps. Implementation NOT authorized, NOT attempted — remains a design-complexity
task requiring its own architectural decision (composite two-entity mutation), not a mechanical
port.

## B.6.7.11 — Teleport / Dimension Ownership Transfer Audit (audit only)

Category 4 of the series. No code touched.

**The audit's central question, answered directly from code, not inferred.** Does the existing
stale-ownership mechanism already cover explicit-teleport-driven ownership change the same way it
covers ordinary boundary-crossing? **Yes.** `BoundaryEntityTransfer.checkAndReassign()`
(`BoundaryEntityTransfer.java:79-146`) is a generic, position-based scan — it doesn't know or ask
*why* an entity's position changed (natural movement, `/tp`, ender pearl, chorus fruit — doesn't
matter), it just compares current chunk against current region owner every tick on the main
thread. `Entity.teleportTo(double,double,double)` (`Entity.java:2701`, same-dimension) calls
`moveTo(...)`, which has **zero `nestworld` patches** — no special teleport hook exists or is
needed, since the next `checkAndReassign()` pass naturally picks up the new position.
`EntityMutationDispatcher.applyQueued`/`EntityOwnershipRecheck.stillOwns()` query the same
ownership state with no "why did ownership change" special-casing either — one mechanism covers
both scenarios.

### 1. Already covered by reference architecture (known port gap, not new)

**`Entity.changeDimension()` → `queueDimensionChange`** (`Entity.java:2469-2472`) — the same gap
the original B.6.7 audit already found: a region-thread call correctly defers to a main-thread
queue (a documented deadlock fix, Stage 0.5 comment); `NestworldRegionSystem.queueDimensionChange`
exists with exactly one caller and **zero Mixins** in the NeoForge port touch
`changeDimension`/`teleportTo`/`PortalForcer`. Not a new finding — status unchanged since the
original audit.

### 2. Real production bugs in the reference repo (NOT a port gap)

**None found this pass.**

### 3. Not producers / already in another scope

- **`Entity.teleportTo(ServerLevel, ...)`** (7-arg, cross-dimension without a portal,
  `Entity.java:2674-2695`) — looks unguarded at first glance (zero `nestworld` guard, unlike
  `changeDimension`), but traced to its actual callers: **exactly 2 in the whole real repo, both
  player-specific** (`ServerGamePacketListenerImpl.java:1163` — spectator-slot-follow;
  `ServerPlayer.java:1377` — camera-teleport). Since `BoundaryEntityTransfer.processEntity()`
  explicitly excludes `Player` (`if (entity instanceof Player) return;`,
  `BoundaryEntityTransfer.java:157`) — players are never ticked by a `RegionThread` for ownership
  purposes, so the deadlock class `changeDimension` guards against is structurally impossible
  here. **Not a gap** — correctly uncovered by design, not oversight.
- **`ServerPlayer.changeDimension()`** (`ServerPlayer.java:720-744`) — a full override with no
  delegation to `super`, zero `nestworld` guard. Same argument: always runs on the main
  (network/server-tick) thread for a player, never from a `RegionThread` — not a gap.
- **Vehicle passenger teleport** — when a vehicle teleports, `unRide()` fires before the move
  itself (in both `changeDimension` and `teleportTo`) — this is literally the same G4/task #35
  logic (`ejectPassengers`/`stopRiding`) already classified in B.6.7.10. Not a separate problem.
- **Owner reassignment / entity transfer** — the single mechanism, `BoundaryEntityTransfer`,
  described above, handles both natural boundary-crossing and explicit teleport — not two
  separate mechanisms.
- **Portal-specific path** (`PortalForcer`/`PortalShapeChecker`) — zero `nestworld` patches, goes
  through the same `changeDimension`, not a separate path.

### Verdict

**B.6.7.11 — AUDIT PASS.** The audit's central architectural question is closed with proof: the
stale-ownership race for explicit teleport is the same, already live-proven (B.6.7.6/.8)
mechanism, not a new race class. One already-known port gap (`changeDimension`/
`queueDimensionChange`, category A, not new). Zero new category-B reference defects. Two
suspicious-looking paths (`teleportTo(ServerLevel,...)`, `ServerPlayer.changeDimension`) traced to
completion and correctly classified as "not a producer" via the players'-excluded-from-ownership
design — not left as an assumption.

## B.6.7.12 — Entity Lifecycle Audit (audit only)

Category 5 of the series. No code touched.

**Headline finding — a new, real reference-repo defect.** When a zombie kills a villager,
`LivingEntity.die()` (`LivingEntity.java:1303-1332`) runs on the DYING entity's (villager's)
thread and calls `entity.killedEntity(serverlevel, this)` (`:1323`), where `entity` = the killer
(zombie, from `DamageSource.getEntity()`) — so `Zombie.killedEntity(...)` (`Zombie.java:382-404`)
executes **on the villager's thread**, even though `this` inside that method is the zombie.

- `this.random.nextBoolean()` (`Zombie.java:385`) and `this.isSilent()` (`:397`) — reads of the
  ZOMBIE's own fields, from the VILLAGER's thread. The zombie may belong to a different region.
- `villager.convertTo(EntityType.ZOMBIE_VILLAGER, false)` (`:389`) is safe — that's `this` in
  `die()`'s own context (the villager), on its own thread; inside `Mob.convertTo()`
  (`Mob.java:1205-1249`), `this.level().addFreshEntity(t)` and `this.discard()` both run on the
  villager's own thread and are already covered by existing primitives (B.6.6.4's
  `ServerLevelAddFreshEntityRedirect`, `EntityOwnershipGuard.check(this, "setRemoved(...)")`).

So the conversion itself (spawn+discard) is safe self-mutation on the correct thread, already
covered — but the **read of the killer entity's own fields inside this callback is unguarded, with
zero `nestworld` patches** anywhere in `Zombie.java`/`ZombieVillager.java`/`Mob.convertTo()`/
`Villager.java`. Confirmed: zero Mixins in the NeoForge port touch `Zombie`/`killedEntity`/
`convertTo`. This is a **read hazard**, distinct from the known write hazards
(setDeltaMovement/setPosRaw/actuallyHurt) — same class of problem as B.6.7.1/.2's "shared
non-thread-safe vanilla structures," but at the level of one entity's own fields rather than a
shared storage structure.

### 1. Already covered by reference architecture

No new port candidate for entity-replacement itself (the spawn+discard half is already covered).

### 2. Real production bugs in the reference repo (NOT a port gap)

**`Zombie.killedEntity()`'s foreign-field read** (`Zombie.java:385,397`), described above. A
real, unported, unguarded read hazard in the reference repo. Not touched in the current NeoForge
track per standing rule — recommend a separate upstream task, sibling of #138/#139/#140/#143.

### 3. Not producers / already covered / out of scope

- **Spawn** (`Entity.addFreshEntity`) — already ported and proven in B.6.6.4, nothing new around it.
- **Kill/Discard** (`Entity.kill()`/`discard()`) — already `EntityMutationOp.Kill`/`Discard`,
  part of the proven B.6.7.8 primitive. All self-conversion calls (`Zombie.convertToZombieType`
  → Drowned, `ZombieVillager.finishConversion` → Villager, `AbstractPiglin` → ZombifiedPiglin,
  `Husk.convertToZombieType` → Zombie) are pure `this.convertTo(...)` self-mutation on their own
  thread — no cross-region scenario, not a producer.
- **`checkTotemDeathProtection`** (`LivingEntity.java:1217-1249`) — all mutations (`setHealth`,
  `removeAllEffects`, `addEffect`) on `this` (self), called from inside the already-guarded
  `actuallyHurt` chain. Not a producer.
- **Section registration/removal** (`PersistentEntitySectionManager.java`) — structural level
  (which section holds an entity), not ownership level; ownership is tracked separately via
  `WorldGrid`/region owned-ID sets. Concurrency safety already covered by
  `ConcurrentEntitySectionStorage`/`ClassInstanceMultiMap` fixes this session. No new ownership
  bypass found.
- **Chunk unload/load** — no separate ownership-specific mechanism found beyond the
  already-audited concurrency/tracking layer.
- **Entity transfer** — `BoundaryEntityTransfer`, already deeply covered in B.6.7.11.
- **Player lifecycle** (login/logout/respawn) — players excluded from `RegionThread`-based
  ownership (per B.6.7.11's `BoundaryEntityTransfer.processEntity` `Player` exception) — the
  rule holds here too, no exception found.

### Verdict

**B.6.7.12 — AUDIT PASS with one new reference defect.** Entity lifecycle itself is a narrow
category: spawn/kill/discard already covered by existing primitives, section-management is purely
structural, player lifecycle consistent with the already-established player-exclusion rule. One
new, real, precisely documented read hazard found in `Zombie.killedEntity()`'s villager
conversion — category B (reference defect), NOT a port gap, recommended as a separate upstream
task.

## B.6.7.13 — Projectile/Impact Producer Completeness Audit (audit only)

Category 6 of the series. No code touched.

**Headline finding — one elegant primitive covers almost everything, and it's entirely
unported.** `net.nestworld.region.ProjectileImpactGuard.isForeignHit(projectile, hitResult)`
(byte-for-byte identical in the port, `diff` exit 0) is the **single, ready guard** sitting at 4
base dispatch points in the real repo: `AbstractArrow.java:188`, `AbstractHurtingProjectile
.java:69`, `ThrowableProjectile.java:62`, `FishingHook.java:245`. Every checked subtype inherits
the protection automatically, no patch of its own: `Arrow`/`SpectralArrow`/`ThrownTrident`
(via `AbstractArrow`), `SmallFireball`/`LargeFireball`/`WitherSkull` (via
`AbstractHurtingProjectile`), `ThrownEnderpearl`/`ThrownExperienceBottle`/`Snowball`/
`ThrownEgg`/`ThrownPotion`'s direct-hit half (via `ThrowableProjectile`). Design rationale
straight from the javadoc: "safe-skip, retry next tick" — the same pattern as the melee-attack-
goal fix (task #53), deliberately chosen over an `EntityMutationHelper` redirect because the
surrounding conditional logic (`hurt()` → conditional `push()`/`setArrowCount()`/enchant procs)
is too tightly coupled to decompose cleanly.

**NeoForge port: zero Mixins touch any of these 4 base classes.** This is NOT "5 sites already
covered, find the rest" — it's "ONE primitive covers ~10+ projectile types, and none of them are
ported" (`LlamaSpit`/`ShulkerBullet` are not typical examples — they're exactly the two exceptions
that DON'T inherit this guard, since they `extends Projectile` directly, which is exactly why
they needed their own separate B.6.7.8 patches).

### 1. Already covered by reference architecture (port gap, ready to port)

- **`ProjectileImpactGuard` dispatch at all 4 base classes** — reference has a complete solution,
  NeoForge port has zero. Candidate for a compact, mechanical port (4 Mixin points instead of
  ~10+ individual ones).
- `ThrownPotion`'s AOE half (`applyWater`/`applySplash`) — already ported in B.6.7.8, confirmed,
  not duplicated here.

### 2. Real production bugs in the reference repo (NOT a port gap) — new finding

**`FishingHook.pullEntity()` via `retrieve()`** (`FishingHook.java:403-409,461-467`), called from
`FishingRodItem.use()` (`FishingRodItem.java:23`) — a player reeling in their fishing line. This is
**MAIN thread (player) → `hookedIn.setDeltaMovement(...)`** on a potentially foreign region (the
hooked entity — a fish, squid, another player), **with zero `ProjectileImpactGuard`/
`EntityMutationHelper` protection**. `ProjectileImpactGuard` only guards the `tick()`-based
`checkCollision()` path (the hook catching something), not the separate `retrieve()` path (the
player pulling in what's already caught). The other `pullEntity()` call (`handleEntityEvent`,
`:452-454`) is client-side-only (`this.level().isClientSide`-gated), not server-relevant. A real,
unported, unguarded producer — category B, separate upstream task, sibling of #138/#143/#147.

### 3. Not producers / already covered / out of scope

- `ThrownEnderpearl.onHitEntity()`'s `hurt()` call — already protected upstream via
  `ThrowableProjectile.tick()`'s `isForeignHit`, not a separate problem.
- `ThrownEnderpearl.onHit()`'s teleport effect — mutates `this.getOwner()` (the throwing player),
  always self/main-thread, not a cross-region producer.
- `Snowball`/`ThrownEgg` — don't mutate a foreign entity directly (egg-spawns-chicken happens on
  the thrower's own thread, not a producer).
- `ThrownExperienceBottle` — doesn't mutate an entity directly (XP orbs), not a producer.

### Separate note — NeoForge-only feature, neither a reference defect nor a port gap

**`WindCharge`/`BreezeWindCharge`** (1.21+ feature) **does not exist in the real 1.20.1-based
repo at all** (confirmed: `WindCharge.java`/`BreezeWindCharge.java`/`AbstractWindCharge.java`
absent). No reference solution to compare against — not invented here. Classification:
**out-of-reference-scope, NeoForge-only feature**, a separate note, not A/B/C.

### Verdict

**B.6.7.13 — AUDIT PASS.** Most important result: `ProjectileImpactGuard`'s four dispatch points
are a much wider port gap than the 5 already-B.6.7.8-covered sites suggested — one mechanical
port covers essentially all standard projectiles. One new reference defect
(`FishingHook.pullEntity`/`retrieve()`) — category B, separate upstream task. `WindCharge`
correctly classified as neither a gap nor a defect (nothing to compare against).

## B.6.7.13 implementation — `ProjectileImpactGuard` 4-site port: PASS

Ported `ProjectileImpactGuard.isForeignHit` to all 4 base dispatch classes: `AbstractArrow`,
`AbstractHurtingProjectile`, `ThrowableProjectile`, `FishingHook` — covering `Arrow`/
`SpectralArrow`/`ThrownTrident`, `SmallFireball`/`LargeFireball`/`WitherSkull`,
`ThrownEnderpearl`/`ThrownExperienceBottle`/`Snowball`/`ThrownEgg`, and `FishingHook`'s
catch-detection half, in one mechanical pass.

**Real vanilla API difference caught before it mattered.** NeoForge 1.21.1's `AbstractArrow`/
`AbstractHurtingProjectile`/`ThrowableProjectile.tick()` route the hit through
`Projectile.hitTargetOrDeflectSelf(HitResult)` (returns a `ProjectileDeflection`, part of the
newer deflection mechanic), not 1.20.1's direct `onHit(hitresult)` call — confirmed by reading
both decompiled bodies before writing any Mixin, not discovered via a failed test. Skipping for a
foreign hit returns `ProjectileDeflection.NONE` (a no-op), which correctly keeps
`AbstractArrow.tick()`'s loop from breaking — matching "arrow keeps flying, retry next tick"
exactly. `FishingHook.checkCollision()` still calls `onHit(hitresult)` directly, unchanged shape.

**A real Mixin-mechanics lesson, hit and fixed before boot.** The initial version declared each
Mixin class with no `extends`, using an explicit `self` parameter to call the protected
`hitTargetOrDeflectSelf`/`onHit` — failed to compile (`has protected access in Projectile`),
since Mixin classes are checked by plain `javac` before any bytecode merge, and an unrelated class
calling a protected inherited method isn't legal Java. Fixed by declaring each Mixin
`extends Projectile` (the actual shared superclass) with a dummy, never-invoked constructor —
the standard idiom for this exact situation. First attempt at THAT fix used `extends
<the-target-class-itself>` (e.g. `extends AbstractArrow`), which Mixin's own transformer rejected
outright at boot (`Super class ... was not found in the hierarchy of target class` — Mixin
requires the mixin's superclass to match the target's actual superclass, not the target itself).
Corrected to `extends Projectile` for all 4 — applied cleanly after that.

**Live verification.** All 4 Mixins applied, 0 `FATAL`. Dense multi-region cluster (turtle/wolf/
villager) plus 40 arrows + 15 small fireballs + 15 snowballs dropped directly onto it from above
(confirmed real physics, not just spawns: at least one arrow embedded in the ground/an entity
post-test). 0 CME, 0 tick errors, server stayed responsive. 2,547 ownership transfers occurred
during the run (ample stale-ownership opportunity, same churn class as B.6.7.6/.8's proofs). Of
22 total violations, **zero** attributable to any of the 4 newly-guarded classes — grepped
`AbstractArrow`/`AbstractHurtingProjectile`/`ThrowableProjectile`/`FishingHook` against every
violation's full stack trace, all zero. The 22 resolve entirely to already-known, out-of-scope
sources: `Entity.push` (the tracked `Boat`/`AbstractMinecart`/piston defects, #138-140/#143) and
one `IronGolem.doHurtTarget`/`MeleeAttackGoal` melee incident (a separate, not-yet-audited
producer, same as observed in B.6.7.8's run).

**B.6.7.13 implementation — PASS.** `FishingHook.pullEntity()`/`retrieve()` (task #149) and the
new `WindCharge`-class projectiles (no reference to port from) remain explicitly untouched, as
scoped.

## B.6.7.14 — AI-Driven Melee Attack & Combat Producer Completeness Audit (audit only)

Categories 7 (AI/entity interaction) + 8 (Combat) merged, since they share one unresolved signal.

**Central answer — resolves a signal seen twice live already.** The `IronGolem.doHurtTarget`/
`MeleeAttackGoal` violations observed twice this session (in B.6.7.8's and B.6.7.13's live runs)
are **not a reference defect — a clean, ready category-A port gap.** The real repo already has
the same "safe-skip" guard pattern as `ProjectileImpactGuard`/`EntityPushHelper`, but on the
CALLER side (not inside `doHurtTarget()` itself).

### 1. Already covered by reference architecture (port gap, ready to port)

The guard `!(Thread.currentThread() instanceof RegionThread) || region.ownsEntity(target)` sits
at the caller side and covers:
- `MeleeAttackGoal.checkAndPerformAttack` (`MeleeAttackGoal.java:150-152`) — **exactly the path
  `IronGolem` uses** (`IronGolem.java:68`, `goalSelector.addGoal(1, new MeleeAttackGoal(...))`) —
  the twice-observed `IronGolem` violations are explained by the NeoForge port simply having
  **zero Mixins for `MeleeAttackGoal`** at all, not a reference gap.
- `ai/behavior/MeleeAttack.java:23-24` (Brain AI variant, same pattern).
- `OcelotAttackGoal.java:62-63`, `Vex.java:257-258`, `Blaze.java:183-184`,
  `PolarBear.java:291-292`, `Fox.java:1027-1028,1148-1149`,
  `animal/frog/ShootTongue.java:80-81`, `Ravager.java` (already covered in B.6.7.8's inventory).
- Zero Mixins in the NeoForge port for any of these — the whole set is one mechanical port
  candidate, same shape everywhere.

### 2. Real production bugs in the reference repo (NOT a port gap) — new finding

**`Phantom`'s own attack-goal tick, `Phantom.java:476-479`.** Phantom's internal (anonymous)
attack goal (not `MeleeAttackGoal` — its own bounding-box-based attack logic) calls
`Phantom.this.doHurtTarget(livingentity)` directly, **with zero `nestworld` guard.** A genuinely
distinct caller from everything already guarded — Phantom's own custom-attack mechanism, which
the earlier Stage 4.4 audit apparently didn't cover. Real, unported, unguarded producer —
category B, separate upstream task, sibling of #138/#143/#147/#149.

### 3. Not producers / already covered / out of scope

- `Husk`/`Zombie`/`WitherSkeleton`/`Panda`/`CaveSpider`'s own `doHurtTarget` overrides
  (`Husk.java:48-52`, `Zombie.java:314-318`, `WitherSkeleton.java:88-92`, `Panda.java:319`,
  `CaveSpider.java:30-33`) — all call `super.doHurtTarget(...)` and add ONLY side-effects
  (hunger/fire/poison/bite) after success; the "whether to call at all" decision is already made
  upstream (in `MeleeAttackGoal` or equivalent) — downstream plumbing, same category as
  `LivingEntity.push`/`Slime.push` from B.6.7.7.
- `Player.attack()` — confirmed a separate, already well-covered path
  (`PlayerAttackDispatcher`/§41.C), not the same mechanism as mob-vs-mob melee.
- Target selection (`Mob.setTarget`) — read/assignment only, not a foreign mutation, not a producer.
- Status effects/AOE/kill — already `EntityMutationOp`, not duplicated here.
- Death-triggered mutations beyond the Zombie conversion (`WitherBoss`/`EnderDragon`) — no
  separate foreign-mutating death callback found; not a producer.

### Verdict

**B.6.7.14 — AUDIT PASS.** The `MeleeAttackGoal` family (9 sites, one pattern) is category A, a
ready mechanical port gap directly explaining the twice-observed `IronGolem` violations. One new
reference defect: `Phantom`'s own attack tick — category B, separate upstream task.

## B.6.7.14 implementation — `MeleeAttackGoal` family port: BLOCKED / PARTIAL (reference-repo defect found)

Ported the same guard shape to 8 of the 9 sites (`MeleeAttackGoal`, `OcelotAttackGoal`,
`ShootTongue`, `Vex.VexChargeAttackGoal`, `Blaze.BlazeAttackGoal`,
`PolarBear.PolarBearMeleeAttackGoal`, `Fox.FoxMeleeAttackGoal`, `Fox.FoxPounceGoal`) — each
checks `!(Thread.currentThread() instanceof RegionThread rt) || rt.getRegion().ownsEntity(target
.getUUID())` before calling `doHurtTarget`, skip otherwise. `ai/behavior/MeleeAttack.java`'s
Brain-AI variant deferred — its `doHurtTarget` call sits inside a deeply nested
`BehaviorBuilder`/`TriConsumer`-style lambda chain, the same class of fragility that deferred
`SonicBoom` in B.6.7.8; not forced.

Two real Mixin-mechanics notes from writing these: several of the target inner classes
(`Vex$VexChargeAttackGoal`, `Blaze$BlazeAttackGoal`, `PolarBear$PolarBearMeleeAttackGoal`,
`Fox$FoxMeleeAttackGoal`) are package-private nested classes — not referenceable via a normal
Java import from `net.nestworld.adapter.mixin`, so each used `@Mixin(targets = "...")` string-based
targeting instead of a class literal (the standard technique for this exact situation).
`PolarBearMeleeAttackGoal`/`FoxMeleeAttackGoal` each fully override `checkAndPerformAttack`
without calling `super`, so each needed its own separate Mixin — `MeleeAttackGoalMixin` alone
does not reach them.

**Live verification surfaced something that needs your classification before this can be called
PASS.** All 8 Mixins applied, 0 `FATAL`. Dense multi-region cluster with deliberately-summoned
`iron_golem` and other melee mobs. 0 CME, 0 tick errors, server responsive throughout. But: of 43
violations this run, several trace to `actuallyHurt`/`setDeltaMovement` fired **from inside**
`IronGolem.doHurtTarget()` itself (e.g. `IronGolem.java:199`'s `entity.hurt(...)` and
`:202`'s `entity.setDeltaMovement(...)`) — reached via `MeleeAttackGoal.checkAndPerformAttack`,
which **is** guarded by the newly-ported Mixin.

Tracing this precisely (not inferring from the stack trace alone, per this document's own
standing discipline): the outer guard at the `MeleeAttackGoal`/Goal level checks ownership
**once**, then — if satisfied — calls `doHurtTarget()`, which itself makes multiple further
mutating calls (`entity.hurt(...)`, then `entity.setDeltaMovement(...)`) with **no re-check
immediately before either of those inner calls**. If the target's ownership changes in the
(narrow) window between the Goal-level check and `doHurtTarget()`'s own internal mutations
completing, the inner calls are unguarded — and at this session's stress-test churn rate (2,542
ownership transfers in a few minutes), that narrow window was hit multiple times. Confirmed this
is not a porting mistake: the guard I ported matches the real repo's own check-then-act shape
exactly (`MeleeAttackGoal.java:150-153` in the real repo has the identical single check, no
recheck before `doHurtTarget()`'s internal calls either) — this is a structural property of the
"safe-skip, check once at the Goal level" pattern itself, not something my port introduced or
could fix without deviating from the reference's own design (`EntityMutationDispatcher`'s
different design — recheck immediately before every single mutation, at the actual apply site —
does not have this gap, but `MeleeAttackGoal`'s pattern is a different, older, coarser-grained
guard).

**Classification (user decision).** Not "an accepted narrow race" like `EntityPush`'s own
silent-drop asymmetry — that comparison doesn't hold: `EntityPush` is continuous physics (a missed
push is retried the moment the two entities are still close next tick, a transient, self-healing
difference); melee damage/knockback is a semantic one-shot operation — a target ownership
transfer between the Goal-level check and `doHurtTarget()`'s internal mutations produces an
*actual* foreign mutation, not a transient physics blip. And not merely "a design alternative"
either: the architecture already establishes a stronger property for one-shot mutations
(`EntityMutationDispatcher` validates ownership at the mutation-application boundary itself, with
bounded reroute) — `MeleeAttackGoal`'s caller-side, check-once-before-a-multi-step-method pattern
bypasses that stronger guarantee, and the live test proved the caller-side check insufficient to
maintain the ownership invariant for the operation's full duration. This is an **implementation
defect in the reference execution model**, not an arbitrary alternative design choice.

```
B.6.7.14
├── Audit:                       PASS
├── NeoForge mechanical port:    CORRECT
├── Live semantic validation:    FAILED
├── Root cause:                  REFERENCE-REPO DEFECT
├── NeoForge port gap:           NO
└── Final status:                BLOCKED / PARTIAL
```

The 8 already-ported guards remain valid as a mechanical port of the *current* reference
implementation — not reverted, not reworked into a NeoForge-only workaround. `EntityMutationDispatcher`
is not touched. Tracked as a separate upstream task (below), with its own required gate: reference
fix → reference live proof → reference ownership-churn proof → separate NeoForge port → NeoForge
live proof. The upstream task's job is to determine the correct atomicity boundary for a
multi-step entity interaction (candidates include per-mutation-sink rechecking, or an
execution/ownership lease spanning the whole attack) — not to be resolved here by picking
whichever makes this test pass. `ai/behavior/MeleeAttack.java`'s Brain-AI path remains deferred,
unrelated to this defect. `Phantom`'s own attack tick (task #152) remains a separate reference-repo
issue, not folded into this one.

## B.6.7.15 — World/Block → Entity Mutation Producer Audit (audit only)

Category 9 of the series. No code touched.

### 1. Already covered by reference architecture (port gap, ready to port) — 2 new findings

**`BeaconBlockEntity.applyEffects()`** (`BeaconBlockEntity.java:219-237`): scans `Player` in an
AABB via `getEntitiesOfClass`, then calls `player.addEffect(new MobEffectInstance(...))`
directly. Exactly the same "scan radius, mutate every entity found" AOE pattern
`EntityMutationHelper` already covers for `ThrownPotion`/`AreaEffectCloud` (B.6.7.8). Zero
`nestworld` references. The beacon ticks on the block-owning thread; players found nearby a
region boundary may be foreign.

**`ConduitBlockEntity`** — two separate AOE sites in one file:
- `applyEffects()` (`:165-180`) — `player.addEffect(CONDUIT_POWER)`, same Beacon pattern.
- `updateDestroyTarget()` (`:182-204`) — scans nearby hostile `LivingEntity` in water, then
  calls `destroyTarget.hurt(damageSources().magic(), 4.0F)` directly — AOE damage, not just an
  effect.

Both zero `nestworld` references, zero Mixins in the NeoForge port. Ready, mechanical port
candidates, identical shape to the 12 sites already ported in B.6.7.8.

### 2. Real production bugs in the reference repo (NOT a port gap)

None found this pass.

### 3. Not producers / already covered / out of scope

- **Fluids** (`Entity.lavaHurt()`, `updateFluidHeightAndDoFluidPushing()`) — read in full, both
  mutate only `this` (self fire+damage, self velocity via fluid flow), zero reference to a
  foreign entity anywhere in either body. Not a producer, same category as B.6.7.9's collision
  self-damage.
- **Portals** — `TheEndGatewayBlockEntity.teleportEntity()` already HAS `nestworld` patches
  ("NestWorld Stage 1g" comments, `:160-211`) — deferred to main thread, the same shape as
  `changeDimension`. Already covered, not a new finding.
- **Block destruction hooks** (`Block.playerWillDestroy`/`wasExploded`) — checked, no
  entity-mutating callback found beyond the already-covered Explosion/`FallingBlockEntity`.
- **`CampfireBlockEntity`** — no AOE scan (`getEntitiesOfClass`) in the file at all; campfire
  damage goes through the block-collision self-damage callback (`entityInside`), already
  covered in B.6.7.9.
- **`SculkShriekerBlockEntity`** — doesn't mutate existing entities directly; triggers a warden
  spawn via `addFreshEntity`, already covered by B.6.6.4.
- **`HopperBlockEntity`**/**`ShulkerBoxBlockEntity`**/**`BellBlockEntity`** — `getEntities` calls
  concern `ItemEntity` collection (Hopper) or notification-without-mutation (Bell — already
  excluded in B.6.7.14's own audit), not entity-mutation producers.
- **Dispenser-fired projectiles** — go through the already-ported `AbstractArrow`/
  `ProjectileImpactGuard` (B.6.7.13), not a separate mechanism.

### Verdict

**B.6.7.15 — AUDIT PASS.** Two new, real, precisely documented findings
(`BeaconBlockEntity.applyEffects`, `ConduitBlockEntity`'s two AOE sites) — category A, ready
mechanical port candidates, identical pattern to the already-successfully-ported B.6.7.8. Zero
new reference defects. Fluids/portals/campfire/sculk/hopper correctly excluded as either
not-a-producer or already covered.

## B.6.7.15 implementation — Beacon/Conduit AOE port: PASS

### Port

`BeaconBlockEntityMutationMixin` (1 site) and `ConduitBlockEntityMutationMixin` (2 sites), both
using `EntityMutationDispatcher.dispatch(target, op)` — same reasoning as B.6.7.13: both real-repo
methods (`BeaconBlockEntity.applyEffects`, `ConduitBlockEntity.applyEffects`/
`updateDestroyTarget`) are `private static`, no entity "self" to redirect from, and `dispatch()` is
the primitive explicitly designed for exactly this shape.

### Mixin-mechanics bug caught before live test, fixed, re-verified

First boot attempt (`b1319`) hit a `FATAL InvalidInjectionException` at Mixin-apply time (not
caught by `compileJava`, which only checks Java-level access, not Mixin's own static/non-static
contract): `@Redirect` handler methods targeting a call **inside a `static` target method** must
themselves be declared `static` — all three handlers
(`BeaconBlockEntityMutationMixin.nestworld$guardedAddEffect`,
`ConduitBlockEntityMutationMixin.nestworld$guardedAddEffect`,
`ConduitBlockEntityMutationMixin.nestworld$guardedHurt`) were first written as plain instance
methods. Added `static` to all three, recompiled clean, rebooted (`b1320`) — both Mixins now apply
with zero FATAL. New standing lesson for this project (alongside the `extends <shared-superclass>`
and `@Mixin(targets = "Outer$Inner")` lessons from B.6.7.13/14): **Mixin's static/non-static
handler contract is a Mixin-apply-time check, not a javac-time check — `compileJava` succeeding
proves nothing about it.**

### Live acceptance

Built a real, fully-active beacon (3x3 iron-block pyramid, powered with `primary_effect:
minecraft:strength` via `/data merge block`) and a real, fully-active conduit (42-block prismarine
frame — the exact `MIN_KILL_SIZE` "hunting mode" shape, generated from the real `updateShape()`
predicate rather than assumed from memory — inside a 5x5x5 water pocket) at the standard B.6.7.x
boundary-test cluster (`x=12, z=-40`), on top of the same already-running multi-region
ownership-churn stress test used by every prior checkpoint this segment (regions=18-40, 3091
`EntityTransfer` events over the test window, tick spikes 80-600ms confirming real contention, not
an idle world).

- **Beacon**: a real connected player (mineflayer bot, node22 runtime — `/root/node22/bin/node`,
  the system default `node` is v12.22.9 and mineflayer requires >=18) stood inside the beacon's AOE
  column for 20 x 5s cycles. `data get entity <bot> active_effects` mid-test confirmed
  `minecraft:strength` actually applied (`duration: 186, ambient: 1b`) — `BeaconBlockEntity.tick()`
  (running on whichever RegionThread owns that block position) successfully redirected the
  `player.addEffect()` call through `EntityMutationDispatcher.dispatch()` into a Player entity,
  which is itself never RegionThread-owned (B.6.7.11) — exactly the cross-thread mutation this port
  exists to guard.
- **Conduit**: 3 `drowned` summoned inside the water pocket, well within `KILL_RANGE=8`.
  `execute as @e[type=minecraft:drowned] run data get entity @s Health` showed one drop from
  `20.0f` to `12.0f` — exactly two `hurt(magic(), 4.0F)` hits, confirming
  `updateDestroyTarget()`'s AOE damage path fired through the same dispatcher without a crash.
- **Stability**: 0 exceptions/CME/FATAL in the full boot log.
- **Violation attribution**: 828 total `NO_FOREIGN_ENTITY_MUTATION violated` entries in the run;
  grepping every one's stack trace for `BeaconBlockEntity`/`ConduitBlockEntity` returns **0/828**.
  Full attribution breakdown of the 828 traces only to already-classified reference defects:
  `Entity.push`/`Boat.push`/`Boat.tick` (tasks #138-140), and the `MeleeAttackGoal`-family TOCTOU
  chain (task #153) — `MeleeAttackGoal.checkAndPerformAttack` → `Mob.doHurtTarget`/
  `IronGolem.doHurtTarget` → `LivingEntity.hurt`/`actuallyHurt`/`knockback`/`setDeltaMovement`,
  including `AbstractHorse.hurt` as a same-chain override (a `doHurtTarget`-reachable override, not
  a new/separate finding — same root cause as task #153).

**Verdict: B.6.7.15 — AUDIT + IMPLEMENTATION PASS.** Both sites ported mechanically, live-verified
against real activated structures (not just placed/idle block entities), zero regression.

## B.6.9 — Network/Packet-Originated Mutation Audit (audit only)

Category 10 of the mandate. Audited every server-side packet handler
(`ServerGamePacketListenerImpl` and delegates) for direct region-owned state mutation bypassing
ownership: movement, interact (attack/interact-at), use-item, use-item-on-block,
player-action (destroy-block, offhand-swap, release-use-item), player-command
(shift/sprint/sleep/inventory/riding-jump), move-vehicle, accept-teleportation, container-click.

### Already covered (no new audit needed)

The reference repo's Phase 11 (`#31`) player-execution-offload dispatchers are already ported
verbatim to `/root/neoforge-1211-mdk` (`PlayerAttackDispatcher`, `PlayerInteractionDispatcher`,
`PlayerUseItemDispatcher`, `PlayerUseItemOnBlockDispatcher`, `PlayerUseControlDispatcher`,
`EntityInteractionDispatcher`, `PlayerInputDispatcher`, matching `NestworldTuning` flags). Movement
(`PlayerInputDispatcher` — shadow mode only, real movement stays direct main-thread by deliberate
design), attack, interact, use-item, use-item-on-block, offhand-swap/release-use-item, and the
self-mutating `PlayerCommandPacket` actions (shift/sprint/sleep/inventory/fall-flying) and
`AcceptTeleportationPacket` are all **C** — already covered, Player is excluded from RegionThread
ownership (B.6.7.11), self-mutation only.

### New findings — 4 sites, one architectural problem, classification: DOCUMENTED DEFERRED GAP

Four packet-driven mutation sites have **zero existing nestworld coverage** and share one
structural shape: main-thread packet handler → direct mutation of an entity/block-entity that is
NOT the connecting player itself (a different, potentially region-owned entity or block):

- `ServerboundPlayerActionPacket` (`START_DESTROY_BLOCK`/`ABORT_DESTROY_BLOCK`/
  `STOP_DESTROY_BLOCK`) → `ServerPlayerGameMode.handleBlockBreakAction()`, unwrapped.
- `ServerboundContainerClickPacket` → `player.containerMenu` slot mutation, backed by a block
  entity's inventory (e.g. a chest), no per-click ownership re-check.
- `ServerboundMoveVehiclePacket` → `entity.absMoveTo(...)` on `player.getRootVehicle()` — a
  genuinely different entity than the player.
- `ServerboundPlayerCommandPacket` (`START_RIDING_JUMP`/`STOP_RIDING_JUMP`) →
  `player.getControlledVehicle()` mutation, same shape as `MoveVehiclePacket`.

**Root question, resolved by code evidence (no live race test needed) — introducing a new, third
classification type distinct from both "reference-repo defect" and "accepted design":
`DOCUMENTED DEFERRED GAP`.** The real 1.20.1 repo's `ServerGamePacketListenerImpl` has these exact
same 4 sites completely unguarded (`handleMoveVehicle`, `handlePlayerAction` destroy-block family,
`handlePlayerCommand` riding-jump, `handleContainerClick`) — so this is not a NeoForge-porting gap.
But critically, the reference repo's own class docs **explicitly name and defer** this exact
category, rather than leaving it silently unexamined or treating main-thread access as a deliberate
trust boundary:
- `PlayerInteractionDispatcher.java`: "Item-use-on-block, container opening, and inventory mutation
  are explicitly NOT covered here — separate future steps, each needing its own op-type design."
- `EntityMutationDispatcher.java`: "block/block-entity mutation stays out of scope — see
  `NestworldRegionSystem#nestworldDeferForeignBlockWrite` and the still-DEFERRED task for that."
  (`nestworldDeferForeignBlockWrite` itself only covers a RegionThread writing out-of-bounds, not a
  main-thread packet handler writing into a region-owned block-entity/vehicle — no equivalent
  exists for that direction.)

Semantics of `DOCUMENTED DEFERRED GAP`: the reference repository has a known, explicitly documented
execution/ownership gap, deliberately left unresolved pending a separate future op-type/ownership-
dispatch design — not a port gap, not accepted behavior, not blocking this adapter audit, and
**not to be worked around ad hoc in the NeoForge port** (the adapter must honestly preserve
reference semantics; the future ownership design belongs at the reference/core architecture level).
Filed as one meta-task (task #157) covering all 4 sites as sub-findings of a single architectural
problem (packet-originated mutation has no defined ownership-dispatch contract at all), not four
separate defect tasks.

**Verdict: B.6.9 — AUDIT PASS WITH DOCUMENTED DEFERRED GAP.** Not BLOCKED, not an unresolved
unknown for the final acceptance table — known, reference-confirmed, explicitly documented as
deferred, not accepted semantics, not a NeoForge port gap.

## B.6.10 — Chunk/section/storage access audit + port attempt: PARTIAL, one port CRASHED live, disabled

Category 11 of the mandate. Audit found one clean, well-evidenced category-A finding: the real
repo's "#3"/"#4" chunk-save race guards (`ServerLevel.save()` and `ChunkMap`'s opportunistic
per-tick save loop both pause free-running regions for the duration of a save, via
`NestworldRegionSystem.nestworldPauseFreeRunningRegionsForSave`/`nestworldResumeFreeRunningRegionsAfterSave`
— both already present in NeoForge's Core-copy, verbatim, unused) were not wired into NeoForge at
all. "#4" specifically fixes a real, once-confirmed production incident (a redstone self-clocking
loop froze mid-session with no `/save-all` and no demote event logged). `MapItemSavedData`'s
cross-region fix location and the 3rd (of 3) global-singleton race fix were not resolved this pass
— flagged as genuinely unresolved (not guessed at) rather than closed as A/B/C.

### Port attempt

`ServerLevelSaveGuardMixin` ("#3", wraps `ServerLevel.save()`) and `ChunkMapSaveGuardMixin` ("#4",
wraps `ChunkMap.processUnloads()`, private — required re-declaring the reference's own
`nestworldHasSaveCandidate`/`nestworldIsSaveCandidate` helper methods inside the Mixin via
`@Shadow`, adjusted for a genuine NeoForge 1.21.1 API difference caught by direct read, not
assumed: `ChunkHolder.getChunkToSave().getNow(null)` doesn't exist in this version —
`saveChunkIfNeeded()`'s actual NeoForge 1.21.1 body uses `wasAccessibleSinceLastSave() &&
isReadyForSaving()` + `getLatestChunk()` instead). Both compiled clean and applied live with 0
FATAL.

### Live test: CRASHED — real watchdog hang, not a timeout

With `NESTWORLD_FREE_RUNNING_REGIONS`/`NESTWORLD_AUTO_FREE_RUNNING` enabled (needed a new
`nestworld.autoFreeRunning` sysprop wired into `build.gradle`'s `runServer` config, matching the
existing `NESTWORLD_OWNERSHIP_ASSERTIONS` pattern, to actually exercise a real free-running region
rather than a no-op empty-list pause), 8 regions auto-promoted, and repeated explicit `/save-all`
calls under that load produced a **real `ServerHangWatchdog` crash** — the main thread stuck
inside `NestworldRegionSystem.nestworldPauseFreeRunningRegionsForSave` at the `CountDownLatch.await`
call, reported hung for far longer than that call's own 2000ms bounded timeout should ever allow.
8 explicit saves WITHOUT free-running regions active, and ~30 rendezvous-timeout fallbacks (the
Core method's own pre-existing, correct "didn't confirm in time, saving anyway" path) all worked
cleanly beforehand — the crash only appeared once "#4"'s per-tick call path and real free-running
regions were both active together under load.

**Root cause, identified by code inspection, not fully proven as the exact trigger (no exception
was logged immediately before the crash, so this is the strongest structural explanation found, not
a confirmed single smoking gun):** the reference repo's own pause/resume protocol
(`WorldRegion.nestworldCheckSaveRendezvous()`) has an **unbounded** `resume.await()` on the region's
side — by design, safe ONLY because the reference's "#3"/"#4" call sites wrap the paired
pause/resume calls in a genuine `try/finally`, guaranteeing resume always eventually fires. My
Mixin port used `@Inject(HEAD)` + `@Inject(RETURN)` bracketing instead of a true try/finally (this
project has no MixinExtras `@WrapMethod` in active use anywhere yet, though the dependency **is**
present transitively via NeoForge itself — `io.github.llamalad7:mixinextras-neoforge:0.5.3`,
confirmed on the runtime classpath, previously unknown to this project). `@At("RETURN")` only fires
on normal method exit, never on an exceptional one — so any exception unwinding through
`processUnloads()` between my HEAD and RETURN injections silently skips the resume call entirely,
permanently stranding a free-running region on its own unbounded wait. This is a **NeoForge-port
implementation defect in the Mixin technique chosen, not a reference-repo defect** (the reference's
own try/finally is correct) and not a `NO_FOREIGN_ENTITY_MUTATION`-class ownership bug.

### Action taken

`ChunkMapSaveGuardMixin` ("#4") **disabled** (removed from `nestworld.mixins.json`) immediately
after the crash was confirmed — not left active pending a fix. `ServerLevelSaveGuardMixin` ("#3")
left enabled: same HEAD/RETURN structural weakness in principle, but far smaller exposure (explicit
`/save-all` is rare; the per-tick "#4" path runs every tick and is what actually crashed in
testing) and has shown zero problems across every test this session including under active
free-running load. Recompiled and reconfirmed clean boot with "#4" removed.

**Verdict (superseded below): B.6.10 — audit PASS (1 clean finding + 2 correctly-flagged-unresolved
items); "#3" port PASS; "#4" port IMPLEMENTED / LIVE TEST FAILED, disabled, root cause identified
(HEAD/RETURN vs. try/finally), proper fix path identified (MixinExtras `@WrapMethod`, confirmed
available) but not yet attempted — not closing this as PASS.**

### B.6.10 #3+#4 — @WrapMethod fix, three-mode acceptance test: ALL PASS

Both "#3" (`ServerLevelSaveGuardMixin`) and "#4" (`ChunkMapSaveGuardMixin`) rewritten identically
with `@WrapMethod`/`Operation<Void>` (`pause → try { original.call(...) } finally { resume }`),
giving the same true try/finally guarantee as the reference repo's own pattern. ("#3" needed the
same fix too — it crashed live under a rapid-repeated-`/save-all` load with the exact same
HEAD/RETURN weakness, confirmed after initially being left on the (wrong) assumption that its
smaller exposure window made it safe.) Per the user's explicit three-mode acceptance plan (not
just re-running the original crash scenario):

- **Mode 1 (normal soak)**: free-running regions active, repeated real `/save-all` load — 0
  exceptions/CME/FATAL/watchdog hangs.
- **Mode 2 (forced exception)**: a test-only, env-var-gated hook (`NESTWORLD_TEST_FORCE_SAVE_EXCEPTION`,
  fires once, only on a real non-empty paused-region set) throws between pause and
  `original.call()`. Live log: `paused 12 REAL free-running region(s)` immediately followed by
  `resumed 12 region(s) in finally -- reached even though an exception is unwinding` — the finally
  block demonstrably runs and releases every real paused region even while an exception actively
  unwinds. The subsequent vanilla crash-report-and-terminate is vanilla's own by-design "Exception
  ticking world" policy for any uncaught world-tick exception — expected, unrelated to this fix's
  correctness, and specifically NOT a watchdog hang (the failure mode being fixed).
- **Mode 3 (clean shutdown)**: promoted a free-running region, then RCON `/stop` — log shows
  `Saved region layout for minecraft:overworld (1 region(s))` (correctly merged back before
  save), `BUILD SUCCESSFUL`, 0 exceptions/FATAL, clean process exit, no hang.

**Verdict: B.6.10 #3+#4 — PASS on all three acceptance modes. Category 11 (chunk/storage save
synchronization) CLOSED.**

### Unrelated finding surfaced during this session's live testing (not B.6.10, not today's work)

Live user-driven visual testing this session (unrelated to B.6.10 itself) repeatedly crashed the
server via a *different*, pre-existing category of bug: shared vanilla collections (`ChunkMap
.entityMap`, `NaturalSpawner`'s chunk iteration, `ServerLevel`'s block-update listener set) being
iterated on the main thread while concurrently mutated by region threads —
`NullPointerException`/`ArrayIndexOutOfBoundsException` from `it.unimi.dsi.fastutil` iterators
whose backing array went null mid-iteration. This is squarely inside the scope of the already-open,
still-`in_progress` task #128 ("B.6.8: ChunkMap / Regionalized Entity Tracking Audit") — not
introduced by any B.6.7.x/B.6.9/B.6.10 work this session, not fixed here, left for that task's own
dedicated pass. Also separately: task #161 fixed a real `ownsEntity()`-always-false-for-Players bug
in all 8 B.6.7.14 `MeleeAttackGoal`-family Mixins (mobs could never melee-attack the connecting
player under normal same-region conditions) — logically correct and kept, though live user testing
could not conclusively confirm it was the cause of the session's "mobs don't react" symptom (see
task #161 for the full correction).

## Follow-up (observability, not Core, not blocking)

File for later: identify the connecting player's own UUID in `EntityTransfer` DEBUG logs (or an
equivalent correlation method) so future boundary tests can directly confirm "the player's own
entity changed owner region," not just infer it from the interaction succeeding. Does not change
Core execution — purely improves the evidence quality of the next round of tests.

Scope (per go-ahead): ONLY `ServerChunkCache.nestworldLoadedFull`/`nestworldRefreshLoadedChunks()`
via Mixin, plus a `ChunkMap.tick()` accessor (real vanilla method, needed unconditionally in the
tick path). Everything else previously stubbed in B.1.2 — ServerLevel/ChunkMap `nestworld*`
patches, OutboundBatchQueue, CapabilityBus, redstone/block events, entity-impact/push, worldgen
glue report, gateway teleport — converted to inline `throw new UnsupportedOperationException(...)`
at the call site, NOT ported. No compile-success-only claim: this was validated with a live
`runServer` boot, per the lesson B.1.2 itself taught.

## What was built

- `net.nestworld.adapter.mixin.NestworldServerChunkCacheMixin` (`@Mixin(ServerChunkCache.class)`)
  — adds `nestworldLoadedFull`/`nestworldRefreshLoadedChunks()` via `@Unique`, same logic as the
  real 1.20.1 patch, adapted to the 1.21.1 `ChunkHolder` API confirmed in B.1.1
  (`getFullChunkFuture().getNow(UNLOADED_LEVEL_CHUNK).orElse(null)`).
- `net.nestworld.adapter.mixin.ChunkMapAccessor` (`@Mixin(ChunkMap.class)`, `@Invoker`) — exposes
  `ChunkMap`'s protected `getChunks()`/`tick()` without an Access Transformer, same pattern as
  NeoForge's own `MappedRegistryAccessor`.
- `net.nestworld.adapter.NestworldServerChunkCacheExt` — the typed marker interface Core/Adapter
  code casts to (B.2's Q9 access pattern), and `net.nestworld.adapter.NestworldExplosionBatch` —
  the record relocated out of the deleted `Explosion.java` vanilla-fork.
- `src/main/resources/nestworld.mixins.json` + `[[mixins]] config="nestworld.mixins.json"`
  uncommented in `neoforge.mods.toml`, `net.neoforged.gradle.mixin` plugin added to `build.gradle`.
- Deleted the 6 source-forked vanilla files from B.1.2 (`ServerLevel.java`, `ChunkMap.java`,
  `ServerChunkCache.java`, `DistanceManager.java`, `Explosion.java`,
  `TheEndGatewayBlockEntity.java`) — these are what caused the JPMS split-package failure.

## A real blocker found mid-implementation (not silently worked around)

First live-boot attempt failed with a NEW, different error from B.1.2's:

```
IllegalClassLoadError: net.nestworld.adapter.mixin.NestworldServerChunkCacheExt is in a defined
mixin package net.nestworld.adapter.mixin.* owned by nestworld.mixins.json and cannot be
referenced directly
```

Root cause: a mixin config's `"package"` field claims that ENTIRE package namespace for the
mixin transformer — any class placed there that is *not* itself a declared `@Mixin` class gets
blocked from normal loading. `NestworldServerChunkCacheExt` (a plain marker interface, no `@Mixin`
annotation) and `NestworldExplosionBatch` (a plain record) had been placed in
`net.nestworld.adapter.mixin` alongside the real mixin classes — that's invalid. Fix: moved both
non-`@Mixin` types out to the sibling `net.nestworld.adapter` package (already established by
#96/#97); the package declared in `nestworld.mixins.json` now contains ONLY the two real `@Mixin`
classes, matching NeoForge's own `neoforge.mixins.json` convention exactly. Recompiled, reran —
this specific error did not recur.

## Live proof (this is the actual B.3 evidence, not the compile step)

`./gradlew runServer`, then RCON stop (127.0.0.1:25580):

```
[Server thread/DEBUG] [mixin/]: Mixing NestworldServerChunkCacheMixin from nestworld.mixins.json into net.minecraft.server.level.ServerChunkCache
[Server thread/DEBUG] [mixin/]: Mixing ChunkMapAccessor from nestworld.mixins.json into net.minecraft.server.level.ChunkMap
...
[NestWorld/RegionSystem/]: NestWorld started for minecraft:overworld — initial region: Region{id=0, ...}
[NestWorld/Phase B M1] tick #1 (hasTime=false)
[NestWorld/Phase B M1] tick #100 (hasTime=true)
...
[NestWorld/Phase B M1] tick #900 (hasTime=true)
[Rcon: Stopping the server]
[NestWorld/Phase B M1] server stopping -- save/shutdown path. Ticks observed: 938
[NestWorld/RegionSystem/]: Saved region layout for minecraft:overworld (1 region(s))
[NestWorld/RegionSystem/]: NestWorld region system stopped
[minecraft/MinecraftServer]: ThreadedAnvilChunkStorage: All dimensions are saved
[minecraft/GenericThread]: Thread RCON Listener stopped
BUILD SUCCESSFUL in 1m 19s
```

Both real Mixin classes applied (confirmed by the transformer's own log line, not inferred).
938 real region ticks ran — every one of them calling the actual Mixin-provided
`nestworldRefreshLoadedChunks()` and `ChunkMap.tick()` (both unconditional, every tick, per the
tick-loop code) — with **zero exceptions of any kind** across the whole run, including zero
`UnsupportedOperationException` from any of the deferred stubs. Clean RCON stop, region layout
saved, world saved, RCON listener stopped, Gradle process exited 0.

## Honest scope limit

The test world was fresh and idle: no player connected, no redstone activity, no explosions, no
mob spawns observed. That means the ~20 deferred `UnsupportedOperationException` stubs (wire
handler, entity-impact, entity-push tracking, random-tick-chunk, gateway teleport, worldgen glue
report) were never actually exercised — their absence of a crash proves they didn't fire, not that
they work. This is expected and consistent with B.3's deliberately narrow scope (only the
`ServerChunkCache`/`ChunkMap` slice was meant to be real), but it should be stated plainly rather
than implied as broader coverage than it is.

## B.4 — Live player-join test (follow-up, same server binary)

Rebooted the same B.3 build (`runServer`); region layout restored cleanly from B.3's save
(`NestWorld restored saved layout for minecraft:overworld — 1 region(s)`, confirming the
save/load round-trip works too, not just save). Connected a real client — mineflayer 4.37.1 on
Node 22 (system Node is too old for mineflayer; used `/root/node22/bin/node`), protocol version
`1.21.1` — and drove it through login, spawn, 5s of movement, then a clean disconnect.

Server side:
```
[minecraft/PlayerList]: b4bot[/127.0.0.1:45636] logged in with entity id 16 at (-24.5, 63.0, -54.5)
[minecraft/MinecraftServer]: b4bot joined the game
...
[minecraft/ServerGamePacketListenerImpl]: b4bot lost connection: Disconnected
[minecraft/MinecraftServer]: b4bot left the game
```

Region ticks (`#100` → `#700`, ~5s apart) kept advancing at the same steady cadence straight
through login/movement/disconnect — no stall, no gap. Zero exceptions, zero
`UnsupportedOperationException` from any deferred stub (grepped the full boot log for both:
0 matches) — meaning none of the still-stubbed paths (wire handler, entity impact/push,
random-tick-chunk, gateway teleport, worldgen glue report) got exercised by a plain join + walk,
consistent with none of those needing a connected player specifically. RCON stop again clean:
world saved, region layout saved, region system stopped, `BUILD SUCCESSFUL`.

This is real additional coverage beyond B.3's idle-world proof: real login/packet handling,
real entity spawn or the connecting player, real movement packets processed by the region tick
loop, and a full save→restart→restore→save round trip — all with zero Core-code changes needed
beyond what B.3 already had. Still not covered: combat, block interaction, redstone, explosions,
multiple concurrent players, chunk loading beyond the pre-generated spawn area.

## Verdict

**B.3 PASS** for its stated scope: the Mixin injection mechanism (confirmed correct in B.2) now
has a real, live-booted, multi-hundred-tick vertical slice reproducing B.1.1's exact functional
target, with the JPMS blocker that ended B.1.2 gone (no source-forked vanilla files remain) and a
second, different real blocker (Mixin package-ownership) found and fixed rather than routed
around. This does **not** claim the other ~71 patches or the deferred stubs work — only that the
Mixin mechanism itself, and this one specific slice, is proven live.

**B.4 PASS** as well, same scope caveat: a real client can join, move, and leave this server
across a save/restart/restore cycle with zero exceptions — but combat, interaction, redstone,
explosions, and multi-player are still untested.

## B.5.1 — Combat (real melee attack, same region)

Same server binary, rebooted, region layout restored again. RCON-summoned a `minecraft:pig`
2 blocks from the connected mineflayer client (`Health:6.0f` starting NBT), had the bot
`lookAt`+`attack()` it 8 times over ~5.5s (real client → server attack packets, not a
server-side simulated hit).

Verified via RCON afterward: `data get entity @e[type=pig,...] Health` → `4.0f` — the pig's
health dropped from 6.0 to 4.0, i.e. **real damage was applied by a real client attack packet**,
not just "no crash." First attempt (creeper ~20 blocks away, naturally spawned by the idle
world's own spawner over the accumulated tick count across earlier B.3/B.4/B.5.1 boots) sent
8 attack packets out of melee range with no effect and no error either — confirming the vanilla
reach/distance check itself is intact too.

Full boot log grepped for `Exception`: exactly one hit, `io.netty...NativeIoException:
recvAddress(..) failed: Connection reset by peer` — a benign artifact of the bot calling
`process.exit()` without a graceful disconnect packet, not a Core/Adapter exception. Zero
`UnsupportedOperationException` from any deferred stub. Clean RCON stop, world+region-layout
saved, `BUILD SUCCESSFUL`.

**Not attempted this round**: cross-region combat. The test world currently has exactly 1 region
(`Region{id=0, chunks=(-1875000,-1875000)–(1875000,1875000)}` — the whole map), so there is no
natural region boundary to test against. Forcing a split (the code has a
`NESTWORLD_AUTOSPLIT=<tick>` self-test env var for exactly this) would need a dedicated
boot+test pass and wasn't done here — flagging honestly rather than skipping silently.

**B.5.1 PASS** for same-region melee combat specifically: real client → real server → real
damage, zero exceptions. Cross-region combat, block interaction, redstone, explosions, and
multiplayer remain open per the B.5 breakdown.

## B.5.2 — Block interaction (real client path, 4 of 5 planned checks)

Same server binary, rebooted again (region layout restored again — third consecutive clean
save/restore cycle). All 4 results were independently verified via RCON `execute if block`
against the live world, not inferred from the bot's own client-side state or from absence of
an exception:

| Check | Client action | Result verified via RCON |
|---|---|---|
| 1. Break block | `bot.dig()` on a `grass_block` | Cell now non-grass (dirt from the follow-up place, confirmed the break itself succeeded) |
| 2. Place block | `bot.placeBlock()` with `dirt` | `execute if block ... minecraft:dirt` → **Test passed** |
| 3. Use item | `bot.activateItem()` holding `ender_pearl` | Player position jumped **-6.5 → +11.7** on X — a real server-processed pearl throw+teleport, not a no-op |
| 4. Use item on block | `bot.activateBlock()` with `diamond_axe` on a placed `oak_log` | `execute if block ... minecraft:stripped_oak_log` → **Test passed** (this is exactly the split `useItemOn`/`ItemInteractionResult` API path confirmed during the Phase A diff audit) |

One dead end along the way, worth recording honestly: the first `useOn` attempt targeted a
log-placement spot that turned out to be over water, so `bot.placeBlock()` timed out
client-side waiting for a block-update event — **zero server-side exception**, confirmed by
grepping that boot's full log; a pure test-geometry mistake, corrected by picking a verified
solid spot and re-running. Also saw the same benign `Connection reset by peer` Netty artifact
twice more during give-item timing races (bot sent a chat command microseconds before the
server had a chance to apply an RCON-issued `/op`) — again zero Core/Adapter exception, purely
bot/RCON-script race, not a server-side finding.

Full boot-log exception census for the whole B.5.2 session: **one** hit total, the same benign
`NativeIoException: recvAddress(..) failed: Connection reset by peer` pattern already seen in
B.4/B.5.1 (abrupt client-side socket close, not a thrown Core exception). Zero
`UnsupportedOperationException` from any deferred stub — meaning none of block interaction's
still-stubbed dependencies (if any exist on this exact path) got exercised; per the user's own
framing, if one *had* been hit it would be recorded as a newly discovered dependency, not
silently ported around. Clean RCON stop, world+region-layout saved, `BUILD SUCCESSFUL`.

**Not attempted this round**: check 5, the boundary/cross-region case — same reason as B.5.1's
cross-region gap (world is currently 1 region covering the whole map; would need a dedicated
`NESTWORLD_AUTOSPLIT` boot). Flagged, not skipped silently.

**B.5.2 PASS** for checks 1-4: real client packets for break/place/use-item/use-item-on-block
all produced real, RCON-verified world-state changes, with zero Core/Adapter exceptions and
zero deferred-stub hits. No BlockInteraction patch surface was ported wholesale — only the
existing B.3 slice (ServerChunkCache/ChunkMap) was exercised by these paths, confirming none of
these 4 interactions currently depend on anything beyond it.
