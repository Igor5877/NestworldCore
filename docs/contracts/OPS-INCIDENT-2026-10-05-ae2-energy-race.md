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

## 2026-10-14: full crash (Mekanism) and AE2 tally (from crash-reports read by the owner, read-only)
**AE2 EnergyService race keeps recurring.** Same signature in every report:
- 2026-10-06: 20:01:08, 20:01:26, 20:18:15, 20:58:23;
- 2026-10-07: 01:27:42, 23:18:53, 23:19:49, 23:21:17;
- 2026-10-11: 16:19:57, 19:23:05, 19:48:46;
- 2026-10-12: 22:25:17, 22:28:56, 22:41:54, 23:10:47, 23:11:15, 23:27:07 (the last three are not
  fully shown but carry the same description).

Each one is a skipped tick; none was a full crash.

**New race, 2026-10-14, full server crash.**
- Three skipped ticks in two seconds (03:46:33, 03:46:34, 03:46:35), then the tick-loop circuit
  breaker stopped the server (`crash-2026-10-14_03.46.35`).
- Only the first report, 03:46:33, has a stack. The later ones are stackless because the JIT had
  started throwing that NPE without a stack trace (OmitStackTraceInFastThrow):

```
java.lang.NullPointerException: Cannot invoke "it.unimi.dsi.fastutil.objects.ObjectArrayList.get(int)" because "this.wrapped" is null
  at fastutil Object2ObjectOpenHashMap$MapIterator.nextEntry(631) / EntryIterator.next(725)
  at mekanism.common.lib.transmitter.acceptor.NetworkAcceptorCache.commit(NetworkAcceptorCache.java:63)
  at mekanism.common.lib.transmitter.DynamicNetwork.commit(DynamicNetwork.java:72)
  at mekanism.common.lib.transmitter.TransmitterNetworkRegistry.commitChanges(253) / onTick(140)
  at ForgeEventFactory.onPostServerTick -> MinecraftServer.tickServer (Server thread, END of tick)
```

**Reading (from the stack and the known pattern; not reproduced):**
- Main, at the END of the server tick, iterates a network's acceptor-cache fastutil map. A null
  `wrapped` in the fastutil iterator is the signature of that map being modified concurrently
  during the iteration.
- **Hypothesis:** a region thread changed the same Mekanism network's acceptors at the same time,
  for example a transmitter or neighbor update from a block entity ticking in a free-running
  region.
- It is the same structural class as the AE2 race: a mod's main-thread END-of-tick handler racing
  with region threads that mutate the same mod network.
- **Not yet in `docs/RACE_CLASSIFICATION.md`**, which lists the Mekanism `flowingStacks`,
  `PathfinderCache` and `FrequencyManager` races, but not `NetworkAcceptorCache`.
- `2026-10-14-1.log.gz` and `debug-1.log.gz` contain no matching NPE line.

**Island state at that time:**
- Core 47.4.234. Inferred to be about `d405f5187` (2026-09-29) from the build numbering; there is
  no recorded provenance.
- `freeRunningRegions` and `autoFreeRunning` on.
- The JVM sees 30 CPUs.
- JVM flags only `-Xmx4G -Xms1G` (no `-XX:-OmitStackTraceInFastThrow`).

## Auto-pin state on the island (read by the owner, 2026-10-14)
`user_jvm_args.txt` has `-Dnestworld.autoPin=true` and `-Dnestworld.autoPinThreshold=2` (marked as
temporary for a test, 2026-09-23), with free-running on.

| Pin | When (Europe/Kyiv, from pin reasons) | What followed |
|---|---|---|
| `mod:ae2` (caught-elsewhere, TickHandler.onServerTickEnd) | 2026-10-06 18:01:53 | AE2 race **recurred 17 more times** (10-06 20:01 … 10-12 23:27) |
| `be:pipez:energy_pipe` (own-tick error) | 2026-10-12 23:45:37 | **no AE2 race after it** |
| `entity:botania:mana_burst` | 2026-10-06 19:29:31 | — |
| `mod:mekanism` (caught-elsewhere, NetworkAcceptorCache NPE) | 2026-10-14 03:46:33, on the first NPE | **two more NPEs** (03:46:34, 03:46:35), then the circuit-breaker crash |

Pinned mods: `ae2`, `edexpandere`, `exdeorum`, `mekanism`.

**Readings:**
- **Auto-pin works as designed.** It attributes the mod and pins it.
- **Pinning the mod whose handler throws does not stop these races.** For AE2 and for Mekanism the
  race went on after the pin. The mutating side is evidently not that mod's own entities or block
  entities.
- **Pipez may be the AE2 injector.** The AE2 race stopped after `pipez:energy_pipe` was pinned.
  This is a correlation, not proven: Pipez energy pipes push FE into AE2 acceptors from their
  own tick.
- **Structural mitigation (owner decision):** free-running off on islands. Barrier-mode region
  rounds finish before the main thread's END-of-tick handlers, so this class cannot overlap
  whichever mod injects. Recommended together with `-XX:-OmitStackTraceInFastThrow`, keeping the
  existing pins.
