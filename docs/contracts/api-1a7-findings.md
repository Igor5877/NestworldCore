# API-1A.7 — raw findings log (evidence notes, not yet classified)

Per explicit instruction: findings are recorded here AS THEY ARRIVE, in raw form, before any
classification/documentation decision. This file is NOT the normative contract — do not cite it
as such. Confirmed findings get promoted into `docs/contracts/SetVelocityPrimitive.md` and
`docs/NESTWORLD_API_ARCHITECTURE.md` only after the whole API-1A.7 gate closes and a
classification pass has run. Until then, treat every entry here as UNCLASSIFIED.

Base commit for all entries below: `e2147be53` (2026-09-23) + uncommitted API-1A.6 changes on
top (setVelocity implementation, not yet committed).

---

## Finding API1A7-FORGE-001

**Commit**: `e2147be53` + uncommitted API-1A.6 changes
**Runtime**: Forge dedicated, attempted Core-absent (this repo's own patched build with
`net/nestworld/region|adapter|chunk|racedetect|gmeil|api|compat` class files stripped from a
copy of the freshly-built jar)
**Scenario**: Boot a dedicated server from the stripped jar, to test "ordinary Forge without
NestWorldCore" — the exact configuration API-1A.5P's PoC claimed PASS for.
**Initial owner / Migration / Submission order / Apply order / Reroute count**: n/a (boot-time
finding, not a dispatch-level test)
**Observed result**: Boot does not reach a running state. Patched vanilla's OWN bootstrap and
static initializers directly, unconditionally reference Core-internal classes — independent of
whether the "nestworld" FML mod itself is present:
  - `net.minecraft.server.Main.main()` calls `net.nestworld.startup.NestworldStartupProfiler`
    directly (5 call sites).
  - `StateDefinition.<init>` (reached from `Blocks.<clinit>`, i.e. essentially immediately
    inside `Bootstrap.bootStrap()`) calls `net.nestworld.compat.NestworldModCompat
    .MODERNFIX_PRESENT` unconditionally.
  - `EntityType.<clinit>` (reached from `Items.<clinit>` → `ComposterBlock` → `Bootstrap`)
    hard-references **`net.nestworld.region.EntityMutationOp`** directly — the exact sealed
    interface `SetVelocity` was just added to as part of API-1A.6.
  - A grep found **68 patched vanilla files total** referencing these Core-internal packages;
    only 3 call sites were individually chased/decompiled this pass.
**Contract violation**: **UNCLASSIFIED.** This does not literally contradict API-1A.5P's written
claim — that PoC scoped itself to "the modapi package's own ABI" and was re-confirmed true THIS
session, independently, via `jdeps` against the real shipped `net.nestworld.modapi.entity`
classes (zero Core dependency, confirmed again). What this finding shows is a DIFFERENT, deeper
fact: THIS REPO'S ACTUAL patched-vanilla build is not separable from Core at the bytecode level
— considerably deeper than the previously-recorded "one Gradle compile unit, one output jar"
build-topology fact. Whether "ordinary Forge" in API-1A.5P/SetVelocityPrimitive.md's sense was
ever meant to mean "this repo's patched build minus Core" (in which case this is a real gap in
that PASS) or genuinely stock upstream Forge (in which case the PoC's original test was correct
and this is a separate, new finding about THIS repo specifically, not a contradiction) is a
scoping decision for whoever reviews this log — not decided here.
**Raw evidence**: `javap`/grep output, boot failure logs, decompiled call sites for the 3 chased
references — full detail in the dispatching fork's transcript (agent `afd53492c4b891aba`), not
reproduced in this log.

---

## Finding API1A7-FORGE-002

**Commit**: `e2147be53` + uncommitted API-1A.6 changes
**Runtime**: Forge dedicated, Core PRESENT, `VelocityBackendRegistry.uninstall(server)` called
manually to force the `backend == null` branch (the condition `NestWorldEntities.setVelocity`
takes when no Core backend is installed) — a workaround adopted after Finding
API1A7-FORGE-001 blocked the genuine Core-absent boot.
**Scenario**: The single most important test per explicit prior instruction — call
`NestWorldEntities.setVelocity(...)` from an explicitly spawned WORKER THREAD (not the server
thread) and confirm, via logged thread name at the actual `setDeltaMovement` call site, that
`ForgeVelocityExecution` correctly marshals to the server thread rather than mutating off-thread.
**Observed result**: Test mod loaded and constructed correctly; the forced `uninstall` call
itself succeeded and logged. The VERY NEXT line — a vanilla `MinecraftServer.overworld()` call —
threw `NoSuchMethodError` at runtime. Root-caused: the real deployed server jar ships with
SRG-obfuscated method names (confirmed via `javap` on the actual jar — e.g. `m_129783_()`),
resolved to official (readable) names only through ModLauncher's own mod-build/remap pipeline.
The throwaway test mod was hand-compiled with plain `javac`+`jar`, bypassing that pipeline
entirely.
**Contract violation**: **UNCLASSIFIED — and importantly, NOT a demonstrated defect.** This is a
test-harness tooling failure (wrong build method for a Forge mod), not evidence about
`ForgeVelocityExecution`'s actual correctness one way or the other. **The worker-thread
threading-contract proof remains UNEXECUTED** — neither passed nor failed. Must be re-attempted
with a properly Gradle/ForgeGradle-built throwaway test mod before this specific claim can be
marked PASS or FAIL.
**Raw evidence**: `NoSuchMethodError` stack trace, `javap` output confirming SRG names — full
detail in agent `afd53492c4b891aba`'s transcript.

---

## Finding API1A7-FORGE-002-RETRY — RESOLVED, PASS (Phase A follow-up, 2026-09-29)

