# AE2-COMPAT-ENERGY-001 — glue smoke and T2 acceptance fixtures (results)

**Status: glue implemented (commit `27ddb5212`). Gate item 1 (deterministic tests) and the T2 part of
item 2 (transition-race tests) PASS on an isolated scratch server.**
- Remaining gate items are not run yet: 001.3 §8 items 3–11.
- Production unpin stays NOT approved; full AE2 unpin stays BLOCKED (QUEUE-001,
  LEGACY-ORIENTATION-001).

## Environment
- **Server:** `/root/compat-ae2-rig/server`, Core 47.4.306 (the PERF-1B trusted build), ports
  25631/25632, flat world. It contacts no shared infrastructure.
- **Mods:** AE2 15.4.10 (`fbfee05c…`), GuideME 20.1.14, Pipez 1.2.26, and the addon.
- **Addon jar:** `nestworld-compat-ae2-0.1.0-all.jar`, sha256 `470b2ce1…728caf79` (built from this
  commit).
- **Fixtures:** enabled with `-Dnestworld.compatAe2.fixtures=true` and run with
  `/nwcompatae2 fixture <stale|wake|alert|self>`. Code: `glue/T2Fixtures.java`.
- **How a fixture works:** a non-world AE2 managed node carries a controllable `IGridTickable`. Its
  third device callback runs on main inside a real T2 suspension. During that callback a foreign
  thread acts on the same node, through the real wrapped `TickManagerService` entries.

## Smoke
- **Startup gate:** PASS (14 642 classes scanned in 1.5–1.7 s). Every injection applied, with the
  refmap for `Level`.
- **First boot failure (fixed):** the `addNode` admission target was declared as returning `Object`.
  The bytecode returns `IGridNodeService`. Every other `@WrapOperation` target was then checked
  against `javap`.
- **Network test:** controller, energy cell, creative cell, acceptor and inscriber, plus 5 rounds of
  cell break/place churn, plus a charger with a ticking item.
  - Mode NORMAL throughout; 5 600+ guarded entries.
  - 0 refusals, 0 unaudited paths, 0 main-affinity violations.
  - 30/30 T2 suspensions resumed; power flowed (charger `internalCurrentPower = 1600`).

## T2 acceptance fixtures (Design-1.5 §3.4)
| Fixture | Scenario | Result |
|---|---|---|
| STALE (owner test) | During the callback a foreign thread runs `removeNode` and then `addNode` on the same node, installing a new tracker | **PASS.** Exactly 1 tracker in the queue and it is the new one; the stale tracker is not re-queued; no node ticked twice in a server tick; ticking continues every 10 ticks |
| WAKE (lost wake) | The device returns SLEEP; a foreign `wakeDevice` arrives during the callback | **PASS.** The wake returns `true`; the node stays awake and keeps ticking |
| ALERT (same-phase double tick) | Foreign `alertDevice` during the callback | **PASS.** Returns `true`; first tick after the trigger is exactly `trigger+1`; never twice in one server tick (asserted from a tick log keyed by server tick, independent of `PriorityQueue` order) |
| SELF | The callback calls `sleepDevice(self)` and returns IDLE | **PASS.** Returns `false` (vanilla); the node stays awake with exactly 1 queued tracker |

**Negative controls.** Each is a temporary source patch (`rig/negative.sh`), built, run once and
reverted. The source tree was verified clean afterwards.

| Control | Patch | Fixture | Result |
|---|---|---|---|
| identity | continuation gated only by AE2's `containsKey` | STALE | **FAIL as required:** 2 trackers in the queue, zombie re-queued, the node ticked twice in each period |
| suppress | AE2 `currentlyTicking` suppression kept for foreign threads | WAKE | **FAIL as required:** `wake=false`, node asleep, never ticked again (lost wake) |
| lasttick | no `lastTick := currentTick` on rescheduling the suspended tracker | ALERT | **FAIL as required:** node ticked twice in server tick 45 |

## Counter semantics observed
`t2StaleTracker` counts every invalidated continuation, not only removal and re-add. In the run it
was 3:
- STALE: identity changed;
- WAKE: generation changed by the foreign wake;
- ALERT: generation changed by the foreign alert.

This is intended: an intervening scheduling operation always wins.

## Recorded deviation (as designed in 1.5 §3.3)
After a foreign alert or wake lands mid-tick, the device's own modulation for that tick is discarded.
In the ALERT fixture the node therefore stays at rate 1, because the alert set the rate to 1 and the
device's IDLE was not applied. It remains there until the device returns a different modulation.
Devices already accept this from `alertDevice`.

## Gate item 4 — race reproduction, A/B (2026-10-14)
**Rig:** the same scratch server, with Mekanism 10.4.16.80 added (startup gate PASS, 17 788
classes), `-Dnestworld.freeRunningRegions=true`, region #0 set to free-running.
- Grid: a controller and 40 energy cells.
- Inputs: 20 energy acceptors, each fed by a Mekanism creative energy cube that ejects FE from its
  own block-entity tick on the region thread.
