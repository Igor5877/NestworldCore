# MODULE-2 — External Module Loader: audit (2026-10-15)

**Status: AUDIT — stop for owner review. No loader code is written.**
- Companion spec: `docs/contracts/MODULE-2-EXTERNAL-LOADER-SPEC.md`.
- Scope:
  - what the current module system (MODULE-1 + MODULE-LIFECYCLE-001 + FAST-TRANSITION) gives an
    external jar;
  - what it lacks;
  - which runtime facts bind the loader's design.
- Confidence words: CONFIRMED (code or bytecode read), STRONGLY INDICATED, HYPOTHESIS, UNKNOWN.

## 1. Target

```
server/nestworld-modules/*.jar          (not mods/)
third-party project: implements NestWorldModule, builds a jar, drops it in → restart → RUNNING
no Core rebuild; closed NestWorld modules use the same public mechanism (no Core backdoor)
```

## 2. What exists today (CONFIRMED)

### 2.1 SPI surface — `src/main/java/net/nestworld/module/`

These are the public types a module sees:

| Type | Kind | Non-JDK dependencies |
|---|---|---|
| `NestWorldModule` | interface: `descriptor()`, `onEnable(ModuleContext) throws Exception`, default `onDisable()` | — |
| `ModuleDescriptor` | record `(ModuleId id, ModuleVersion version, String displayName)` | — |
| `ModuleId`, `ModuleVersion` | string records | — |
| `ModuleIdentity`, `ModuleGeneration` | records | — |
| `ModuleContext` | interface: `identity`, `ownsServer(MinecraftServer)`, `registerLoginBudget`, `prepareChunks`, `snapshotSyncData`, `registerListener(Object)`, `registerCommand(String, Command<CommandSourceStack>)` | `com.mojang.brigadier.Command`, `net.minecraft.commands.CommandSourceStack`, `net.minecraft.server.MinecraftServer` |
| `ResourceHandle` | interface | — |
| `LoginBudget`, `SyncDataSnapshot` | records | — |
| `ChunkPreparationRequest` | record | `net.minecraft.resources.ResourceLocation` |
| `ChunkPreparation` | interface | — |
| `ContentGeneration`, `ModuleExecutionContext`, `ModuleLifecycleState`, `ModuleFailure` | value types / enums | — |

Implementation types live in the **same package**:
- package-private: `ModuleContextImpl`, `ModuleRuntime`, `ResourceLedger`,
  `ModuleChunkPreparations`, `ModuleSyncData`;
- public, but Core-side by intent: `ModuleRegistry`.

Facts:
- **A1 (CONFIRMED).** No type in `net.nestworld.module` imports `net.nestworld.region.*`.
  - The only textual hit is a javadoc line at `ModuleContext.java:32`.
  - The SPI is therefore separable from Core internals at the type level.
  - It still depends on Minecraft, Forge, and Brigadier types, which is acceptable because those
    are parent-owned (spec §4).
- **A2 (CONFIRMED).** `ModuleRegistry` is public. An external module, which is trusted code (spec
  §12), *can* call `ModuleRegistry.create/forServer/dispose`.
  - Nothing distinguishes "SPI" from "Core API" inside the package except documentation.
  - The published SPI surface must therefore be defined by an explicit list (spec §8), not by
    the package.
- **A3 (CONFIRMED).** `FastTransitionModule` (`net.nestworld.module.fasttransition`) imports only:
  - `net.nestworld.module.*`;
  - Minecraft, Forge, Gson, and slf4j.

  It has zero `net.nestworld.region` references, so it is a real candidate for the first
  first-party module loaded through the public mechanism (spec §13, gate G7). It uses
  `registerListener(this)` with `@SubscribeEvent` (`FastTransitionModule.java:36,74`). That path
  is affected by finding E1.

### 2.2 Wiring

