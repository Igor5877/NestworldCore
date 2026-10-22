# OPS triage 2026-10-23 — tick-skips, crashes and thread dumps (owner report)

Source: the owner's production summary (servers Igor, Spawn-dev, airis, spares; Core 47.4.218–376).
- No production server was touched.
- Classification uses the standing taxonomy: `docs/RACE_CLASSIFICATION.md`, and the rule
  "faulting mod ≠ mutating mod".

Build-to-commit mapping:
- **Rule:** Core 47.4.N = `git rev-list --count 47.4..<commit>`. Verified on the known pair
  306 ↔ `7803c678`.
- **On this branch:** 47.4.224 = `3f0b98759` (the Draconic/AE2 registry fix). 228 / 229 / 230 / 234
  = `3b0ef086` / `a7550a80` / `8ecb8ecf` / `d405f518`; **all four contain 224**.
- **Caveat:** island 234 has no recorded provenance, so this holds only if those builds were made
  from this branch.

## 1. Tick-skips

| Server / Core | Count | Exception / first non-JDK frame | Classification | State |
|---|---|---|---|---|
| Igor 228 | 12 | CME HashMap, `BoundaryManager.syncGhostZones:97` | Core (ghost zones) | **fixed**: not seen after 228 (owner) |
| Igor 228 / 230 / 234, Spawn-dev 229 | 4 + 1 + 3 + 9 | CME `WeakHashMap.forEach`, `DEEventHandler.serverTick:58` | **mod race, NOT covered by 3f0b98759** (CONFIRMED from bytecode, 3.1.2.621). `DEEventHandler.serverTick` (main) runs `static WeakHashMap<Mob,Long> deSpawnedMobs.forEach`; the writer is `DEEventHandler.onMobSpawnedBySpawner(Mob)` (spawner tick, region thread). Fix 224 covered `CrystalUpdateBatcher.batchQue` / `ID_CRYSTAL_MAP`, a different structure | **OPEN, new race record.** Fix class C3/C6: WeakHashMap weak-key semantics rule out a plain C1 swap; a synchronized wrapper plus iterate-on-copy, or a main-drained queue, is needed. Interim: `pinmod draconicevolution` or a BE pin of the spawner |
| airis 230 | 20 | CME `HashMap$KeyIterator`, `exdeorum VisualUpdateTracker.syncVisualUpdates:55` | mod race, C1 (shipped) | **fixed**: not seen after 230 (owner) |
| airis 234 | 26 | ISE "Cannot modify energy requesters while energy is being injected", `appeng EnergyService.addRequester:515` | **AE2 energy race: the AE2-COMPAT-ENERGY-001 class** (topology on a region thread during injection). The islands run **without** the addon | covered by `nestworld-compat-ae2` (ENERGY-001 CLOSED/VERIFIED); the canary build carries it |
| airis 234 | 1 + 2 without a stack → crash | NPE `ObjectArrayList.get` "this.wrapped is null", `mekanism NetworkAcceptorCache.commit:63` ← `TransmitterNetworkRegistry.onTick:140` | **mod race (Mekanism transmitter network)**, HYPOTHESIS: acceptor cache mutated from a region thread while main commits. Not the flowingStacks site (fixed). No record yet | **OPEN, needs a source audit.** It caused the crash in §2 |
| Spawn-dev / Igor 218–351 | 9 | CommunicationsException, PlayerSync `VanillaSync.onUpdate:758` | 3rd-party (MySQL); memory: playersync-mysql-crash-loop | not Core |
| Spawn-dev 218 | 1 | authlib `findProfilesByNames` | network / Mojang API | not Core |

## 2. Crashes
- **airis 14.10 03:46:35, Core 234, run 122a31ea.** The Mekanism NPE above. Three tick-skips
  exhausted the skip limit, and the server stopped.
  - The stack was lost because of **OmitStackTraceInFastThrow**: the JVM flags are only
    `-Xmx4G -Xms1G`.
  - **Action (owner decision, pending since 2026-10-15):** add `-XX:-OmitStackTraceInFastThrow` to
    island JVM args. This matters for this crash as it did for the AE2 processQueue spin.
- **Spawn-dev ServerHangWatchdog** (>60 s): 02.10, 14.10 ×2 (234), 16.10, 19.10, 22.10 (351).
  - The server thread is in `ForgeEventFactory.onPostLevelTick:955` → a PlayerSync JDBC read or
    connect. The region threads are idle in `RegionThread.awaitDispatch:495`.
  - **Not Core.** It is a blocking DB call on the tick thread (PlayerSync).
  - Mitigation belongs to PlayerSync configuration: DB timeouts / async, or remove it.
- **Extended Terminal 15.0.4** "MyotusAPI has not been initialized yet!" in
  `FMLModContainer.constructMod`. 9× on 234, plus Igor 22.10 on 376; intermittent.
  - **HYPOTHESIS:** an ordering race in mod construction, either FML's parallel construction or a
    NestWorld startup optimisation.
  - Not reproduced. It needs the mod-construction thread / order evidence from a failing start log.
    Check whether it occurs with `-Dnestworld` startup-optimisation features disabled.
- **airis 06.10, Core 234.** `NoClassDefFoundError gg/chaldea/server/fastlogin/RecipeDeferState` in
  `FastLoginMod.onServerStarted:193`. This is a FastLogin mod packaging / version mismatch (a
  missing class in its own jar). Not Core; verify the deployed FastLogin jar.

## 3. Thread dumps
- **Igor 19.10, Core 376.** At startup the server thread waits in `CpuBenchmark.runWorkload:94` →
  `Thread.join`.
  - That is the CPU Capacity Planner's startup benchmark. Waiting there is expected for its
    duration.
  - It is a defect only if the wait is unbounded or long. Duration and timeout to be checked
    against the dump timestamps. UNKNOWN.
- **Igor 15.10 20:24, Core 376.** The 196-line NestWorld region dump. Not analysed here; it needs the
  file.
- **Forced stops (EXPECTED_STOP_FORCED):** Igor 06.10 and 08.10 (234), 15.10 (351); Test 05.10 (234).
  - Prior finding (memory: skyblock-template-core-update): the island stop-hang was a mod, not Core.
  - The matching dumps should be checked for the owning stack before re-attributing.

## 4. Relevance to the AE2 canary
- **AE2 `EnergyService.addRequester` ISE (airis, 26×):** the canary addon contains the ENERGY-001
  fix. If the canary is on an AE2 island, expect 0 of these. That is a direct acceptance signal.
- **QUEUE-001:** the processQueue spin (2026-10-15 incident) is also covered by the addon.
- **Draconic `deSpawnedMobs`, Mekanism `NetworkAcceptorCache`:** unrelated to the addon. They will
  continue on the canary unless pinned, so they must be **classified, not counted as addon
  failures**, in canary acceptance.