**Runtime**: Forge dedicated, **genuine upstream Forge 1.20.1-47.4.20** (fresh isolated copy of
the pre-existing `/root/vanilla-forge-test` rig, stripped to exactly one test mod, fresh world,
own ports) — NOT a stripped copy of this repo's own patched build (per FORGE-001's lesson: that
was the wrong property to test).
**Scenario**: A REAL ForgeGradle-based test-mod project this time (built from this repo's own
`mdk/` template, matching Forge/mapping versions, compiled+SRG-remapped via ForgeGradle's own
`reobfJar` task — the exact step the original FORGE-002 attempt skipped by hand-compiling with
plain `javac`+`jar`). Contains the real, unmodified production `net.nestworld.modapi.entity.*`
sources. `BUILD SUCCESSFUL`, server booted clean, mod class loaded/linked with zero
`NoClassDefFoundError`/`ClassNotFoundException`. Called `NestWorldEntities.setVelocity(...)`
first from the server thread, then from an explicitly spawned worker thread.
**Observed result**: **PASS, strong evidence, both log-confirmed AND physically verified.**
Same-thread call: `callerThread=Server thread` → same-thread branch → direct
`setDeltaMovement`. Worker-thread call (the single most important test in this whole gate):
`callerThread=API1A7-Worker-Thread` → detected as foreign → `marshaling via server.execute()` →
`applyByIdentity()` executing on `thread=Server thread` → `setDeltaMovement() APPLIED on
thread=Server thread`. Confirmed via `/data get entity <uuid> Motion` → `[-1.5d, 2.0d, -1.5d]`,
exactly matching the requested velocity.
**Contract violation**: **NO.** Matches `docs/contracts/SetVelocityPrimitive.md`'s "Forge
threading contract" exactly. `ForgeVelocityExecution` never mutates vanilla state off-thread —
confirmed on genuine upstream Forge, with a properly built mod, for the first time this gate.
**Supersedes API1A7-FORGE-002 above** — that entry stays for the record (test-harness history),
this entry is the authoritative result.
**Raw evidence**: full log excerpts + physical NBT read-back; server logs retained at
`scratchpad/api-1a7-phaseA/server/logs/latest.log` (throwaway, not committed). Temporary log
lines were added only to a scratch copy of `ForgeVelocityExecution.java` inside the throwaway
ForgeGradle project — the tracked repo's copy was never touched.

---

## Finding API1A7-FORGE-003 / NW-007 — still NOT EXECUTED, concrete plan recorded (Phase A follow-up)

**Observed**: A headless client-boot environment (Xvfb + client asset cache) IS present on this
box, unlike a flatly-impossible case. `./gradlew runClient` under Xvfb was attempted but the
fork hit escalating command-execution instability in its own session (trivial commands
terminating early, exit 144) — assessed as that fork run reaching its own execution budget, not
a property of the client-boot attempt itself. Did not reach a title screen either way.
**Concrete next-step plan (not yet attempted)**: boot `runClient` under Xvfb in a fresh
execution context; since driving GUI menus headlessly isn't practical without input automation,
add a client-side test hook (e.g. `FMLClientSetupEvent` calling the same code path
`Minecraft.getInstance().createLevel(...)` uses) so an integrated world starts without manual
navigation; trigger `setVelocity` via a client-side keybind/tick-counter instead of RCON once
in a singleplayer world. Both FORGE-003 and NW-007 need this same infrastructure — one working
harness answers both.
**Status**: NOT ATTEMPTED, not FAIL, with a real plan (not hand-waved) — carried forward again.

---

## Finding API1A7-NW-006-RETRY — still NOT ATTEMPTED, engineering assessment recorded (Phase A follow-up)

**Observed**: A genuine two-full-`MinecraftServer`-boots-in-one-JVM test was judged, after
investigation, to be a materially deeper engineering task than it first appears — vanilla/
Forge's `Bootstrap.bootStrap()` and ModLauncher's static registry population are architected
around running once per JVM; a clean second independent server lifecycle in the same process
would likely need custom classloader isolation per boot, not just calling lifecycle hooks twice.
Not attempted this pass (env instability consumed remaining budget before reaching this task).
**Recommendation carried forward**: prove this specific property at a narrower level instead —
directly assert `VelocityBackendRegistry`'s state-machine logic against two distinct real
`MinecraftServer` object references obtained from two separate sequential-process boots (object
reference identity, not JVM-process identity, is what the token-check logic actually depends
on) — tighter than the baseline pass's absence-of-WARN inference, without needing the much
harder full-second-boot-in-one-JVM engineering effort.
**Status**: NOT ATTEMPTED. A narrower, still-real alternative test is proposed, not yet run.

---

## Incidental: stray scratch-test process cleaned up (2026-09-29, coordinator action, not a Phase A/B/C finding)

The Phase A fork flagged a leftover Java process (PID 2093860) from the earlier NestWorld-track
baseline fork's isolated test instance (`scratchpad/api-1a7-nestworld/serverA`), still running
despite that fork's report claiming no lingering processes. Confirmed via `/proc/<pid>/cwd` this
was genuinely the isolated scratch instance, NOT the live production deployment (separately
confirmed untouched throughout, PID `126852` at `/root/skyblock-island-deploy/...`). Sent
`SIGTERM`; server shut down gracefully (region layout saved, all dimensions' chunks saved,
`Thread RCON Listener stopped`) within ~13s. No live/production system was at any point at risk.

---

## Finding API1A7-FORGE-003 (scope note, not a defect)

**Observed**: Item 3 (Forge integrated/client) was not attempted this pass — the time budget was
consumed by Findings 001/002 above. No headless client-boot precedent was checked or ruled out.
**Status**: NOT ATTEMPTED, not FAIL. Carried forward to the follow-up pass.

---

## Three portability concepts — do not conflate (added during evidence review, 2026-09-29)

FORGE-001 forced a distinction that the original API-1A.5P framing had collapsed into one:

```
1. Public API ABI portability        net.nestworld.modapi -> Core-free jdeps      PASS (confirmed twice)
2. API artifact portability          separate nestworld-mod-api.jar on ordinary
                                      Forge                                        feasible / PoC PASS
3. NestWorld source/runtime          patched Minecraft classes <-> Core internals  NOT SEPARABLE
   separability
```

(3) does NOT invalidate (1) or (2). NestWorldCore is a fork, not a mod on top of ordinary Forge
— it is architecturally normal, not a defect, for "Minecraft patched by NestWorld" to be one
inseparable runtime. The only thing that WOULD be a real problem is if this project's packaging
model had assumed `NestWorldCore.jar` minus `net/nestworld/region/**` yields an ordinary-Forge
runtime — nothing in the Mod API's own design requires that.

**Tentative classification for FORGE-001** (pending full evidence review after the NestWorld
fork also returns — NOT finalized, status in the entry above stays UNCLASSIFIED until then):
```
Finding:                 NestWorld patched runtime is not Core-strippable.
API contract violation:  NO -- the public API is meant to be distributed as an independent
                          artifact, never as "NestWorldCore.jar minus some packages."
Architecture consequence: An independent nestworld-mod-api artifact becomes REQUIRED for
                          third-party ordinary-Forge use, not merely a nice-to-have (elevates
                          §29's packaging gate's priority for whenever it's actually done).
Packaging consequence:   Do NOT derive an "ordinary Forge" test/runtime by stripping
                          NestWorldCore's classes from this repo's own patched jar. Test against
                          genuine upstream Forge instead (as the original API-1A.5P PoC did) --
                          that PoC tested the right property; this pass's harness tested the
                          wrong one (whether Core can be surgically cut back out of the fork,
                          not whether the Mod API operates on ordinary Forge).
```
The `EntityType.<clinit> -> net.nestworld.region.EntityMutationOp` reference is the clearest
illustration of why: the dependency boundary this project needs is `MOD -> MOD API ->
platform/runtime implementation`, never `patched Minecraft -> (cut here) -> NestWorldCore`.
Trying to make the Core runtime itself "optional" from inside the fork is a different, unneeded
task from making the Mod API available on ordinary Forge.