- **A4 (CONFIRMED).** Discovery is hard-wired. `NestworldRegionSystem.startModules`
  (`NestworldRegionSystem.java:296`) runs at `ServerStartingEvent` (`:207`):
  - `discover(new ExampleModule())`;
  - `discover(new FailingExampleModule())`;
  - `discover(new FastTransitionModule())` only when `-Dnestworld.fastTransition=true`;
  - then `enableAll()`.

  There is no jar scanning, no classloader, no descriptor file, and no SPI version.
- Shutdown:
  - `quiesceAll()` runs at stopping (`:233`);
  - `ModuleRegistry.dispose(server)` runs at stopped (`:259`).
- `RegisterCommandsEvent` only re-binds through `bindCommands` (`:280-291`) and never discovers.
  This satisfies MODULE-LIFECYCLE-001 I3 and must be preserved.

### 2.3 Registry behaviour that an external loader inherits

| # | Fact (CONFIRMED) | Where | Consequence for MODULE-2 |
|---|---|---|---|
| A5 | `discover` throws `IllegalStateException` on a duplicate `ModuleId` | `ModuleRegistry.java:127-136` | An external duplicate would abort `startModules`, and with it every later module. The loader must de-duplicate **before** `discover` (spec §10). |
| A6 | The `modules` map is a `LinkedHashMap`; `enableAll` and `quiesceAll` iterate in **discovery order** | `:65`, `:141`, `:207` | There is no dependency order. Disable order is not the reverse of enable order. With dependencies, the spec requires topological enable order and reverse disable order (§9). |
| A7 | Generation counters are a JVM-static `Map<ModuleId, AtomicLong>` | `:54` | Generation increases per server lifetime within one JVM (integrated A→B). This is correct and stays as is. |
| A8 | `BY_SERVER` is a `WeakHashMap<MinecraftServer, ModuleRegistry>`, and `dispose` exists | `:58`, `:96` | The per-server lifetime is already modelled (I5). The class-loader lifetime is not, because no loader exists yet. |
| A9 | The descriptor is obtained by **calling module code**: `module.descriptor()` | `:129` | For an external jar the static descriptor must be readable **without loading any class** (§5). `descriptor()` becomes a cross-check. |
| A10 | `enableOne` catches `Exception` around `onEnable` | `:155-168` | `LinkageError`, `NoClassDefFoundError`, and `ExceptionInInitializerError` from an external jar are `Error`s. They would currently escape and abort `startModules`. |
| A11 | `registerCommand` binds via `dispatcher.getRoot().addChild(...)` with **no name-collision check** | `ModuleContextImpl.java:59`, `:170` | Brigadier `CommandNode.addChild` merges same-named literals and replaces the executor. A module registering `nestworld` would silently hijack Core's root command executor, or another module's command. Built-ins never collided, so this was latent. External jars make it reachable. |
| A12 | `registerListener(Object)` uses `MinecraftForge.EVENT_BUS.register(listener)` | `ModuleContextImpl.java:149` | See E1. |

## 3. Runtime facts that bind the classloader design

### 3.1 JPMS / ModLauncher layer

- **L1 (CONFIRMED, PKG-002).** Core classes, `net.nestworld.module` included, are compiled into
  `:forge`'s single source set and ship in NestWorld's `forge` module in the GAME layer.
  - PKG-002's `ResolutionException: Modules nwinvprobe and forge export package …` is evidence
    that the layer resolves packages per named module and rejects split packages.
  - A module jar placed in `mods/` that contains `net.nestworld.module` classes would hit the same
    failure.
  - **Consequence: module jars must never contain SPI classes, and must not live in `mods/`.**
- **L2 (STRONGLY INDICATED).** The natural parent loader for a module is
  `NestWorldModule.class.getClassLoader()`, the GAME-layer `TransformingClassLoader`.
  - From there, Minecraft, Forge, Brigadier, Gson, slf4j, and the SPI are all visible.
  - A plain child `URLClassLoader` defines its classes in its own **unnamed module**. An unnamed
    module reads every module.
  - Access then depends only on exports. The Forge/Minecraft modules are automatic modules,
    which export every package.
  - This is to be proven by gate G0 before the loader is written. It has not been run.