- Main thread: AE2's E8 tick plus churn (cell break/place over RCON). Scripts:
  `rig/build-race.sh`, `rig/drive-race.sh`.

| Run | Result |
|---|---|
| **Addon disabled** (`-Dnestworld.compatAe2.disable=true`) | **Race reproduced in about 30 s**: `IllegalStateException: Cannot modify energy requesters while energy is being injected` (13×), via `EnergyCellBlockEntity.emitPowerEvent → GridEventBus → EnergyService.storagePowerChangeHandler → addRequester` on main while the region thread's injection held AE2's `ongoingInjectOperation`. Reached as "Readying AE2 block entity" and "Ticking grid on end of server tick". 6 tick failures in 60 s ended in a full crash shutdown after 17 churn rounds. Log: `/root/compat-ae2-rig/evidence/race-baseline-addon-disabled.log` |
| **Addon active** | **180 s, 5 101 churn rounds, 0 exceptions, TPS 20.** Mode NORMAL; 156 344 guarded outer entries; `contended=785`, `retries=11`; 0 refusals, unaudited paths or main-affinity violations; 5 751/5 751 T2 suspensions resumed; 491 701 post-commit deliveries. Log: `/root/compat-ae2-rig/evidence/race-addon-active.log` |

Confidence:
- CONFIRMED: the addon removes this race class (the island's EnergyService injection-flag race) in
  this rig.
- Scope, first variant: the input path is a Mekanism cube pushing FE from its own block-entity tick
  on the region thread. It enters AE2 through the external `receiveEnergy → funnelPowerIntoStorage →
  injectPower` path.

**Pipez variant (the island path).** Each cube ejects into an extracting Pipez energy pipe, and
Pipez routes the energy into the AE2 acceptor (`EnergyPipeType.receive → receiveEnergy`) on the
region thread. Scripts: `rig/build-race-pipez.sh`, `rig/drive-churn.sh`. Each run lasted 180 s.

| Run | Result |
|---|---|
| Addon disabled | Race reproduced: 4× `Cannot modify energy requesters while energy is being injected`, via "Readying AE2 block entity". The server survived through Core catch-and-continue (fewer than 6 failures per 60 s). 5 142 churn rounds. Log: `evidence/race-pipez-baseline-addon-disabled.log` |
| Addon active | **0 exceptions.** 5 184 churn rounds, TPS 20, NORMAL. 728 452 guarded entries, `contended=1 382`, `retries=11`; 0 refusals, transitions, unaudited paths or main-affinity violations. Log: `evidence/race-pipez-addon-active.log` |
- Only one region (single region thread versus main) was tested. Multi-region cases are gate items
  5 and 6.

## Gate items 5–7 — several free-running regions (2026-10-14)
**Setup:** region #0 split into 4 pinned quadrant regions (#3–#6), all free-running.
- Scripts: `rig/build-grid.sh` (one grid = controller, 20–50 cells, and acceptors fed by
  region-thread creative cubes) and `rig/drive-multi.sh` (rotating cell break/place on main).
- Each run lasted 120 s.

| Item | Setup | Result |
|---|---|---|
| 6, different-domain parallelism | 4 independent grids, one per region, injected concurrently by 4 region threads | 3 606 churn rounds; **0 exceptions**; NORMAL; TPS 20; +459 081 guarded entries; `contended +2 102`; `retries +275` (validation re-resolves during churn); 0 refusals, unaudited paths, transitions or main-affinity violations |
| 5, same-domain contention | 1 grid (50 cells) crossing the #3/#4 boundary (block x 15/16), injected from both region threads and churned on main | 3 619 churn rounds; **0 exceptions; no deadlock**; TPS 20; +727 330 entries; `contended +1 123`; `retries 0`; 0 refusals, unaudited paths or transitions |
| 7, topology churn under load | cell break/place during injection, in every run above (gate 4 included: 5 101 to 5 184 rounds) | no exception in any addon-active run |

What these runs do **not** show:
- **Throughput.** The load is tiny (region ticks ≈ 0.0–0.1 ms), so this is correctness under
  concurrency, not a parallel-speedup measurement. "Parallelism" here means only that, by
  construction, the four domains share no lock in NORMAL.
- **Contention source.** `contended` does not separate main-versus-region contention from
  region-versus-region contention.

Logs: `/root/compat-ae2-rig/evidence/multi-region-*.log`.

## Next (001.3 §8)
1. Deadlock tests.
2. AE2/Pipez reproduction of the island race.
3. Same-domain contention.
4. Different-domain parallelism.
5. Topology churn under load.
6. SolarFlux conservative case.
7. Soak.
8. Shadow/canary.
9. Unpin review (blocked by QUEUE-001 and LEGACY-ORIENTATION-001 for whole-AE2).