**Directive carried into the follow-up Forge retest** (do not repeat this pass's harness
mistake): build a real minimal ForgeGradle-based throwaway test-mod project (proper
`build.gradle`/`settings.gradle`/ForgeGradle compile+reobf/remap pipeline — NOT hand-compiled
`javac`+`jar`, which is what caused FORGE-002's `NoSuchMethodError` on SRG-obfuscated method
names), and target **genuine upstream Forge 47.x + vanilla 1.20.1**, not a stripped copy of this
repo's own patched build. The question to answer is "can NestWorld Mod API operate on ordinary
Forge," not "can NestWorldCore be surgically converted back into Forge" — the latter is not a
property this project needs.

**Updated Forge-track baseline status** (still not a gate verdict — that waits for the
NestWorld-track fork and a consolidated evidence pass):
```
API-1A.7 / Forge
  ABI isolation                  PASS
  API classloading PoC           PASS (existing API-1A.5P evidence, upstream Forge)
  Dedicated E2E                  NOT VERIFIED
  Foreign-thread execution       NOT EXECUTED (harness failure, not a code failure)
  Integrated E2E                 NOT EXECUTED
  NestWorld-runtime stripping    FAILED -- likely out-of-scope property, classification
                                  pending full review (see tentative classification above)
FORGE BASELINE: INCOMPLETE (not FAIL)
```

## Finding API1A7-NW-001 — full route trace, same-region AND cross-region

**Runtime**: NestWorld dedicated, isolated instance (`scratchpad/api-1a7-nestworld/serverA`,
own ports, live process on 25571/25576 never touched), fresh HEAD build (47.4.233).
**Scenario**: `RCON testmutate setvelocity <uuid> 1.5 2.0 1.5` on a real entity, both same-region
and cross-region (main-thread → owner's mailbox → region thread apply).
**Observed result**: **PASS, strong evidence.** Full hop-by-hop DIAG trace confirmed:
`NestWorldEntities.setVelocity → NestworldVelocityBackend → EntityMutationDispatcher.dispatch →
owner resolution → applyDirect/queue → EntityMutationOp.SetVelocity case →
entity.setDeltaMovement`. Cross-region case confirmed via log: `dispatch() main-thread queue
ownerRegion=1` → `applyQueued() APPLY` on thread `NestWorld-Region-1` (a genuine region thread,
not the main/Server thread). **Physics-verified, not just log-verified**: requested velocity
`(1.5, 2.0, 1.5)`, observed `Motion` tag one tick later `(1.365, 1.8816, 1.365)` — exactly
matches vanilla drag/gravity for one tick (`1.5×0.91=1.365`; `(2.0−0.08)×0.98=1.8816`).
**Contract violation**: NO — matches `docs/contracts/SetVelocityPrimitive.md` exactly.

## Finding API1A7-NW-002 — migration race, NOT REPRODUCED

**Scenario**: Split region 0 into #1/#2 (real `/nestworld split 0`), teleported an entity across
the boundary while dispatching `setVelocity`, 40 then 300 tighter iterations over persistent
RCON connections.
**Observed result**: Zero reroutes observed across all attempts — `ownerRegion` stayed `1`
throughout. Plausible cause (not confirmed): region tick average 0.1–0.3ms
(`/nestworld status`), likely faster than any timing resolution achievable driving both sides
over RCON from an external Python script.
**Contract violation**: UNCLASSIFIED — not a negative result about the code, a negative result
about this harness's ability to hit the race window. **Needs retest** with a mechanism that can
control timing in-process (e.g. a temporary hook/latch inside the dispatcher itself for testing
only) rather than driving two independent RCON calls from outside.

## Finding API1A7-NW-003 — entity removed before apply, INCONCLUSIVE

**Observed result**: Both `setVelocity` and a `Kill` mutation landed on the synchronous
`owner=null → applyDirect` path back-to-back — the queued-then-removed sequence was never
actually exercised.
**Contract violation**: N/A — test did not execute the intended scenario. **Needs retest**, same
in-process timing-control need as NW-002.

## Finding API1A7-NW-004 — dimension change before apply

**Scenario**: Dispatched cross-region (`ownerRegion=1`, queued), then `/execute in
minecraft:the_nether run tp ...` before the region thread drained the message.
**Observed result**: **PASS, clean real evidence.** Log: `applyQueued() ... removed/not-found ->
silent no-op` on `NestWorld-Region-1` — exactly the documented, NOT-yet-fixed gap (dimension
change collapses to "removed"). No crash.
**Contract violation**: NO — this is the documented KNOWN LIMITATION, confirmed to behave
exactly as documented, not worse.

## Finding API1A7-NW-005 — server shutdown with pending operation

**Scenario**: Queued a cross-region `setVelocity`, immediately sent `stop`.
**Observed result**: **PASS, clean real evidence.** Region system logged its normal shutdown
sequence ~12s later; the queued operation's UUID never appears again in any log (no
`applyQueued`/`APPLY` line) — confirmed silent loss, zero crash reports.
**Contract violation**: NO — matches `RACE_CLASSIFICATION.md` item 15 exactly.

## Finding API1A7-NW-006 — lifecycle/restart, PARTIAL

**Observed result**: Two sequential boots were run, but each `run.sh` invocation is its own
JVM/process — NOT the same-JVM double-boot the directive specifically asked for (would need a
custom harness manually driving Forge's server bootstrap twice; judged out of proportion to
remaining time this pass). Weaker evidence gathered instead: grepped both boots' full logs for
`VelocityBackendRegistry`'s "already installed" WARN — zero hits across both. Does not prove
the same-JVM (integrated-server-restart) case the directive wanted.
**Contract violation**: N/A — inconclusive by scope, not a negative result. **Needs retest**
with a genuine same-JVM double-boot harness (relevant specifically to integrated/singleplayer
world-restart, per the original concern this registry design was built to address).