- **L3 (CONFIRMED by construction).** Classes defined by a child loader outside ModLauncher are
  **not transformed**. That rules out:
  - Mixin;
  - access transformers;
  - coremods;
  - `EventSubclassTransformer`;
  - runtime enum extension.

  Module jars cannot rely on any of these. The spec makes them fail-closed (§6.4) instead of
  silently not applying.
- **L4 (CONFIRMED, PACKAGING/PKG-002).** The production runtime uses SRG member names, as for mods.
  A module jar compiled against Minecraft members must be reobfuscated, exactly like a mod jar.
  Otherwise `NoSuchMethodError` / `NoSuchFieldError` occurs at link time, which is a
  `LinkageError` (see A10).

### 3.2 EventBus 6.0.5 — finding E1

> **G0 correction (2026-10-15, `docs/contracts/MODULE-2-G0-RESULTS.md`).** E1 is **CONFIRMED**, but
> the mechanism below is wrong. Under FML the factory is `ModLauncherFactory`, not
> `ClassLoaderFactory$ASMClassLoader` (that is the non-ModLauncher fallback).
> - The wrapper `<pkg>.__<Listener>_<method>_<Event>` is synthesised by EventBus's ModLauncher
>   plugin, and only for GAME-layer packages.
> - For a module package, `EventBus.register` hits a `ClassNotFoundException` **at
>   registration**. EventBus catches and logs it at ERROR, and `register` completes without
>   throwing. `ModuleContextImpl` then creates a `ResourceHandle` and records it in the ledger, so
>   Core wrongly treats the registration as successful. The module then gets **zero
>   deliveries, silently**.
> - E1a is corrected accordingly, E1b is moot, and E1c is CONFIRMED on main and `RegionThread`.
>
> The original analysis is kept below as the record of what was predicted.

Read from `eventbus-6.0.5.jar` (`javap -c`):
- `ASMEventHandler` generates one wrapper class per `@SubscribeEvent` method.
- It defines that class through a single static `ClassLoaderFactory$ASMClassLoader LOADER`.
- `ASMClassLoader` has **parent `null`**. Its `loadClass(name, resolve)` is
  `Class.forName(name, resolve, Thread.currentThread().getContextClassLoader())`.

Therefore (STRONGLY INDICATED; to be confirmed by a G0 negative fixture):

- **E1a.** The wrapper's reference to the listener class resolves through the TCCL **of whichever
  thread first links it**.
  - In NestWorld that may be main or any region thread.
  - None of those has a module loader as TCCL.
  - The expected result is a `NoClassDefFoundError` at first event dispatch, outside module
    lifecycle attribution. On a region thread, this could make it a tick failure.
- **E1b.** `LOADER` is one JVM-wide loader. If it ever resolves a listener name, it becomes the
  initiating loader for that name.
  - Two module jars with the same listener FQCN (allowed by per-module isolation, §7) would then
    collide through `LOADER`, causing a `LinkageError` or a wrong-class binding.
- **E1c.** The lambda path `IEventBus.addListener(EventPriority, boolean, Class<T>, Consumer<T>)`
  does not use `ASMEventHandler`; it compiles to `lambda$addListener$11` (`javap -p`). It holds a
  direct reference to the module's `Consumer`. That reference was resolved by the module's own
  loader when the module created the lambda.

**Consequence (confirmed by G0):** for an external loader, `registerListener(Object)` is non-functional as implemented. The
spec adds a typed, generic `registerListener(Class<E>, EventPriority, Consumer<E>)` capability
and refuses the `Object` form for jar-loaded modules until G0 proves otherwise (§8.3).

### 3.3 Events defined by modules

