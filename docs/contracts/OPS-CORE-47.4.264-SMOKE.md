# OPS — Core 47.4.264 operational smoke (SkyBlock island) — record

**Status: PASS (2026-10-04).** No Core change. No production rollout. Existing islands unchanged.

## State
| Item | Status |
|---|---|
| SOURCE-PATCH-INTEGRITY-001 | CLOSED / VERIFIED / FROZEN |
| 47.4.264 trusted artifact | VERIFIED: `Git-Commit dab499ff`, `Git-Dirty false`, `Source-Patch-Integrity MATCH`; installer sha256 `5d0fe391…06b1d` |
| 47.4.264 canary | 4/4 PASS (reobfJar, installed server jar, core bundle) |
| 47.4.264 lifecycle smoke | PASS |
| Core regression vs 47.4.233 | NONE OBSERVED |
| SkyBlock production rollout | NOT STARTED |
| Existing islands | UNCHANGED |
| PERF-1 | READY; reference build = 47.4.264 (`dab499ff`) |

## Lifecycle smoke (isolated copy of the island template)
Isolation:
- copy of `/root/skyblock-island-deploy/extracted/minecraft`; the original was not modified;
- process cwd confirmed via `/proc/<pid>/cwd`;
- `apiBaseUrl` → `127.0.0.1:9`;
- PlayerSync absent (no jar; its config removed in the copy);
- `-Dnestworld.bypassForgeHandshake=true` in the copy only, for the bot.

Every external call failed locally with ConnectException; there were no calls to shared infrastructure.

| Step | Result |
|---|---|
| startup | ABI gate PASS, modules RUNNING, TPS 20 |
| join | PASS |
| overworld → nether → overworld | PASS (dimension changes observed) |
| save-all / stop / start | PASS |
| rejoin | PASS: dimension and a pre-stop entity tag persisted |
| error profile | same sources as 47.4.233 (166 ERROR / 4 FATAL, all mod-side); nothing new from Core |

## Known external defect — MODS-SERVER-LIFECYCLE-001 (registered, not a Core gate)
- **Component:** `nestworld-mods-server-1.3.0.jar`, `com.skyblock.dynamic.events.PlayerEventHandler`.
- **Defect:** `Executors.newSingleThreadScheduledExecutor()` (non-daemon) is never shut down. A
  player logout schedules `freezeTask` (minutes).
- **Effect:** after any player lifecycle, `/stop` does not end the JVM; Core's forced-exit watchdog
  ends it after 90 s.
- **Evidence:** the thread dump shows the only non-daemon thread is `pool-3-thread-1` in
  `ScheduledThreadPoolExecutor.take`. Without a join, stop takes under 1 s.
- **Reproduced identically on 47.4.233 and 47.4.264**, so this is not a Core regression.
- The mod owner reports it is fixed in newer mod versions.

## Registered separately (not part of PERF-1; no action now)
- **DEPLOY-CONFIG-INTEGRITY-001.** `mods.zip` and the deployed `extracted/minecraft/mods` differ:
  - zip only: `playersync-1.20.1-2.1.5.jar`, `proxy-compatible-forge-1.1.7.jar`;
  - deployed only: `nestworld-mods-server-1.3.0`, `nestworld-teams-addon-1.0.0`,
    `nestworldmetricsexporter1.0.0`.

  Not synchronised now, to keep the PERF-1 baseline clean.
- **CORE-DEPLOY-UNIT-001.** The SkyBlock Core deployment unit is **not** `mods.zip`, which contains
  only mods. It is a versioned Forge/Core bundle:
  ```
  libraries/net/minecraftforge/{forge,fmlcore,fmlloader,fmlearlydisplay,
            javafmllanguage,lowcodelanguage,mclanguage}/1.20.1-<ver>/   (10 files)
  run.sh
  CONTENTS.sha256
  ```
  The installer changes no other library (measured: 47.4.233 vs 47.4.264). The bundle must become
  the atomic input of the deployment pipeline, not 11 files copied by hand. Bundle for 47.4.264:
  `nestworld-core-1.20.1-47.4.264.zip`, sha256 `87f06c3b41ca09b917c3b869a4fc6e14fd7634d45b69725dc05ab9b43b93109e`.
- **Old-islands inventory.** A read-only per-island line (running state, Core version, server-jar
  hash, provenance, canary) is pending; it is run on the LXD host. Rolling replacement is decided
  after it.