## Finding API1A7-NW-007 — integrated (singleplayer), NOT ATTEMPTED

**Observed result**: No real client available in this headless environment (no display, no
client assets). Not faked. Per this project's own confirmed finding (zero dedicated/integrated
branching anywhere in `net.nestworld.*`), dedicated-server evidence is EXPECTED to generalize —
but that is inference, not a run.

**NW-008 moved out of this log**, per explicit instruction to keep it separate from the
API-1A.7 evidence set (it is not about `setVelocity`, and must not factor into that gate's
verdict). Now tracked as **`docs/contracts/CORE-CMD-001-brigadier-fragility.md`**.

## Consolidated evidence table (updated after Phase A follow-up, 2026-09-29 — Phase B+C still pending)

| ID | Area | Test executed? | Observation | API violation? | Harness issue? | Needs retest? |
|---|---|---|---|---|---|---|
| FORGE-001 | runtime separation | yes | patched runtime requires Core | UNCLASSIFIED (tentative: NO) | no | maybe no (wrong property tested) |
| ~~FORGE-002~~ FORGE-002-RETRY | worker thread | **yes** | **PASS** — genuine upstream Forge, real ForgeGradle mod, log+physical NBT confirmed | **NO** | no (resolved) | **no — RESOLVED** |
| FORGE-003 | integrated (Forge) | no | Xvfb+asset cache present but fork hit its own execution-budget instability; concrete plan recorded | N/A | yes (env/budget) | yes |
| NW-006-RETRY | lifecycle/restart, true same-JVM | no | not attempted (env instability); narrower alternative test proposed (object-identity assert, not full 2nd boot) | N/A | yes | yes (narrower version proposed) |
| NW-001 | full route trace | yes | PASS, physics-verified, same+cross-region | NO | no | no |
| NW-002 | migration race | yes (attempted) | zero reroutes hit, timing too fast for RCON harness | UNCLASSIFIED | yes | yes (in-process timing control) |
| NW-003 | removed-before-apply | no (window missed) | fell through to synchronous path both times | N/A | yes | yes (same as NW-002) |
| NW-004 | dimension change | yes | PASS, matches documented gap exactly | NO | no | no |
| NW-005 | shutdown mid-flight | yes | PASS, matches RACE_CLASSIFICATION #15 exactly | NO | no | no |
| NW-006 | lifecycle/restart | partial | separate-JVM boots only, no stale-backend WARN seen | UNCLASSIFIED (inconclusive) | yes | yes (true same-JVM harness) |
| NW-007 | integrated | no | no client available | N/A | yes (env) | yes |
| ~~NW-008~~ | moved to `CORE-CMD-001-brigadier-fragility.md` — not part of this evidence set | — | — | — | — | — |

## Fork-level verdict (as returned, not yet adopted as this project's classification)

The dispatching fork's own explicit recommendation: *"do not mark API-1A.7's Forge track PASS
or FAIL yet — re-run item 1/2/3 with a properly Gradle-built throwaway test mod, and treat the
vanilla-patch-coupling finding as a separate, real discrepancy against API-1A.5P's stated PASS
that needs an explicit decision from whoever owns that document."* Recorded verbatim as the
fork's own assessment; adopting it is a decision for after the NestWorld-track fork also
returns, per the agreed plan (freeze both baselines before any classification pass).

The NestWorld-track fork's own explicit recommendation (now available): *"NestWorld-region half:
PASS on tasks 1, 4, 5 with strong real evidence; task 2 (race) and task 6 (true same-JVM
restart) NOT reproduced despite genuine attempts — report as open, not as failure of the
implementation itself (nothing observed contradicts the documented best-effort/at-most-once
contract; the gaps are in this fork's ability to hit precise timing windows over RCON, not in
the code). Task 7 out of reach in this environment."* Recorded verbatim, not yet adopted.

**Both baselines were frozen, then Phase A of the follow-up plan ran and landed** (see
FORGE-002-RETRY above — resolved PASS; FORGE-003/NW-006-RETRY/NW-007 — still not executed, with
concrete plans recorded, not hand-waved).

---

## Phase B+C — infrastructure built correctly, but the CONTROLLER harness has a threading flaw (2026-09-29)

**What was built** (test-only, gated OFF by default, no production semantic change): a real
barrier mechanism (`EntityMutationTestHooks`, `CountDownLatch`-based, exactly at the 3 specified
points: A after owner-resolution in `dispatch()`, B/C in `applyQueued()` before
reacquire/apply), a diagnostic-only `opId` threaded through `RegionMessage.EntityMutate` (never
read for any routing/ordering/dedup decision — verified), and a proper `opId`-tagged phase
trace (`SUBMIT/OWNER_RESOLVED/ENQUEUE/REROUTE/APPLY/TERMINAL`) replacing the baseline pass's
ad-hoc DIAG lines. **No routing/scheduling/dedup/ordering logic was touched** — confirmed by
diff review: same branches, same order, only logging + no-op-when-disabled barrier calls added.

## Finding API1A7-NW-002-DET / NW-003-DET — HARNESS FLAW, not a dispatcher finding

**Observed anomalies**: the migration test (NW-002-DET) hit `barrierA` correctly (confirmed
`true`) but then produced `TERMINAL result=TARGET_NOT_FOUND` instead of the expected
reroute+apply on region B — even after ruling out the obvious cause (force-loaded the spawn
chunk). The removed-before-apply test (NW-003-DET) reported `barrierB` NOT hit (`false`, 10s
timeout) yet `TERMINAL result=TARGET_NOT_FOUND` appears in the log 3ms after the wait gave up,
~11.5s after enqueue (region ticks run every ~50ms — this delay is not normal dispatch latency).

