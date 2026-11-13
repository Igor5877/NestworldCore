# Claude handoff — AE2 WP3-B — 2026-11-11

Owner explicitly requested handing this task to Claude in tmux because Codex limits are ending. This file is the current source of truth; older evidence is historical, not a production-ready claim. Speak Ukrainian, act autonomously within authorized scope. User wants an actual TPS improvement quickly, no false assurances.

## Scope and constraints

- AE2 correctness CLOSED as AE2-COMPAT-PRODUCTION-VERIFIED. Do not reopen D1/D4a/topology/readiness/TickManager/end-tick/teardown/P2P/MBCalculator without concrete production symptom.
- WP3-B goal: move expensive external inventory bus work off MAIN while AE2 network authority remains MAIN-safe. Production pins stay; no global lock, synchronous REGION→MAIN wait, pinzone, global pin semantics changes, throttling or player restrictions.
- User explicitly permits Core mechanisms; addon only needs NestWorldCore. **Latest instruction: DO NOT implement Mod API now; focus TPS20 first.** Only Mod API source was read; no API changes made.
- **Latest testing instruction: ON only. Do not repeat OFF now.** Load mining claim chunks BEFORE measuring; current list is66 unique, verified with execute if loaded. Earlier65 count is obsolete/wrong for current list.
- User authorizes test-world copy, isolated test server, loading mining claim. Do not restart live with other players; Igor is explicitly exempt, _Hiklee_ is not. Last online _Hiklee_. No live deployment in this development stage.
- User earlier explicitly authorized commit/push; completed commit93d6d2750 on origin/claude/nestworld-hybrid-arch-invq4h. Current work BELOW is uncommitted. Preserve other work; no git add .

## Live state / confirmed diagnosis

Live /root/project-infinity, service nestworld-infinity, JavaPID290 last checked. RCON ./rcon.sh; never print secrets or whole server.properties. Identify Java by /proc/PID/cwd, never pgrep/pkill -f.

Installed addon0.9.1 SHA1f6d4a57133604d4931a9a9250e8ed5ef31302b3773a4efb048114ec08dd5c86. It is OLD short-export path: withdraw before target capacity, then refund. Transit enabled, fixtures disabled, pins intact. Core445 (NOT latest source Core540). online-mode=true last check; user earlier toggled but someone restored; don't alter now.

Fresh LIVE JFR /tmp/ae2-followup-20261111.jfr, execution samples16:31–16:32UTC: MAIN5860, AE2 tick path~41.4%, export991, import751, Interface449, Soph getter377. GC~0.53% window. Live~10TPS/~100ms. Counter snapshots~75s: export withdraw18262, deliver336, return17926 (98.16% refund). Transit import0. Own recorded transit overhead small; don't claim all low TPS caused by refunds. docs/compat/evidence/wp3b-20261111/live-followup/diagnosis.md.

Single region +autoSplit=false confirmed separately in Overworld and mining. regionpool command ALWAYS reads Overworld even execute in mining. Status MAIN avg since boot is dimension wall time including waits, NOT CPU; excludes ordinary AE2 END grid work. No split performed. User requested manually split; command only Overworld, so didn't split wrong world.

## Completed performance evidence: NOT ready

Copied-world0.9.3 test with66/66 loaded: evidence /root/airis-2026-10-25/WP3B-MINING-20261111/comparison-20261111-183659. Warm90s +measure120s each. OFF actual11.000TPS/MAIN85.947ms; ON10.633TPS/MAIN88.110ms. No benefit. Short Forge rolling snapshots12.67/12.92 are NOT whole-window TPS. JFR both collected/analyzed; docs/.../candidate-093/jfr-comparison.md:

ON47360 bus calls, only510 transit(1.08%); strategy refusal29551(62.4%), linked9488(20%), MAINneighbor4642(9.8%), crafting3043(6.4%). Region inventory work19ms TOTAL, inline MAIN35SECONDS. Import/export MAIN stacks stayed hot. This is a coverage failure, not evidence to optimize wrappers further. Typed dispatch optimization previously tested also gave no benefit; defaultOFF.

Removing whole addon baseline crashed AE2 TickManager during warmup (removes verified fixes): invalid perf baseline, don't reopen TickManager. MM previous tests0 transit due namespace type-pin guard, so NOT MM transit PASS.

## Current NEW source:0.9.4 experimental cursor +Core handoff

