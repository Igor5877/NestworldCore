# MODULE-2 G0 — E1 feasibility probe (results, 2026-10-15)

**Status: MODULE-2 / G0 — PASS / CLOSED (owner, 2026-10-15).** No loader code was written. G1–G7 were not started.

| Item | Status |
|---|---|
| E1 | CONFIRMED |
| Current `registerListener(Object)` | NOT VALID for modules loaded by an external `ModuleClassLoader` |
| Typed `addListener`-style registration | FEASIBLE / VERIFIED on Server thread and NestWorld `RegionThread` |
| Integrated server | NOT TESTED IN G0 — deferred to G4/G5 |

**Verdict:**
- **E1 is CONFIRMED.** `@SubscribeEvent` objects defined by an external class loader never
  receive events.
- **The mechanism differs from the audit's prediction.** The audit predicted a failure at first
  dispatch. The actual failure happens at registration, and EventBus swallows it.
- **The typed `addListener` path works** on both the main thread and a `RegionThread`.

## Setup

| Item | Value |
|---|---|
| Rig | `/root/module-g0-rig` (isolated scratch; ports 25641/25642). The AE2 rig, islands and production were not touched |
| Core | `1.20.1-47.4.306-claude-nestworld-hybrid-arch-invq4h` (the rig's installed build). `ModuleContextImpl.registerListener` is `EVENT_BUS.register(listener)`, as at HEAD |
| JVM | OpenJDK 21.0.12, `-Dnestworld.freeRunningRegions=true`, flat world, 8 cows/pigs in a forceloaded area, all regions free-running (`freerun-all.sh`) |
| Stand-in loader | Harness mod `nwg0harness`, used **only** for this probe. At `ServerStartedEvent` it creates `new URLClassLoader("g0-module", [g0-probe.jar], NestWorldModule.class.getClassLoader())` and loads `dev.nestworld.g0.G0ProbeModule`. It then hands the instance to the **real** `ModuleRegistry.forServer(server).discover(...)` / `enableAll()`, so `context.registerListener(Object)` is the real `ModuleContextImpl` path |
| Probe jar | `nestworld-modules-g0/g0-probe.jar`, plain jar, no `mods.toml`, compiled against the rig's libraries |
| Typed control | In every mode, at `EventPriority.HIGHEST`, the module calls `MinecraftForge.EVENT_BUS.addListener(..., ServerTickEvent.class / LivingTickEvent.class, lambda)` directly. This is the path the proposed typed capability would use. The SPI method does not exist yet |
| Per-hit evidence | thread name and class, TCCL, module loader, listener class and loader, calling class (StackWalker) and its loader, and whether the TCCL can see the listener class (`Class.forName(name, false, TCCL)`) |
| Sources | `tests/module-g0/` (`build.sh`, `g0-run.sh <mode>`) |
| Raw logs | `/root/module-g0-rig/evidence/g0-<mode>-<time>.log` |

## Results (600 server ticks per run after enable)

| Mode | `registerListener(Object)` returned | EventBus registration error | `@SubscribeEvent` deliveries | Typed deliveries main / region | Result |
|---|---|---|---|---|---|
| `lambda` (positive control) | — | 0 | — | 600 / 3833 | **PASS** |
| `subscribe-main` (ServerTickEvent, main) | non-null (Core-created `ResourceHandle`, see step 5) | 1 | **0** | 600 / 8641 | **E1 CONFIRMED** |
| `subscribe-region` (LivingTickEvent, region) | non-null (Core-created) | 1 | **0** | 600 / 13321 | **E1 CONFIRMED** |
| `subscribe-main-tccl` (TCCL = module loader during `register`, the state spec §6.5 proposes) | non-null (Core-created) | 1 | **0** | 600 / 18132 | **TCCL does not fix it** |

Typed region counts grow across runs because cows breed and accumulate in the persisted world.
That has no effect on the verdict.

### Identity evidence (lambda run, identical in all runs)

```
harness   thread=Server thread  TCCL=cpw.mods.modlauncher.TransformingClassLoader@af7e376[TRANSFORMER]
          spiLoader = harnessLoader = TransformingClassLoader@af7e376[TRANSFORMER]
loaded    entryLoader=java.net.URLClassLoader@489668ea[g0-module]  module(javaModule)=unnamed module
onEnable  thread=Server thread TCCL=TransformingClassLoader[TRANSFORMER] identity=g0_probe@0.0.1#gen1
HIT typed ServerTickEvent  thread=Server thread       threadClass=java.lang.Thread
HIT typed LivingTickEvent  thread=NestWorld-Region-0  threadClass=net.nestworld.region.RegionThread
    both: TCCL=TransformingClassLoader[TRANSFORMER]  listenerLoader=URLClassLoader[g0-module]
          caller=net.minecraftforge.eventbus.EventBus  callerLoader=cpw.mods.cl.ModuleClassLoader[MC-BOOTSTRAP]
          TCCLseesListener=NOT_FOUND(ClassNotFoundException)
```

This closes three audit unknowns:
- **U1 (CONFIRMED, dedicated).** A child `URLClassLoader` whose parent is the SPI's loader links
  against the SPI, Forge, EventBus and slf4j. The module runs in an unnamed module. `ModuleRegistry`
  drives it through DISCOVERED → RUNNING (`g0_probe state=RUNNING`).
- **U5 (CONFIRMED).** The TCCL of the main thread **and** of `RegionThread` is the GAME-layer
  `TransformingClassLoader`. It cannot see module classes.
- **U2 (CONFIRMED, different mechanism).** See below.

### Failure path (subscribe-main; the region run is identical apart from the event)

```
[Server thread/ERROR] [EVENTBUS]: Error registering event handler: class TickEvent$ServerTickEvent
    public void dev.nestworld.g0.SubscribeServerTickListener.onServerTick(TickEvent$ServerTickEvent)
java.lang.ClassNotFoundException: dev.nestworld.g0.__SubscribeServerTickListener_onServerTick_ServerTickEvent
  at cpw.mods.cl.ModuleClassLoader.loadClass ... BuiltinClassLoader.loadClass
  at net.minecraftforge.eventbus.ModLauncherFactory.createWrapper(ModLauncherFactory.java:19)
  at net.minecraftforge.eventbus.ClassLoaderFactory.create(ClassLoaderFactory.java:25)
  at net.minecraftforge.eventbus.ASMEventHandler.<init>(ASMEventHandler.java:37)
  at net.minecraftforge.eventbus.EventBus.register(EventBus.java:128)
  at net.nestworld.module.ModuleContextImpl.registerListener(ModuleContextImpl.java:99)
  at dev.nestworld.g0.G0ProbeModule.onEnable(G0ProbeModule.java:45)
  at net.nestworld.module.ModuleRegistry.enableOne → enableAll
```
In the TCCL variant, the stack shows `URLClassLoader.findClass` (the module loader, after its
parent also refused), with the same `ClassNotFoundException`.

## Corrected mechanism (CONFIRMED: run evidence plus `javap -c eventbus-6.0.5.jar`)

The audit's E1a/E1b analysed `ClassLoaderFactory$ASMClassLoader`. That is EventBus's fallback for
running **without** ModLauncher. Under FML the factory is `ModLauncherFactory`:

1. `createWrapper(method)` puts the `Method` into a static `PENDING` map. The key is the unique
   name `<listener package>.__<Listener>_<method>_<Event>`.
2. It then calls `Class.forName(uniqueName, true, getClassLoader())`, where `getClassLoader()` is
   the **TCCL**, falling back to the GAME-layer loader.
3. The wrapper is **synthesised by EventBus's ModLauncher launch plugin** (`ModLauncherService` /
   `EventBusEngine`) when ModLauncher's `TransformingClassLoader` is asked to load that name.
   ModLauncher can only do this for classes in packages owned by the GAME layer.