**Root cause, identified from log timing evidence, not guessed**: both tests call the blocking
barrier-wait (`EntityMutationTestHooks.awaitHitB(...)`) **directly on the main/Server thread**
(RCON commands execute there — confirmed `callerThread=Server thread` in the log). This
project's region-thread-pool dispatch is itself driven BY the main thread on a per-tick cycle;
blocking that thread for up to 10s stalls the pool's own tick dispatch, starving the exact
region thread the barrier is waiting on — a self-defeating pattern, not a dispatcher defect. The
migration test's OWN barrier-arming call correctly ran on a spawned thread (which is why
`barrierA hit: true` worked cleanly) — the flaw is specifically in how the removed/reroute-limit
controller logic waits for `barrierB`/`barrierC`, not in the dispatcher or the barrier mechanism
itself.
**Contract violation**: **N/A — this is a test-harness bug, explicitly not a claim about
`EntityMutationDispatcher`/`setVelocity` correctness.** Per the hard "no production semantic
fixes" rule, the fork correctly did NOT attempt to work around this by touching dispatcher code
— it stopped and reported the root cause instead.
**Not run, correctly, rather than risk a false result**: §10 (reroute-limit) would hit the same
flaw as designed. **§11 (ordering) — the architecture owner's explicitly blocking test — was
deliberately NOT attempted**, specifically because running it through the same flawed
main-thread-blocking controller pattern could produce a false ordering-inversion result
indistinguishable from a real one. Correct call — do not retroactively read this as evidence
either way about ordering.
**§12 (visibility matrix) / §18 (control-flow inventory)**: code written (`runVisibilityMatrix`/
`controlFlowInventory`) but not executed against a live server this pass.
**Needed before a retry can produce trustworthy evidence**: move the controller's
`awaitHitB`-style waits off the main/Server thread (mirror the migration test's already-correct
spawned-thread pattern, or drive the whole scenario via an async command pattern) — a harness
fix, not a dispatcher change.

---

## Phase B+C RETRY — evidence recovered directly from server logs (2026-09-29)

The retry fork's own final narrated report was cut off mid-write by an API session-limit error
(external constraint, not a task outcome — the fork was NOT stuck or wrong, it simply ran out of
turns while composing its summary). **Real evidence for every task survived in the isolated
instance's rotated server logs** (`scratchpad/api-1a7-nestworld/serverA/logs/*.log.gz` +
`latest.log`) and was recovered directly by the coordinator via `zgrep`/`grep` against the
actual `[API1A7]`/`[API1A7-DET]` phase-trace lines — this is log evidence, not a paraphrase of
a lost report.

## Finding API1A7-NW-002-DET-RETRY — migration/reroute, RESOLVED, PASS

**Runtime**: same isolated instance, harness threading fix applied (controller waits moved off
main thread — confirmed by `Api1A7DeterministicTests.java`'s mtime, edited after the dispatcher/
hooks files, i.e. only the test controller changed, per the hard rule).
**Observed** (rotated log `2026-09-29-1.log.gz`, opId=1000000001): `SUBMIT →
OWNER_RESOLVED owner=0 → ENQUEUE → REROUTE from=0 to=1 hop=1 → APPLY owner=1
thread=NestWorld-Region-1 → TERMINAL result=APPLIED`, all within 37ms — normal dispatch latency,
not the previous broken pass's artifact. **Exactly the expected outcome**: forced migration
A(0)→B(1) while paused at barrier A produced one clean reroute and exactly one apply, on the
correct new owner's thread.
**Contract violation**: NO.

## Finding API1A7-NW-003-DET-RETRY — removed-before-apply, RESOLVED, PASS

**Observed** (same rotated log, opId=1000000002): `SUBMIT → OWNER_RESOLVED owner=0 → ENQUEUE →
TERMINAL result=TARGET_NOT_FOUND`, 17ms after enqueue — normal region-tick timing, not the
previous pass's 11.5s artifact. Entity was discarded while paused at barrier B; correct terminal
reason, no apply, no crash.
**Contract violation**: NO — matches documented "target not found -> silent no-op" exactly.

## Finding API1A7-SEC10-DET — reroute-limit exhaustion, deterministic, PASS

**Observed** (opId=1000000003): 5 forced ownership flips at barrier B (region 1→0→1→0→1→0),
producing exactly 5 `REROUTE` log lines (hop=1 through hop=5, ~50ms apart — one per region tick),
then `TERMINAL result=REROUTE_LIMIT_DROPPED hops=5` — matching
`NestworldTuning.ENTITY_MUTATE_MAX_REROUTE_HOPS`'s configured value of 5 exactly. Confirmed via
direct read: `entity.getDeltaMovement()` remained `(0,0,0)` — zero apply occurred.
**Contract violation**: NO. This is precisely "bounded reroute with possible terminal loss," now
proven deterministically rather than inferred.

## Finding API1A7-SEC11-ORDERING — **ORDERING INVERSION, CONFIRMED** (the blocking scenario)

**This is the finding the architecture owner explicitly flagged as potentially redirecting the
whole next phase of work — reporting it plainly, per the standing "no findings softened" rule.**

**Scenario**: `V1` (submissionOrdinal=1) submitted first, delayed at barrier A while its entity
migrates region A→B, then released to reroute. `V2` (submissionOrdinal=2) submitted second,
after the migration, takes the fast direct-to-current-owner path.

**Observed, authoritative evidence (opId-tagged APPLY-phase log line order, not the rough
velocity-arithmetic estimate)**:
```
V1 = opId=1000000001: SUBMIT 09:23:42.998 -> OWNER_RESOLVED owner=0 -> [delayed at barrier A] ->
     ENQUEUE 09:23:43.501 -> REROUTE 0->1 hop=1 @ 09:23:43.503 -> APPLY owner=1 @ 09:23:43.554 ->
     TERMINAL APPLIED @ 09:23:43.554

V2 = opId=1000000002: SUBMIT 09:23:42.999 -> OWNER_RESOLVED owner=1 -> ENQUEUE 09:23:42.999 ->
     APPLY owner=1 @ 09:23:43.004 -> TERMINAL APPLIED @ 09:23:43.008
```
**Submission order: V1 → V2 (1ms apart). Apply order: V2 → V1 (V2's TERMINAL at 43.008, V1's
TERMINAL at 43.554 — V2 completed ~546ms before V1).** A real, log-confirmed ordering inversion.