Addon files:
- BusImportContinuation.java NEW: feature -Dnestworld.compatAe2.busImportCursor=true, DEFAULTfalse. Ordered strategy cursor, preserved filter/ops, item prefix ledger, async handoff, native MAIN non-item suffix. No prefix replay. MAIN StackTransferContextImpl constructed reflectively. Native budget copied back in finally on exception; suffix never retried after exception, failure counter increments.
- ImportStrategyCursor.java NEW: exact audited no-op prefix probes at ORIGINAL strategy position. FE/Soul, AE2 storage fluid, Mek chemicals, Mana. Disabled keytype short circuit; capability lookup same order; ABSENT skips once; present requests MAIN continuation. isItem true ONLY audited AE2 HandlerStrategy.ITEMS. All other resources MUST execute original native context on MAIN, never optimistic region buffer. Latest helper extension and root isItem integration NOT rebuilt/tested yet.
- LocalImportSource.java NEW, agent just finished: conservative ownership predicate local(BlockEntity), exact vanilla barrels/furnaces, audited Soph Barrel/LimitedBarrel only; rejects chests, subclasses, controller membership, endpoint/linked wrapper fields/transitional states. **NOT integrated yet.** Call owner-side BEFORE ANY region item prefix and before later region item strategy/returns; false→handoff at current cursor BEFORE mutation. Source snapshot relies on existing owner serialization, isn't itself a lock. Remote inventory ownership is not certified by physical position. Need review before enabling broad Soph imports.
- BusTransit.java modified: new cursor eligibility, record cursor, region runner, waits for ready, prefix MAIN commit then suffix then same-pass recursive ledger commit, terminal lease release, status. Added read-only heldTypes in records for next diagnosis (NOT built yet). Check for integration mistakes.
- BusImportCursorFixtures.java NEW: fixture-only attached ENERGY to explicitly registered furnace plus source energy/domain counters and networkFE gauge.
- BusTransitFixtures.java modified: kind importenergy adds FE cell +source item. LATESTFIX source above host, busUP so fromSideDOWN accesses furnace output; all station operations use busSide. Previous test NORTH couldn't extract output. Latest edits NOT rebuilt/tested.
- NestworldCompatAe2.java command bustransitfx cursorstatus.
- version0.9.4. Existing compiled0.9.4 jar is STALE relative to latest source fixes; rebuild.
- Older safe export pump +zero-cap scan skip already in source. Typed dispatch defaultOFF. Production correctness fixes remain.

Core files (agent /root/safe_export_pump finished ownership):
- BlockEntityDomainHandoff.java NEW generic opt-in physical-position ownership handoff; request MAIN-only, valid/ready/fail MAIN callbacks; Lease AutoCloseable MAIN close. Source handoff via owner mailbox; complete AFTER Runnable.run returns; native tick guard fences precollected/carried work. Guarded callbacks exactly-once token reroute by actual current domain. Position guard persists after lease release; shutdown tombstones until ServerStopped. Original pins object captured, no new global lock/pin semantics changes.
- BlockEntityDomainHandoffGate.java, Token.java NEW, exclusive per-position active-native/owner/release state. Closed gate waits active work before unpin.
- BlockEntityExecutionDomain.execute only replaces task.op.run with runDomainTask wrapper.
- NestworldDimensionRegion tickBlockEntityList both raw tick and GMEIL lambda use tickOrRoute.
- tests/region-handoff/BlockEntityDomainHandoffGateTest.java: pure Java17 gate/token tests PASS,2000 native/owner races. These are NOT full Minecraft runtime proof.
- Core compile+reobfJar PASS after final fixes: /tmp/wp3b-core-handoff-build-final2.txt. No Mod API or RegionSystem edits.

## Core445 overlay packaging (don't wholesale replace Core540)

Tool compat/.../tools/b4/CoreHandoffOverlay.java, compiled /tmp/CoreHandoffOverlay.class. Uses ASM9.5 jar in /root/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.5/dc6ea1875f4d64fbc85e1691c95b96a3d8569c90/asm-9.5.jar.

Original445 universal path:
/root/airis-2026-10-25/WP3B-MINING-20261111/libraries/net/minecraftforge/forge/1.20.1-47.4.445-claude-nestworld-hybrid-arch-invq4h/forge-1.20.1-47.4.445-claude-nestworld-hybrid-arch-invq4h-universal.jar

Compiled reobf helpers: projects/forge/build/reobfJar/output.jar (contains latestCore540 code; ONLY new helper prefix copied, not other classes).

Frozen overlay /tmp/wp3b-core445-handoff.jar SHA2eb99192a1fb9dc0928b8dfafc9f6ee5d12645161c937e7a09ca5ef268abac2a. Verified ONLY2 original class entries changed;11 helper classes added, Java17 major61. Exact hooks: raw ticker m_142224_, GMEIL invokedynamic ticker::tick, Runnable.run inside domain execute. All other445 bytes identical. Source445 has GMEIL too. Overlay rerouted native body timing not captured by original GMEIL enqueue frame; use actual CPU/counters.

