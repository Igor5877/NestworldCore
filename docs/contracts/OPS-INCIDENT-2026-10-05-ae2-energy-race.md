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