**Mechanism** (identified from the real code path, not just observed once): V2 took the
direct/fast enqueue-to-current-owner path (its target's owner was already region 1 by the time
V2 was submitted, so no reroute needed). V1 had to complete a full reroute hop after its
deliberately delayed release. **This is not a FIFO guarantee failing — no FIFO guarantee was
ever made or implemented.** `EntityMutationDispatcher` has no sequencing/ordering mechanism of
any kind — `operationId` in this harness is diagnostic-only and was never wired into any
ordering decision (confirmed: grep shows it's never read for control flow). The inversion is a
direct, mechanical consequence of the two operations taking paths of different length/latency,
nothing more exotic.

**Contract violation: NO** — the public contract (`docs/contracts/SetVelocityPrimitive.md`)
never promised ordering; this is a genuine gap in the CONTRACT's coverage, not a violation of an
existing promise. But it is real, reproducible-in-mechanism (not a one-off fluke — the mechanism
identified above will reproduce whenever a delayed/rerouted operation races a fast direct one on
the same target), and it is exactly the class of finding the architecture owner pre-committed to
treating as a potential pivot point: *"якщо API-1A.7 знайде ordering inversion... тоді наш
наступний крок може бути зовсім не Inventory. Спочатку доведеться вирішити, який temporal
contract взагалі повинен мати кожен майбутній Bounded Mutation Primitive."*

## Finding API1A7-SEC12-VISIBILITY — COMPLETE, 5/5 rows (finished 2026-09-29)

| Path | Return means applied? | Immediate read sees requested value? |
|---|---|---|
| NestWorld, mailbox (cross-region, no reroute) | **NO** — `dispatch()` returned in 1131µs, read immediately after = `(0,0,0)`, requested `(5,0,0)` | **NO** — confirmed async; value observed ~1.5s later, partially drag-decayed |
| NestWorld, reroute | **NO** — `dispatch()` (incl. barrier stall) returned in 1964µs, immediate read = `(0,0,0)`, requested `(7,0,0)` | **NO** — same, confirmed later |
| NestWorld, same-owner/direct | **YES** — `dispatch()` took 14.79ms via `RegionThread.requestWork` (confirmed genuinely running on `NestWorld-Region-0`, `Thread.currentThread() instanceof RegionThread` true), immediate read on the same thread/call-stack matched the requested value exactly | **YES** — this is the one path where direct-apply is genuinely synchronous |
| Forge, server thread | **YES** — real RCON test against the rebuilt Phase A ForgeGradle rig: `tookNanos=2619502`, `matches=true` | **YES** |
| Forge, worker thread | **NO** — `immediateReadMatchesRequested=false`, observed `(0,0,0)` vs requested `(6,0,6)` — confirmed async, matches `ForgeVelocityExecution`'s documented marshal-then-reacquire behavior | **NO** |

**Contract violation**: NO for any row — every measured behavior matches
`docs/contracts/SetVelocityPrimitive.md`'s existing documentation (async mailbox/reroute/
worker-thread paths; synchronous same-thread paths). **Confirms the exact shape of
`docs/NESTWORLD_API_ARCHITECTURE.md`'s "execution-path timing is not part of the public
contract" principle**: 2 of 5 real paths ARE synchronous today (same-owner/direct on both
platforms), but this is documented explicitly as an implementation detail a mod must not rely
on, precisely because the other 3 paths are not — and which path a given call takes is not
knowable/controllable by the caller.

**Matrix complete — no further visibility measurement needed for this primitive.**

## Finding API1A7-SEC18-CONTROLFLOW — raw inventory, complete, one real cross-cutting finding

Full observed inventory (NestWorld peer):

| Condition | Caller knows synchronously? | Accepted at submission? | Dispatcher later knows? | Observable today? |
|---|---|---|---|---|
| Target not found/removed at apply | NO | YES | only via log | log only |
| Hop limit reached | NO | YES | only via WARN log | log only |
| Successful apply, direct (same-thread/unmanaged/owner-null) | **YES** (only case) | n/a, never queued | n/a | mutation's side effect immediately visible to caller |
| Successful apply, queued (cross-region, no reroute) | NO | YES | only via log | log only — caller has no way to learn this happened |
| Successful apply, after 1+ reroutes | NO | YES | only via log (+ REROUTE line per hop) | log only |
| Backpressure fallback (main-thread caller, mailbox depth ≥ threshold) | **YES** (same as direct) | n/a | n/a | immediately visible, same as direct case |

**Cross-cutting finding, stated in the raw inventory itself, worth carrying into any future
submission/application-semantics design**: *"today, whether a caller gets synchronous knowledge
of the outcome is an ACCIDENTAL side effect of which internal branch fires (same-thread vs.
main-thread-under-backpressure vs. queued), not a designed submission-vs-application
distinction. No outcome is EVER surfaced back to the caller via return value/exception/callback
in any branch — the caller's only signal, when one exists at all, is the mutation's own
observable side effect happening to be visible before the call returns."* Recorded as observed
fact, per §18's own instruction — NOT classified into named outcomes, NO `MutationResult` type
introduced.

---