## Last runtime test: PARTIAL/FAIL, do NOT call PASS

Evidence comparison-20261111-192523/1-cursorverify (same test root). All66 loaded. Plain station2080 in network+224source=2304created, invariant0, no held. Energy station remained1source/0network; storedFE1m,0calls, handoff0. Thus Core request/lease path was NOT exercised.

Exact causes found:
1. Production config/appflux-common.toml enable_FE_pull=false. No FE import strategy registered! Soul absence probe worked, but FE fixture couldn't test handoff.
2. Furnace output item in slot2 was inaccessible from north source face. Latest sourcefixture now uses busUP /fromSideDOWN.
3. Global unfiltered cursor admitted builtin non-item fluid/Mek/mana strategies to optimistic buffer, exposing growing held backlog. Counters stuckNonItem are NOT definitive type proof (counter also increments if drop impossible due unloaded chunk). After file: outstanding141, held58128. Nothing silently lost, but queue acceptance FAIL. Latest source now routes ONLY isItem to optimistic context; non-item resources native MAIN via cursor probes. Need rebuild and verify heldTypes in records; don't guess resource family.

Test stopped gracefully, lock released, original Core445 restored by harness finally. No live changes. No test JVM should be running now. Check /proc cwd if needed.

## Harness / next exact actions

compat/.../tools/b4/mining-pack-run.py:
- modes cursorverify (targeted ON, fixtures), on (performanceON), off etc. User asks NO MORE OFF now.
- --core-overlay PATH installs ONLY owned test library jar and restores original in finally.
- --import-cursor sets new flag on performanceON.
- restores frozen snapshot each run; loads66unique mining chunks and verifies Test passed for EVERY chunk before measurement.
- warmup default90s, measure120s; neutral JMX collector /tmp/CollectServerJmx.class, no addon dependence, excludes live. Use actual observedTicksPerSecond and MAINcpuMsPerObservedTick, not onlyForge TPS.
- LATEST cursorverify harness now enables FE_pull=true ONLY in test config, backed up/restored in finally; this is CORRECTNESS-only. Normal performance must retain productionFE_pull=false.
- cursorverify now records forgeTPS, full status, records/heldTypes, grids, fixture counts after test. These latest harness fixes NOT run yet.
- private netns (unshare -n), loopback only, nice10/taskset0-7,4–8GiB heap, own atomic .comparison.lock. Never source/live writes.

NEXT:
1. Review/integrate LocalImportSource before region item mutation. Review new resource probes/isItem path, account prefix/lease lifetime/unload.
2. Rebuild addon0.9.4. Addon command:
 nice -n19 taskset -c24-35 ./gradlew -p compat/nestworld-compat-ae2 reobfJarJar --offline
 Core if further changes: nice -n19 taskset -c24-35 ./gradlew :forge:reobfJar --offline (1–2min). Overlay output refuses overwrite; picknewpath.
3. Run ON-only targeted fixture:
 python3 compat/nestworld-compat-ae2/tools/b4/mining-pack-run.py compat/nestworld-compat-ae2/build/libs/nestworld-compat-ae2-0.9.4-all.jar --modes cursorverify --core-overlay /tmp/wp3b-core445-handoff.jar --warmup30
 Need handoffs/resumed>0, noerrors/offdomain, energy conservation source+network1m, item counts0balance, no growingnonitem held/queue/lease leak. Add unload/replacement/failure-injection tests; current fixture covers only initial nominal cases.
4. Only ifcorrectnessPASS, ON performance with66loaded/warm90s/120s:
 ... --modes on --core-overlay ... --import-cursor
 CaptureTPS/MAIN/JFR/counterdeltas. NoOFF now. Need actual meaningful MAIN benefit;20TPS is goal, not guaranteed. Imports on Sophlinked/controller/chest stay safe; don't drop ownership guards to forcecoverage.
5. Continue actual costly network-side Storage Bus adaptation if inventory import offload alone insufficient; no broad correctness reopening. Do not present 0.9.4 as ready because it compiles.

## Existing artifacts / rollback

Live backup /root/project-infinity/wp3b-backup-20261110-181540 (previous jar/config/pinhashes; transitOFF rollbackargs). /tmp/ae2-0.9.3-candidate contains currentlive0.9.1 rollbackjar/config and old0.9.3 candidate. 0.9.3 DO NOT DEPLOY asTPSfix. User wants fastest working addon, but we must report measured results honestly.

No active Codex agents will continue editing after this handoff. All source remains uncommitted on shared branch; lastpushed93d6d2750 was before0.9.3 followup/Corework. docs/compat/evidence/wp3b-20261111 contains prior reports. No rawJFR committed; may include environment secrets, keep local.
