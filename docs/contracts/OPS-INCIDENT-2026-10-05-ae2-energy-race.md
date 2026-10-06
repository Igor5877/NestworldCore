# OPS-INCIDENT 2026-10-05 — AE2 energy race on a live island; island CPU / free-running exposure

**Status: RECORDED (owner: record, do not reproduce now).** No action has been taken on any live
server. The race analysis is in `docs/RACE_CLASSIFICATION.md` (AE2 `EnergyService`, item 17).

## Event
| Item | Value |
|---|---|
| Island | `skyblock-team-island-of-airisminhistory-64aa241d-25` (`/opt/minecraft`), 1 player online |
| Time | 2026-10-05 20:44:23; crash report `tick-skipped-2026-10-05_20.44.23-server.txt`, UUID `bb676463-b7c3-4380-a9e9-985fad48ba74` |
| Effect | AE2 `TickHandler.onServerTickEnd` threw `IllegalStateException: Cannot modify energy requesters while energy is being injected` |

- NestWorld's tick-loop catch-and-continue skipped that tick, and the server kept running.
- The grid's energy state after the skipped tick may be inconsistent (a lost power-state event);
  this is not verified.

## Analysis (bytecode plus report; no reproduction)
- AE2 15.4.10 sets `ongoingInjectOperation` in `injectPower` and resets it in `finally`, so it
  cannot stay set after an exception.
- Main's stack has no `injectPower`. Another thread was therefore injecting into the same grid
  while main ran the grid's end-of-tick.
- **Hypothesis:** an injecting machine ticked in a free-running region, overlapping main.
- **Not proven:** the report holds only the server-thread stack.

## Island configuration found
- **Core:** `47.4.234-claude-nestworld-hybrid-arch-invq4h`, NestWorld Core Commit `unknown`, so
  there is no provenance.
  - It is neither the template's 47.4.233 nor the verified 47.4.264.
  - Patch integrity and the SPI canaries are unknown for this build.
  - Modpack v0.0.32.
- **JVM:** `-Xmx4G -Xms1G`. `user_jvm_args.txt` has `-Dnestworld.freeRunningRegions=true` and
  `-Dnestworld.autoFreeRunning=true`.
- **CPU:** `nproc` = 30 and `cpu.max` = `max` (no quota). The JVM sees 30 CPUs; the crash report's
  OSHI section reports "Number of logical CPUs: 1".
- **Free-running safeguard defeated.** The template assumes 3 vCPUs: its comment says "(3-2=1)
  region in free tick", with `FREE_RUNNING_RESERVED_CORES=2`. With 30 visible CPUs the cap is
  **28** free-running regions. Other values sized from the CPU count follow it too:
  `maxRegions` defaults to 2×30 = 60, and the split target to 29.
  - Nobody decided this; it changed with the container's CPU set.
  - More free-running regions mean more overlap windows such as this AE2 race, and make the
    open P4 defect (free-running work stuck after demotion, CORE-WORK-RETIREMENT-001) more
    reachable.
- **History:** 20 earlier `tick-skipped` reports on 2026-09-29 (20:03–20:25) read "Exception in
  server tick loop". Their cause was not examined.

## Open items (owner decisions; nothing done)
- **Islands:** disable `autoFreeRunning`, or pin island Core sizing explicitly
  (`-Dnestworld.maxRegions`, `-Dnestworld.freeRunningReservedCores`) to the island's real CPU
  budget.
- **Island inventory** (`island-inventory.sh`): Core version and provenance, CPU visibility and
  quota, free-running flags per island.
- **Reproduction** of the AE2 race on an isolated island copy: deferred by the owner.
- **Cause of the 2026-09-29 tick-skipped series:** not examined.

## Recurrence (log pasted by the owner on 2026-10-06; the lines carry the time only)
Same island (the player `64aa241d-98a0-11f0-bdfb-22014d09235b` is online and PlayerSync saves every
minute), same Core `47.4.234`, same signature. There were **three** skipped ticks in 17 minutes:

| Time | Crash report UUID |
|---|---|
| 20:01:08 | `d3ca4580-ab15-4fd9-9006-1e30d495ba81` |
| 20:01:26 | `b3547036-7a91-4288-99ca-310a227baaec` |
| 20:18:15 | `a35f5aaa-8e53-42f5-b6fc-8643f2199621` |

Stack, identical in all three:
`TickHandler.onServerTickEnd` → `Grid.onServerEndTick` → `EnergyService.onServerEndTick` →
`extractAEPower` → `extractProviderPower` → `AEBasePoweredBlockEntity.extractAEPower` →
`StoredEnergyAmount.extract` → `setStored` → `sendEvents` → `ControllerBlockEntity.emitPowerStateEvent` →
`Grid.postEvent` → `GridEventBus.postEvent` → `EnergyService.storagePowerChangeHandler` →
`addRequester` → `IllegalStateException: Cannot modify energy requesters while energy is being injected`.

Each time the tick-loop catch-and-continue skipped one tick, and the server kept running.

Facts added by this recurrence (still no reproduction):
- **It recurs.** Three occurrences in 17 minutes on the same grid setup; it is not a one-off.
- **The extract path itself raises the event.** Main is *extracting* (end-of-tick), and a
  controller power-state change during that extraction calls `addRequester` while
  `ongoingInjectOperation` is set. Under the hypothesis, that flag belongs to a concurrent
  `injectPower` on another thread. The AE2 15.4.10 bytecode check (the flag is reset in
  `finally`) still rules out a flag stuck on main's own thread.
- **The C1 transformer is present but not in the chain.** `GridEventBus.postEvent` carries
  `xf:fml:forge:nestworld_static_collection_race_transformer`. Our C1 static-collection transformer
  is applied to that class, but it does not cover EnergyService's injection state, and nothing
  shows that it plays a role here.
- **Still unknown:**
  - which thread was injecting (the reports hold the server-thread stack only);
  - whether grid energy state stays consistent after each skipped tick.

Open items unchanged: reproduce on an isolated island copy (deferred by the owner), island
free-running sizing, island inventory. A thread dump taken at the moment of the exception would
name the injecting thread. The existing island dump tooling only triggers on hangs, not on
skipped ticks.