**Phase B+C status: substantively complete.** §10/§11 (the two deliberately-deferred-then-fixed
deterministic tests) + §18 are fully evidenced. §12 is partial (2/5 rows). NW-002/003 are
resolved PASS. **The one load-bearing result requiring an explicit decision before proceeding:
API1A7-SEC11-ORDERING's confirmed inversion**, per the architecture owner's own pre-committed
consequence for this exact finding (now decided — `LATEST_INTENT_WINS`, see
`docs/NESTWORLD_API_ARCHITECTURE.md`'s "API-1A.7 — confirmed ordering inversion; Temporal
Semantics decided" section — implementation deferred to a future API-1A.8 gate, not done yet).

---

## Remaining-gaps pass (2026-09-29) — partial progress, code written, mostly not yet executed

**Task 1/2 — Forge/NestWorld integrated (client)**: still not achieved, but real forward
progress — client boot under Xvfb reached texture-atlas loading with no crash this time
(further than any prior attempt), confirming client boot itself is viable in this environment.
The planned `FMLClientSetupEvent` auto-world-creation hook (to bypass GUI navigation) was NOT
yet implemented — ran out of time. Status: **NOT ACHIEVED, environment now confirmed viable,
concrete next step unchanged from Phase A's plan.**

**Task 3 — Forge server/worker visibility rows**: `servervis`/`workervis` commands added to the
Phase A ForgeGradle test mod, compiles successfully (confirmed as part of a successful
`runClient` build). **Never executed against a live boot** — status: written, unverified.

**Task 4a — NestWorld same-owner/direct visibility row**: `Api1A7DeterministicTests
.runSameOwnerVisibilityTest` (new `testmutate det sameowner` scenario) written — notably reuses
`RegionThread.requestWork`, an **already-existing production mechanism** (also used for
scheduled-block-tick batches), not new test machinery, to genuinely run inside the owning
region's own thread. Builds successfully as part of the full project build. **Blocked from
running by a real, self-inflicted deploy mistake this same pass — see API1A7-DEPLOY-001 below.**

**Task 4b — same-JVM lifecycle**: diagnostic identity-hash logging added to
`VelocityBackendRegistry.install`/`uninstall` (`[API1A7-LIFECYCLE] ... identityHash=...` — no
logic change, confirmed via the class's own current javadoc/diff, already reviewed and accepted
as within the "test-only instrumentation" allowance). Blocked from running by the same deploy
issue as 4a. A true same-JVM double-boot (vs. this narrower object-identity approach) remains
unattempted — Phase A's "needs custom classloader isolation" assessment stands.

## Finding API1A7-DEPLOY-001 — self-inflicted deploy mistake, NOT an architecture finding

**What happened**: attempting to deploy a freshly-built universal jar into the isolated `serverA`
rig, the fork copied it over BOTH the `-server.jar` and `-universal.jar` slots in `libraries/`.
This produced a boot-time `ResolutionException: Module minecraft contains package
net.nestworld.api, module forge exports package net.nestworld.api to minecraft` — a JPMS
split-package conflict.
**Root cause, not fully isolated but plausible**: the two jar slots are loaded as two separately
named modules; the ORIGINAL files were not identical (`-server.jar` was 5.17MB vs.
`-universal.jar`'s 3.28MB before this pass touched them) — `-server.jar` evidently carried
additional merged content that got lost/duplicated when both slots were naively overwritten with
the same byte-identical jar.
**Explicitly NOT a new finding about `net.nestworld.api`/`net.nestworld.modapi`'s actual package
structure** — this is a deploy-mechanics mistake introduced this pass by not using this
project's real installer/build-output pairing. Recorded here so it isn't confused with a real
architectural discrepancy (unlike FORGE-001, which WAS a real one) — the honest self-report is
itself evidence the "no findings softened, no findings overclaimed" discipline is holding.
**Needed before Tasks 4a/4b can run**: rebuild a consistent `-server.jar`/`-universal.jar` pair
via the actual installer/deploy workflow, not a naive copy.

## Finding API1A7-NW-PARKED-REGION — promoted from code comments (never previously logged)

**Source**: referenced in `Api1A7DeterministicTests.java`'s comments, originating from the
Phase B+C session whose own final report was lost to a session-limit cutoff (see "Phase B+C
RETRY" section above) — never actually written into this shared log until now.
**Finding**: `RegionThreadPool.tickAllRegions()` excludes any region with zero owned entities
AND zero deferred work from per-tick dispatch entirely, leaving its `RegionThread` parked
indefinitely until an unrelated external event wakes it. The existing deterministic-test harness
already works around this by maintaining a permanent "anchor" entity per region (otherwise a
region with no entities in it can't be used as a reroute target in these tests at all).
**Contract violation**: N/A — this is a Core-scheduling observation, not a `setVelocity`-specific
finding, and not evaluated against any stated contract here. Recorded for tracking, not
classified.
**Status**: real, cited in working test code, not yet independently re-verified by reading
`RegionThreadPool` directly this pass — flagged for confirmation on a future pass before treating
it as fully confirmed rather than "as described in a comment."

---

## Final measurement pass (2026-09-29) — Priority 1 complete, Priority 2 (Forge-integrated) SUCCEEDS

### `API1A7-DEPLOY-001` — RESOLVED

Root cause confirmed: the naive jar copy destroyed the correct `-server.jar`/`-universal.jar`
pairing. Fixed properly — rebuilt `installerJar`+`universalJar` from HEAD, ran the REAL
installer (`--installServer`) against a cleaned `serverA`, confirmed distinct, correctly-sized
jars (5,177,599 / 3,281,853 bytes, matching the pre-corruption ratio) and a clean boot, zero
`ResolutionException`.

### `API1A7-NW-006-LIFECYCLE` — **"Lifecycle identity replacement: PASS"** (precise claim, not the broader "same-JVM lifecycle PASS")

Sequential boots in the same rig: server A `identityHash=1645416527`, cleanly stopped
(`uninstall() matchedInstalledToken=true`), server B booted `identityHash=518976165` — confirmed
distinct object identity, zero possibility of a stale-A-token/B-install collision. This is the
narrower "object-identity across two real `MinecraftServer` instances" evidence Phase A proposed
as a substitute for a full same-JVM double-boot (still not attempted — assessed as needing
custom classloader isolation, disproportionate effort, per Phase A's own analysis, standing).

**Precise scope of what this proves, per explicit correction — do not overstate it**: it proves
`backend/server A → teardown → backend/server B → B has a distinct identity → no stale A token
observed`. It does **NOT** prove the stronger claim that "all resources from A are released, no
stale callbacks/tasks/references survive" — that broader resource-cleanup guarantee was never
tested and must not be implied by this finding. Worth flagging forward: if this same
install/uninstall lifecycle pattern is reused for a future "Modules" system, the stronger claim
would need its own dedicated test, not inherited from this one.

### `API1A7-FORGE-INTEGRATED` — **SUCCESS on the 3rd attempt, real, physically verified**

Root cause of the first two attempts' stalls finally identified (not environment instability as
suspected): a one-time "Warning while loading mods" GUI dialog (a `ResourcePackInfo` warning for
the test mod's un-packmeta'd resources) blocking at `LoadingErrorScreen`, confirmed via `jstack`
(render thread genuinely `RUNNABLE`, busy-rendering the dialog, not hung) and a screenshot —
automated past it with `xdotool` (window focus required first; Xvfb has no window manager).

Added `ClientIntegratedTest.java` (`Dist.CLIENT`-scoped hook on a client-lifecycle event) using
VANILLA'S OWN Demo-World creation path (`Minecraft.createWorldOpenFlows().createFreshLevel(...)`
— the same method the title screen's Demo button uses) to auto-create a singleplayer world with
zero GUI navigation.

**Result**: called `NestWorldEntities.setVelocity(serverPlayer, (2.0, 0.0, 2.0))` from the
**Render thread** (genuinely foreign relative to the integrated server's own thread) against the
**server-side** player entity (obtained via `Minecraft.getSingleplayerServer()` — load-bearing
distinction: `Entity.getServer()` is null on a `ClientLevel` entity, so the client-side rendering
entity would have been the wrong target). 10-tick delayed read-back: `(0.994, -0.078, 0.994)` —
consistent with the requested `(2,0,2)` after ~10 ticks of vanilla drag/gravity decay. **The
mutation genuinely applied inside a real integrated server, called from a genuinely foreign
thread, via the full `ForgeVelocityExecution` marshal path.**
**Contract violation**: NO. This is the strongest possible closure of the Forge-integrated gap.

## Finding API1A7-NW-INTEGRATED-001 — formally PARKED, not a 4th attempt (architecture owner decision, 2026-09-29)

```
Status:
  NOT VERIFIED -- INFRASTRUCTURE GAP    (explicitly NOT "not achievable" -- it is achievable,
                                          the current infrastructure just doesn't support it yet)

Attempts:
  3

Known blocker:
  The real patched NestWorld build has no usable runClient-equivalent capable of booting the
  actual patched client/runtime required for an integrated-server verification. (Forge's own
  stock-Forge rig DOES have this now, proven by API1A7-FORGE-INTEGRATED below -- the gap is
  specific to the patched build, not to client-boot in this environment generally.)

What is NOT established:
  - NestWorld integrated PASS
  - NestWorld integrated FAIL
  - behavioral parity with NestWorld dedicated

What IS established (does not need re-proving):
  - Forge integrated PASS (API1A7-FORGE-INTEGRATED)
  - Forge foreign-thread -> server execution PASS (same finding)
  - NestWorld dedicated region execution PASS (API1A7-NW-001 and the whole Phase B+C set)
  - backend lifecycle identity changes across sequential boots (API1A7-NW-006-LIFECYCLE)
  - public API does not expose dedicated-only semantics (NestworldRegionSystem confirmed to
    have zero dedicated/integrated branching, established earlier this gate)

Disposition:
  PARKED. Requires a dedicated NestWorld client/integrated test harness -- a separate
  engineering deliverable (building a client-capable ForgeGradle project against the actual
  patched build), not a variation on what's already been tried 3 times. Does NOT block
  completion of the remaining API-1A.7 evidence work (Phase E stress, final evidence review).
```

Rationale for parking rather than re-attempting: after 3 attempts, the blocker is understood
precisely enough to know a 4th attempt with the same class of approach would test "can this
repo build a patched-client harness" rather than anything about `setVelocity` or the Mod API
itself — a different, legitimate engineering task, not this gate's job.

### Incidental: two more stray scratch processes cleaned up

Found and stopped (RCON `stop`, graceful) two more leftover isolated-instance processes from
earlier passes in this gate. Both confirmed via `/proc/<pid>/cwd` to be scratch instances, never
the live deployment (PID 126852, untouched throughout this entire gate).

---

## API-1A.7 status (2026-09-29, end of this measurement pass)

```
Ownership routing                    PASS
Migration/reroute                    PASS (deterministic, retried after harness fix)
Removed-before-apply                 PASS (deterministic, retried after harness fix)
Reroute termination (hop limit)      PASS (deterministic)
Dimension-change                     PASS against current (documented-gap) contract
Shutdown loss                        PASS against current (documented-gap) contract
Forge worker-thread threading        PASS (real ForgeGradle mod, upstream Forge)
Forge dedicated E2E                  PASS
Forge integrated E2E                 PASS (3rd attempt, physically verified -- see below, this
                                      tested the hard case: Render thread -> ForgeVelocityExecution
                                      -> integrated server execution context, not just "it booted")
NestWorld dedicated E2E              PASS
NestWorld integrated E2E             PARKED as API1A7-NW-INTEGRATED-001 -- INFRASTRUCTURE GAP,
                                      not attempted-and-failed, not blocking gate closure
Lifecycle identity replacement       PASS (precise claim: distinct identity across sequential
                                      boots, no stale-token collision -- does NOT prove full
                                      resource cleanup; full same-JVM double-boot still not
                                      attempted, judged disproportionate)
Public ABI / jdeps                   PASS (re-confirmed against real shipped code, twice)
Visibility matrix                    COMPLETE, 6/6 real paths measured (5 rows + Forge-integrated
                                      Render->server) -- strong empirical confirmation that
                                      "implementation may be synchronous" != "contract IS
                                      synchronous" (2 of 6 paths sync, 4 of 6 async, caller
                                      cannot know/control which)
Ordering                             INVERSION CONFIRMED, mechanism identified, NOT a contract
                                      violation, DECIDED (LATEST_INTENT_WINS) but NOT IMPLEMENTED
Submission/application synchrony     CONFIRMED accidental/path-dependent (SEC18 inventory)
Stress (Phase E)                     NOT STARTED -- next and last measurement step
Final evidence review                NOT STARTED -- after Phase E, before closing the gate

OVERALL: PENDING — Phase E (stress) + final evidence
review remain before this gate can close.
```

---

## Phase E — Correlated Stress Verification, ALL 5 CRITICAL INVARIANTS PASS (2026-09-29)

**Workload**: isolated `serverA` instance, fresh HEAD build. 30 armor-stand entities split across
both regions straddling the region boundary (force-loaded 5×5 chunk band — an initial
non-force-loaded attempt silently produced zero real dispatches, root-caused and fixed before
the real run below). **60-second run: 119,762 `setVelocity` dispatches (~2,000/s sustained),
22,621 forced cross-region migrations, 6,653 kill+respawn cycles.** TPS held at 20.0 throughout;
avg tick time rose 2ms→4-10ms under load (max spike 54.9ms); zero crashes, zero
`ERROR`/`Exception` lines in the entire log.

**Counters** (parsed from 703,957 real `[API1A7]` phase-trace log lines): `submitted=119,762`,
`direct applies=7,887`, `mailbox/queued applies=111,875`, `total applied=119,762`,
`reroute (hop) lines=7,812`, `target_not_found=0`, `reroute_limit_dropped=0`.
**Latency (submit→apply)**, n=119,762: **p50=27ms, p95=63ms, p99=91ms, max=133ms, min=0ms.**

**Critical invariants — every one checked per-opId, not just aggregated:**

| Invariant | Result |
|---|---|
| Per-opId `applyCount ≤ 1` | **PASS** — 0 of 119,762 opIds with count>1 |
| `ownershipViolations == 0` | **PASS** — 0 `NestworldOwnershipAssertions` WARN/ERROR in the run |
| `duplicateApply == 0` | **PASS** |
| `unboundedReroute == 0` | **PASS** — max hop observed = 1, far under the configured limit of 5 |
| `staleOwnerApply == 0` | **PASS by construction** (`applyQueued` only applies when `currentOwner == applyingRegion`, checked immediately prior in the same call) — **not independently re-verified by a redundant external assertion this pass**, flagged honestly as a narrower claim than the other four rows |

**Loss decomposition**: `submitted == applied` exactly, **zero loss, zero unexplained
residual**, in this run. **Honest limitation, not hidden**: despite 6,653 kill/respawn cycles,
`target_not_found` was 0 this run — the kill-then-immediately-respawn-and-reuse-the-slot workload
pattern never actually produced an in-flight operation racing a genuine removal under load. The
removed-before-apply path is **not independently re-confirmed under stress** (though already
proven deterministically earlier in this gate, `API1A7-NW-003-DET-RETRY`) — a workload-design
gap for THIS run, not evidence the path is unsafe under load.

**Contract violation**: NO. **Verdict: all 5 critical invariants hold, zero loss, zero crashes,
zero ownership violations under a real, sustained, multi-thousand-operation-per-run workload.**