**L5 (CONFIRMED by L3).** Forge requires every `Event` subclass to pass through
`EventSubclassTransformer`, which runs only in ModLauncher's loader. A module-defined `Event`
subclass would behave incorrectly. The spec forbids it fail-closed: after `defineClass`, a check
of `Event.class.isAssignableFrom(c)` refuses the class (§6.4).

### 3.4 Content registration timing

**L6 (CONFIRMED by anchor).** Modules are discovered at `ServerStartingEvent`. Forge registries
(blocks, items, entity types) are frozen long before that. MODULE-2 modules therefore **cannot add
registry content**. This is a v1 scope boundary and not a loader defect. Content-bearing
extensions stay Forge mods.

### 3.5 Integrated server

**L7 (CONFIRMED, MODULE-LIFECYCLE-001 matrix).** A client JVM runs several sequential
`MinecraftServer` lifetimes (A→B→C), with `ModuleRegistry` per server and generations increasing
per JVM. A class loader cannot follow that lifetime:
- classes, statics, and the Forge/Brigadier/EventBus caches that may reference module classes all
  live for the JVM;
- v1 cannot prove that no such reference survives.

That is the reason for the spec decision "loader = JVM lifetime, instance = server lifetime" (§11).

## 4. Gap list (what MODULE-2 must add)

| Gap | Current | Required (spec §) |
|---|---|---|
| G-a | No filesystem discovery | Scan `<gameDir>/nestworld-modules/*.jar` (§3) |
| G-b | Descriptor comes from module code | Static `META-INF/nestworld/module.json` (§5) |
| G-c | No SPI version | `spi` major.minor plus a member-level ABI check (§8) |
| G-d | No classloader | `ModuleClassLoader`: parent-first, reserved roots, no shadowing (§6, §7) |
| G-e | Duplicate id aborts startup (A5) | Pre-discover de-duplication: all copies refused (§10) |
| G-f | `Error` escapes (A10) | Catch `LinkageError` / `ExceptionInInitializerError` per module, attribute, isolate (§10) |
| G-g | Discovery-order enable/disable (A6) | Topological enable, reverse disable (§9) |
| G-h | Silent command merge (A11) | Name-collision refusal, which also applies to built-ins (§8.4) |
| G-i | ASM listener path (E1) | Typed lambda listener capability (§8.3) |
| G-j | Loader lifetime undefined | JVM-lifetime loader cache, per-server instance, jar-changed refusal (§11) |
| G-k | No published SPI artifact | `compileOnly` SPI artifact plus an external example built without Core sources (§14 G6) |

A11 and A6 are latent defects in already-shipped code. They are listed for the owner and are not
fixed here: the owner asked for no out-of-scope refactors. The spec fixes them inside MODULE-2's
own gates.

## 5. Unknowns to close before or at the start of implementation

| # | Unknown | Closed by |
|---|---|---|
| U1 | **CLOSED by G0 (dedicated): yes.** Can a child `URLClassLoader` (parent = GAME-layer TCL) link against SPI, Minecraft, and Forge on both dedicated and integrated servers? (L2) | G0 positive probe |
| U2 | **CLOSED by G0: yes, at registration (silent), not at dispatch.** E1a: does the `Object` listener actually fail? Fixture: register a `@SubscribeEvent` object from a child loader and post an event from main and from a region thread | G0 negative probe |
| U3 | Does `TransformingClassLoader.getResource("x/Y.class")` reliably report the presence of a parent class? The no-shadowing rule (§6.3) depends on it | G2 fixture: a jar bundling `com/google/gson/Gson.class` must be refused |
| U4 | What does FML do with a module jar placed by mistake in `mods/` (no `mods.toml`)? | G1 observation only; the spec does not depend on it |
| U5 | **CLOSED by G0: `TransformingClassLoader[TRANSFORMER]` on main and RegionThread.** The TCCL of region threads (inherited from the creator?) | G0 log line; affects E1a only |