4. The wrapper name sits in the module's package (`dev.nestworld.g0`). The GAME layer owns no such
   package, so the load is delegated and fails with `ClassNotFoundException`. With TCCL = module
   loader, it is refused by the parent and then not found in the jar.
5. `MinecraftForge.EVENT_BUS.register(listener)` **completes without throwing**: the
   `ClassNotFoundException` is caught and logged inside EventBus. EventBus itself returns no handle
   at all (`register` is `void`).
   - The `ResourceHandle` is created **by our `ModuleContextImpl`** after `register` returns, and
     recorded in the module's ledger.
   - That is why Core wrongly treats the registration as successful. The defect is Core's: it
     takes "no exception" to mean "wired". The module receives nothing.

Consequences:
- **Corrected E1a.** The failure is deterministic, at registration, on the registering thread. It
  does not depend on which thread posts later. It is **silent towards the module**: no exception
  and a non-null `ResourceHandle` that Core itself created.
- **E1b (shared `ASMClassLoader` FQCN collision) is MOOT.** That loader is not used under FML, and
  registration fails before any wrapper exists.
- **TCCL around `onEnable` (spec §6.5) does not rescue `@SubscribeEvent`.** §6.5 stays valid for
  libraries that need the TCCL, but it is not a listener mechanism.
- The failure cannot be fixed by loader configuration. Wrapper synthesis is tied to GAME-layer
  packages, and module jars are deliberately outside ModLauncher (audit L3).

## What this decides (for owner review)

1. **The typed listener API is mandatory in MODULE-2.**
   `registerListener(Class<E> type, EventPriority, boolean receiveCancelled, Consumer<E>)` over
   `EVENT_BUS.addListener` was proven on main and on `RegionThread`.
2. **`registerListener(Object)` must be fail-closed for jar-loaded modules.** Discouraging it is
   not enough: in practice it is silent loss.
   - For a module loaded by a `ModuleClassLoader`, return `null` (REJECTED) with a `ModuleFailure`
     that names the reason.
   - Accepting it would record a resource that delivers nothing.
3. Built-ins still work through the `Object` form, because their package is in the GAME layer.
   FastTransition uses `registerListener(this)` with `@SubscribeEvent`. **G7 therefore requires
   FastTransition to move to the typed form**, which is consistent with spec §8.3's single
   listener path.
4. A residual hazard exists outside MODULE-2's API: a jar module calling
   `MinecraftForge.EVENT_BUS.register(obj)` directly. It is trusted code (spec §12), so this can't
   be prevented. The module author guide documents it as non-functional. Gate A (§8.2) does not
   cover Forge members.

## Not covered by G0

- **Integrated server.** The owner narrowed G0 to main plus `RegionThread` on a dedicated server.
  Integrated linkage stays in G4/G5.
- Any loader, descriptor or validation code: not written.
