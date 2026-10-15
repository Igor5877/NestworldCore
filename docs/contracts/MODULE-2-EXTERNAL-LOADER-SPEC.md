# MODULE-2 — External Module Loader: specification (2026-10-15)

**Status: SPEC — owner decisions D1–D8 RECORDED (2026-10-15, §15). Loader NOT implemented; implementation starts at G0 only on the owner's go.**
- Audit: `docs/contracts/MODULE-2-EXTERNAL-LOADER-AUDIT.md`. Its facts are cited here as A1… /
  L1… / E1.
- Preserved unchanged: MODULE-LIFECYCLE-001 I1–I6, and the state chain
  `DISCOVERED → LOADED → ENABLED → RUNNING → QUIESCING → DISABLED` (+ `FAILED`).
- Not in v1:
  - hot load/unload/reload (install a jar → restart the process);
  - `UNLOADED`;
  - registry content;
  - Mixins/transformers;
  - inter-module class linking;
  - a sandbox.

## 1. Principles

1. **One public mechanism.**
   - Every jar-loaded module goes through the same scanner, descriptor, loader, validation, and
     `ModuleContext`. That includes closed NestWorld modules (§13).
   - No module id, signature, package, or vendor gets extra powers.
   - A capability a first-party module needs is added as a generic, documented, versioned SPI
     capability that any module may use.
2. **Static before dynamic.** Everything decidable without running module code is decided before
   any module class is loaded: identity, versions, dependencies, capabilities, and package layout.
3. **Fail closed per module, never per server** — unless the module is declared
   `"critical": true` (§10.3). An invalid or failing module is FAILED with attribution. Its
   dependents become FAILED / `DEPENDENCY_UNAVAILABLE`. Independent modules and the server
   continue.
4. **The loader is JVM-lifetime; the instance is server-lifetime** (§11).

## 2. Startup flow (per MinecraftServer lifetime, server thread, at ServerStartingEvent, I2/I6)

```
S1 scan          list <modulesDir>/*.jar                      (no class loading)
S2 descriptor    read META-INF/nestworld/module.json via ZipFile; parse; schema check
S3 static check  id/version syntax, spi range, capabilities, side, mappings, deps,
                 reserved-package scan, no-shadowing scan, forbidden-manifest scan
S4 resolve       duplicate-id refusal, dependency graph, cycle detection, topological order
                 → each surviving jar: DISCOVERED (static descriptor = DISCOVERED payload, A9)
S5 loader        ModuleJarCache.obtain(jar): reuse the JVM-lifetime ModuleClassLoader or create it
S6 entrypoint    Class.forName(entrypoint, false, loader); type checks; ABI member check (§8.2)
S7 instantiate   public no-arg constructor (TCCL = module loader during the call)
                 cross-check instance.descriptor() == static (id, version)        → LOADED
S8 registry      ModuleRegistry.discover(instance, source) in topological order
S9 enable        enableAll: generation++, ledger, ModuleContext, onEnable          → ENABLED → RUNNING
```
- Built-in modules (in the Core jar) enter at S8 with a descriptor equivalent to the static one.
  They take the same S4 duplicate and dependency rules and the same S9 path (§13.2).
- A failure in S2–S7 marks that jar FAILED with phase `DISCOVERY` or `LOAD`. It never reaches the
  registry's `discover`.
- A dependent of a FAILED module becomes FAILED with cause `DEPENDENCY_UNAVAILABLE <id>` (§9).
- `RegisterCommandsEvent` and `/reload` stay as they are today: `bindCommands` only, no
  S1–S9 (I3).

## 3. Filesystem

| Item | Rule |
|---|---|
| Directory | `FMLPaths.GAMEDIR/nestworld-modules`. Dedicated: next to `mods/`. Integrated: the client game dir. Override: `-Dnestworld.modules.dir=<path>` |
| Creation | Core creates the directory (`Files.createDirectories`) at the first server start where it is missing, then scans it (empty → zero modules, one INFO line). A creation failure (read-only FS) → one WARN, external modules disabled for this lifetime, server continues |
| Switch | `-Dnestworld.modules.external` (default `true`, D4). Installation is the admin's explicit act of placing a jar: a valid jar in the directory is enabled. No disable list in v1 (a later `config/nestworld/modules.toml` `disabled = [...]` is possible, not specified here) |
| Candidates | Regular files **directly** in the directory whose name ends with `.jar` (case-sensitive) and does not start with `.`. **One level only, no recursion.** Symlinks are canonicalised, and two names resolving to one canonical file count as one candidate |
| Subdirectories | Never scanned. There is no `nestworld-modules/libs/` or other dependency directory in v1: a library goes inside the module jar, relocated if it could collide (§6.3). A subdirectory that contains `*.jar` files → one WARN `subdirectories are not scanned: <dir>` |
| Order | Scan order has **no** semantic effect. Every ordering decision uses ModuleId (§9) |
| `mods/` | Never scanned by this loader. A module jar in `mods/` is not a module (audit U4) |
| Locking | The loader keeps the jar open for the JVM lifetime. On Windows the jar cannot be replaced while the process runs, which matches "install → restart" |

## 4. Parent-owned (reserved) package roots

A module jar must not contain a class or package in any of these roots. S3 refuses it, and the
loader never defines one (§6.2):

```
java. javax. jdk. sun. com.sun.               JDK
net.minecraft. com.mojang.                     Minecraft, Brigadier, DFU, authlib
net.minecraftforge. cpw.mods. org.spongepowered. org.objectweb.asm.   Forge / FML / Mixin / ASM
org.slf4j. org.apache.logging.log4j.           logging (must bind to the server's)
net.nestworld.                                 the whole NestWorld runtime: SPI, modapi, region, …
```

- **SPI duplication is therefore impossible by construction**: `net.nestworld.module` is
  reserved, and S3 refuses any jar that carries even one class under `net/nestworld/`.
- First-party modules follow the same rule (§13). They use their own root, for example
  `dev.nestworld.<module>`.
- The list is a single constant shared by S3 and the loader. Adding a root is an SPI-minor change
  and is documented in this spec's changelog.

## 5. Descriptor ABI — `META-INF/nestworld/module.json`

```json
{
  "schema": 1,
  "id": "fast-transition",
  "version": "1.2.0",
  "displayName": "Fast Transition",
  "entrypoint": "dev.nestworld.fasttransition.FastTransitionModule",
  "spi": "1.0",
  "capabilities": { "commands": 1, "listeners": 1, "loginBudget": 1, "chunkPreparation": 1, "syncData": 1 },
  "side": "server",
  "critical": false,
  "mappings": "srg",
  "dependencies": [
    { "id": "node-system", "version": "[1.0,2.0)", "kind": "required" },
    { "id": "metrics",   "version": "[0.3,)",    "kind": "optional" }
  ]
}
```

| Field | Rule (any violation → FAILED/DISCOVERY with the field named) |
|---|---|
| `schema` | Integer, exactly `1`. Unknown future schema → refused, never best-effort |
| `id` | Canonical form, regex `^[a-z0-9]+([._-][a-z0-9]+)*$`, 2–64 chars: lowercase ASCII letters, digits, and the separators `-` `_` `.`. No spaces, no uppercase, no leading/trailing separator, no two separators in a row. Compared **byte-exactly**, with no case folding and no normalisation, so there is no case-insensitive ambiguity. A non-canonical id is refused, never normalised. Examples: `example-module`, `fast-transition`. A **separate namespace** from Forge mod ids |
| `version` | Maven version, parsed by `org.apache.maven.artifact.versioning.DefaultArtifactVersion` (`maven-artifact`, already an FML library in the parent); ranges by `VersionRange`. Stored as `ModuleVersion` |
| `displayName` | Optional, ≤ 64 chars, defaults to `id` |
| `entrypoint` | Binary class name; must not be in a reserved root (§4); the class must exist in the jar |
| `spi` | `MAJOR.MINOR` the module was compiled against (§8.1) |
| `capabilities` | Map `name → required capability version`. Unknown name, or version above the runtime's → refused. Use at runtime of an undeclared capability → rejected (§8.3) |
| `side` | `server` \| `dedicated` \| `integrated` (default `server`). All modules are server-side. `server` = every server kind (dedicated and integrated); `dedicated` / `integrated` restrict to one. A mismatch is **SKIPPED** (not FAILED): one INFO line, the module does not exist for this server lifetime |
| `critical` | Boolean, default `false`. `true` → any FAILED outcome of this module at startup (DISCOVERY / LOAD / ENABLED, including `DEPENDENCY_UNAVAILABLE` and duplicate id) aborts server start (§10.3). SKIPPED by `side` is not a failure |
| `mappings` | `srg` or `mcp` (`official` is accepted as an alias of `mcp`). Must equal the runtime's `FMLEnvironment.naming`, otherwise refused (L4). Required |
| `dependencies` | List; `kind` ∈ `required`, `optional`; `version` is a Maven range (§9) |

Further rules:
- Unknown top-level keys → refused under schema 1, so typos do not pass silently.
- The parser is Gson, strict (from the parent). Maximum descriptor size is 64 KiB.
- The descriptor is read through `java.util.zip.ZipFile`, **without creating any class loader**.
- `descriptor()` on the instance stays the 3-field `ModuleDescriptor` record, so the ABI is
  unchanged.
  - At S7 its `id` and `version` must equal the static ones; otherwise FAILED/LOAD
    `descriptor mismatch`.
  - The static descriptor is authoritative.

## 6. Class loading

### 6.1 Model
- One `ModuleClassLoader extends URLClassLoader` **per jar**.
- Parent: `NestWorldModule.class.getClassLoader()`, the GAME-layer loader (L2).
- `registerAsParallelCapable()`.
- Module classes live in that loader's unnamed module.

### 6.2 Delegation: **parent-first, everywhere**
```
loadClass(name):
  if name in reserved root (§4): return parent.loadClass(name)      // never define locally
  c = findLoadedClass(name) ?: try parent.loadClass(name) ?: findClass(name)
  findClass → defineClass → §6.4 post-define checks
```
- Why parent-first instead of child-first: the no-shadowing rule (§6.3) makes the two orders
  equivalent for valid jars. Parent-first is the safer order if a check misses a case. The
  module can never replace a platform class.
- Resources (`getResource*`) follow the same order. Modules keep their resources under
  `nestworld-module/<id>/…`. A root-level resource that also exists in the parent gets one WARN
  at S3.
- `META-INF/services/*` in a module jar is ignored by Core. Discovery is the descriptor only.

### 6.3 Bundled libraries and collisions
- **Allowed:** libraries shaded **and relocated** into the module jar, for example
  `dev.nestworld.fasttransition.shadow.okio`.
- **Shadowing refusal (S3).** For every `.class` entry not in a reserved root, if
  `parent.getResource(entry) != null`, the jar is refused with `shadows parent class <name>;
  relocate`. This catches a module bundling Gson, Guava, Netty, and so on. Depends on audit U3,
  which gate G2 proves.
- **Nested jars** (`META-INF/jarjar/`, `*.jar` entries on a `Class-Path`) are refused in v1.
- **Between modules:** each module has its own loader and no visibility of other modules (§9), so
  equal class or resource names in two module jars do not collide at the JVM level.
- **Global name-keyed collisions are refused explicitly:**
  - ModuleId (§10.1);
  - command literal (§8.4);
  - listener wrapper names do not exist, because the lambda path is used (§8.3).
- Multi-release jars are honoured by `URLClassLoader`.
- Signed jars: signatures are checked by the JDK. A broken signature gives
  `SecurityException` → FAILED/LOAD. A valid signature confers **no** trust or power (§12).

### 6.4 Fail-closed for features that cannot work outside ModLauncher (L3, L5)

| Detected | Where | Result |
|---|---|---|
| Manifest `MixinConfigs`, `FMLCorePlugin`, `FMLAT`, `META-INF/accesstransformer.cfg`, `META-INF/coremods.json` | S3 | FAILED/DISCOVERY `unsupported: <feature> (module jars are not transformed)` |
| `META-INF/mods.toml` present | S3 | FAILED/DISCOVERY `this is a Forge mod; install it in mods/` |
| A defined class with `Event.class.isAssignableFrom(c)` | Post-`defineClass` | `ModuleLoadRefusal` → FAILED/LOAD `module-defined Forge Event subclass` |
| `@Mod` / `@Mod.EventBusSubscriber` annotations (ASM scan at S3) | S3 | WARN `ignored: modules are not FML mods` |

### 6.5 TCCL
- Core sets the thread context class loader to the module's loader around:
  - entrypoint construction;
  - `onEnable`;
  - `onDisable`.

  It restores the previous value in `finally`. These are cold paths on the server thread.
- The TCCL is **not** a listener mechanism. G0 `subscribe-main-tccl`: TCCL = module loader during
  `register` still fails, because wrapper synthesis is tied to GAME-layer packages.
- Listener and command callbacks run with whatever TCCL the firing thread has; this is not
  changed on hot paths. A module whose libraries need the TCCL captures its loader and passes it
  explicitly. This is documented in the module author guide.

## 7. Integrated server, same JVM

- One `ModuleJarCache` per JVM, keyed by canonical path. The value is
  `(size, mtime, sha256, ModuleClassLoader)`.
- At each server start (S5), each candidate jar is fingerprinted:

| Jar state vs cache | Result |
|---|---|
| Not cached | New loader |
| Fingerprint equal | Reuse loader (classes, statics, and class-init state survive from the previous server lifetime) |
| Fingerprint differs | FAILED/LOAD `jar changed since this JVM loaded it; restart the process`. The old loader is not used, and no new one is created: that would be a reload |
| Was cached, now absent | Not discovered this lifetime. The loader stays cached and inert |
| New jar since the previous lifetime | Allowed. A new server lifetime is a new startup, not a hot reload |

- The client and its integrated servers never run two `MinecraftServer` lifetimes concurrently
  (L7), so the cache is read-mostly and `synchronized`.
- Modules are server-side. `side` (§5) controls whether a module participates in integrated
  servers.

## 8. ABI and API negotiation

Two gates, the same structure as PKG-002:
- **Gate V (version):** "does the module claim a compatible SPI?"
- **Gate A (ABI):** "does the runtime physically contain what the module links against?"

### 8.1 Gate V — SPI version
- The runtime declares `ModuleSpi.VERSION = "MAJOR.MINOR"`, starting at `1.0` with MODULE-2.
- Compatible ⇔ `module.major == runtime.major && module.minor <= runtime.minor`.
- Minor changes are additive only: a new capability, a new default method, or a new value type.
- Major changes are anything that removes or changes an existing member, or changes a semantic
  contract.
- Capability versions (§5) are negotiated independently: `runtime.capabilityVersion(name) >=
  required`.

### 8.2 Gate A — member-level ABI check (S6, before any module code runs)
- S3 already parsed the jar's class files with ASM. Collect every constant-pool member reference
  whose owner is in `net/nestworld/`.
- At S6, resolve each one with `MethodHandles.Lookup` against the runtime, as `ModApiAbiGate`
  does for modapi. Any miss → FAILED/LOAD `links against <owner.member desc> absent in this
  runtime`.
- Any reference to a `net/nestworld/` owner that is **not in the published SPI surface list** →
  FAILED/LOAD `references non-SPI NestWorld type <owner>`.
  - The surface list is a generated `META-INF/nestworld/module-spi-surface.json` in the Core
    jar, produced from the SPI artifact's class list.
  - This is how the "no backdoor" rule becomes mechanical: a module, first-party included,
    cannot link against `net.nestworld.region.*` or `ModuleRegistry`.
  - It does not stop reflection. That is a trust matter (§12).
- `Minecraft`/`Forge` member references are not checked in v1. Their failures surface as
  `LinkageError`, attributed per module (§10.2).

### 8.3 Listeners (audit E1, CONFIRMED by G0)
- G0 (`docs/contracts/MODULE-2-G0-RESULTS.md`) showed the `@SubscribeEvent` path gets **zero
  deliveries, silently**, from an external loader: the `ClassNotFoundException` happens at
  registration and EventBus swallows it. The typed path works on main and on `RegionThread`.
- New generic capability `listeners` v1, **mandatory**:
  `ResourceHandle registerListener(Class<E extends Event> type, EventPriority p, boolean receiveCancelled, Consumer<E> handler)`.
- It is implemented with `MinecraftForge.EVENT_BUS.addListener(...)`, the lambda path (E1c).
  - The handler is wrapped (the wrapper is the Core-visible resource and the attribution point).
  - Revocation is `EVENT_BUS.unregister(wrapper)`.
- Exceptions escaping module code at runtime follow §10.4 (D3): attribute, request failure,
  **rethrow the original**. Never swallow-and-continue.
- `registerListener(Object)` stays in the SPI for built-ins only. For a module whose class is
  defined by a `ModuleClassLoader`, it is **fail-closed**: it returns `null` (REJECTED) together
  with a `ModuleFailure(capability=listener, "@SubscribeEvent objects cannot be wired from module
  jars; use registerListener(Class, …)")`.
  - It never reaches `EVENT_BUS.register`, which would record a resource that delivers nothing.
- Undeclared capability use is rejected the same way (null + counter), never by throwing.
- Because G0 confirmed E1, the built-ins also move to the typed form (`ExampleModule`,
  `FastTransitionModule`), so built-ins and jars use one listener path. This is mandatory for G7.
- A module jar calling `MinecraftForge.EVENT_BUS.register(obj)` directly hits the same silent
  loss. The module author guide documents this. It can't be prevented (trusted code, §12).

### 8.4 Commands — ownership registry, never overwrite (audit A11)
- `ModuleRegistry` keeps a per-server **command ownership table**
  `literal → owner`, where owner ∈ `CORE` (`nestworld` and every literal Core registers),
  `PLATFORM` (vanilla / Forge / mods: anything already under the dispatcher root), or
  `<moduleId>@gen<N>`.
- The check happens **before** `dispatcher.getRoot().addChild()`. `registerCommand(name, …)` is
  refused with null + `ModuleFailure(capability=command, "REJECTED: command name already owned
  by <owner>")` when:
  - `name` is in the table under another owner, or
  - `name` already exists under the dispatcher root.
- `addChild` is never reached for a refused name, so Brigadier's merge/overwrite can't happen.
- First claim wins; claims happen in topological enable order (§9), so the outcome is
  deterministic. A claim is released when the owning generation's ledger is revoked.
- `bindCommands` on `/reload` re-binds only claims this module holds. If the new dispatcher
  already contains that literal from someone else (listener order inside `RegisterCommandsEvent`
  is not guaranteed), that one bind is skipped and attributed. The claim is kept and nothing is
  overwritten.
- This also applies to built-ins. It changes built-in behaviour only if they collided, and none
  does today.
- `/nestworld modules` lists the table.

### 8.5 Published SPI artifact
- `net.nestworld:nestworld-module-spi:<spi>` is `compileOnly` and never bundled (S3 would refuse
  it anyway).
- It contains exactly the surface list (§8.2). `ModuleRegistry` and every package-private
  implementation type are excluded.
- The package stays `net.nestworld.module`. Moving the SPI into a sub-package is a separate
  refactor and not part of MODULE-2 (decision D5).
- It is built like `:modapi` (PKG-002), compiled against `:clean`. A build canary asserts zero
  `net/nestworld/region` references.

## 9. Inter-module dependencies and ordering

- **v1 dependencies affect ordering and availability only. They grant no class visibility (D6).**
  `requires X` means exactly:
  - X must exist;
  - X must be compatible (version range);
  - X is RUNNING before I enable;
  - I stop before X stops.

  It never means "I can import X's implementation classes". Module loaders do not see each other;
  each loader's parent is Core only.
- Inter-module communication is a future formal SPI capability, for example
  `ModuleContext.publishService(...)` / `requireService(...)` over SPI-owned contract
  interfaces. It is not in v1. `((NodeSystemImpl) other).internalMethod()` is never a path.
- Resolution (S4), over the set that survived S3 plus the built-ins:
  - **required:**
    - absent → FAILED/DISCOVERY `missing dependency <id> <range>`;
    - present but version outside the range → FAILED/DISCOVERY `version`;
    - the dependency later FAILED (S5–S9) → the dependent is FAILED `DEPENDENCY_UNAVAILABLE <id>`.
  - **optional:**
    - absent, FAILED or out of range → no effect on availability, only one INFO line;
    - present and in range → it is ordered before the dependent.
  - **Cycle** (over the required and optional edges in effect) → every module in the strongly
    connected component is FAILED/DISCOVERY `dependency cycle [a, b, …]`.
- Enable order: topological, with ties broken by ModuleId lexical order. Disable order
  (`quiesceAll`): the **exact reverse** of the actual enable order (fixes A6).
- A required dependency that FAILED in `onEnable` cascades in enable order: dependents are never
  enabled.
- A module that reached RUNNING is never retroactively failed by a **startup** failure of a later
  module. A **runtime** failure of a module (§10.4) quiesces its RUNNING required dependents first,
  in reverse enable order, then the module itself (D6: "I stop before X").

## 10. Failure isolation

### 10.1 Duplicate ModuleId
- When two or more jars, or a jar and a built-in, declare the same `id`, **every jar copy** is
  FAILED/DISCOVERY `duplicate module id <id> (<paths>)`.
- There is no winner selection, because any pick would be arbitrary.
- A built-in keeps its id, since it cannot be uninstalled. The jar is refused and the conflict
  is logged.
- This is decided before `ModuleRegistry.discover`, so the A5 throw is unreachable from the
  loader path.

### 10.2 Failure taxonomy (one `ModuleFailure` per module, shown in `/nestworld modules`)

| Phase | Causes |
|---|---|
| DISCOVERY | Unreadable zip, missing or invalid descriptor, schema, field syntax, spi/capability/mappings incompatibility, reserved package, shadowing, forbidden manifest, duplicate id, dependency |
| LOAD | Loader creation, entrypoint missing or not public / not a `NestWorldModule` / abstract / no public no-arg constructor, ABI member check, non-SPI reference, descriptor mismatch, jar changed, Event subclass, `LinkageError`, `ExceptionInInitializerError` |
| ENABLED | `onEnable` threw. Existing behaviour: ledger revoked in reverse order |
| RUNNING | Listener or command callback threw (§10.4): attributed, module driven RUNNING → QUIESCING → FAILED at the next safe point |

- The loader path catches `Exception`, `LinkageError`, and `ExceptionInInitializerError` per
  module.
- `VirtualMachineError` (OOM, StackOverflow) is **never** caught: it propagates.
- `enableOne` is widened to `Exception | LinkageError` (A10).
- The boundary is **narrow and explicit**: descriptor parse, class loading, entrypoint creation,
  `onEnable`, `onDisable`, and the callback wrappers of §10.4. Nothing else catches broadly on a
  module's behalf.

### 10.3 Critical modules (D2)
- Default: a FAILED module does not stop the server; dependents become
  `DEPENDENCY_UNAVAILABLE`; independent modules load.
- A module with `"critical": true` that ends startup FAILED aborts server start. Core throws from
  `startModules` with the full failure list (all modules, not only the critical one) after
  quiescing the modules already RUNNING in reverse order.
- Startup failure of a critical module → the server never becomes operational. Runtime failure of
  a critical module → the server does not remain operational (§10.5, D8).
- There is no global strict flag. Criticality is a property of a module in a deployment, declared
  by its descriptor.

### 10.4 Runtime callback failures (D3: never swallow)
Applies to listener and command callbacks, and to any future capability callback unless that
capability's own contract explicitly declares best-effort semantics. No v1 capability does.
```
module callback throws T (any thread)
  → attribute: ModuleFailure(id, version, generation, phase=RUNNING, capability, thread, T)
  → mark: module.failureRequested = true (CAS; the first failure wins attribution, later ones counted)
  → fail-closed: from now on this module's wrappers do not call module code
       (listeners return without invoking; commands answer "module <id> FAILED")
  → rethrow T unchanged (Event / Brigadier / tick semantics exactly as for a non-module throw)
server thread, next safe point (start of next server tick, I6):
  → quiesce RUNNING required dependents (reverse enable order), then the module:
       RUNNING → QUIESCING (stop accepting, ledger revoked in reverse, onDisable best effort) → FAILED
```
- Why not quiesce inline: the callback may be on a region thread or mid-event. Lifecycle runs on
  the server thread only (I6), and after a partial callback Core cannot know the state is
  consistent. "Where safe" therefore means the next server-thread safe point, and until then the
  fail-closed wrapper keeps the module from running again.
- `VirtualMachineError` is rethrown without attribution work.
- `critical=false`: the module ends FAILED, its dependents are disabled, the server continues,
  and the failure stays visible as an ERROR line plus `/nestworld modules`. There is no automatic
  restart or re-enable in v1.

### 10.5 Runtime failure of a critical module (D8)
```
critical module callback fails
  → attribute failure
  → disable further callbacks from that module immediately (fail-closed wrapper)
  → propagate the original exception unchanged
  → schedule fatal-module handling on the owning server thread
  → quiesce dependents in reverse dependency order
  → quiesce the failed module (→ FAILED)
  → request normal server shutdown
```
- Shutdown is the normal path: `MinecraftServer.halt(false)`, the same path as `/stop`, with
  world save and ServerStopping/Stopped events.
  - **Forbidden:** `System.exit`, `Runtime.halt`, and any force-kill API.
  - Dedicated: the server process ends normally.
  - Integrated: only the integrated server/world stops. The client JVM keeps running (no
    `Minecraft.stop`).
- The original exception has priority. If it terminates the server first (for example a crash on
  the tick path), that outcome is accepted. The scheduled cleanup and shutdown are best effort
  for the controlled case. They never replace, wrap or mask the original exception, and never
  throw a second exception in its place.
- The reason is recorded once: `critical module <id>@gen<N> FAILED at runtime → shutdown
  requested`.

## 11. Lifetimes, disposal and reachability

| Object | Lifetime | Created | Released |
|---|---|---|---|
| `ModuleClassLoader`, its classes and statics | **JVM** | First S5 that sees the jar | Never closed in v1. The JVM exit is the only unload boundary (MODULE-1) |
| `NestWorldModule` instance | **One server lifetime** | S7 (a new instance per server start, including integrated A→B) | `ModuleRegistry.dispose` at ServerStopped drops runtime, context, ledger and instance |
| `ModuleContext`, `ResourceLedger` | One generation | S9 | Revoked at QUIESCING. Rejects use from that point on (existing behaviour) |
| `ModuleGeneration` | Strictly increasing per id per JVM | S9 | — |

Contract for module authors:
- No server-scoped state in statics. A static survives into the next integrated server lifetime.
- Threads, executors and timers the module starts must be stopped in `onDisable`.

Core diagnostics at DISABLED (warning only, never enforcement):
- live threads whose context class loader is the module's loader;
- live threads whose `getClass().getClassLoader()` is the module's loader.

Reachability gate (G5): after `dispose` and a bounded `System.gc()` retry loop, a `WeakReference`
to the module instance and to its context must be cleared. The loader count must equal the jar
count after N integrated lifetimes; it must not grow.

Why not close the loader per server:
- Forge EventBus, Brigadier and JDK caches may hold module classes.
- v1 cannot prove that none does after ServerStopped.
- A closed `URLClassLoader` with a surviving reference fails later and far away.
- That is the "secret unload" failure mode MODULE-1 already ruled out. `UNLOADED` stays
  unimplemented.

## 12. Trust and security assumptions

- A module is **trusted, privileged code** running in the server JVM, equivalent to a Forge mod.
  - Installing a jar is the server owner's trust decision.
  - There is **no sandbox**: no SecurityManager (deprecated), no permission model.
  - Reflection and `Unsafe` are not prevented.
- Capability checks, the non-SPI-reference refusal, and reserved packages are **correctness and
  contract** mechanisms. They catch accidental misuse and make ABI failures early and attributed.
  They are not a security boundary. The spec does not claim one.
- Signatures, vendor or id never change what a module may do (§1.1).
- Not addressed in v1: provenance, update channels, marketplace.

## 13. First-party (closed) modules — same mechanism

### 13.1 Rules (binding)
1. Closed NestWorld modules (for example a future Node System) ship as `nestworld-modules/*.jar`
   with the same descriptor, loader, gates and `ModuleContext`.
2. They live outside `net.nestworld.*` (§4) and link only against the published SPI surface. G7
   checks this mechanically (§8.2).
3. If a first-party module needs something the SPI lacks, the change is a new **generic** SPI
   capability, with a minor version bump, documented in the SPI guide, and usable by anyone.
   Forbidden:
   - a privileged id check;
   - a hidden system property;
   - a `net.nestworld.region` link;
   - reflective access into Core as an accepted path.
4. CI canary for first-party module builds: zero references to `net/nestworld/` outside the
   surface list. This is the same check as Gate A.

### 13.2 Built-ins in v1
- Until G7, `ExampleModule`, `FailingExampleModule`, and `FastTransitionModule` stay inside the
  Core jar as a **built-in source** feeding the same S4/S8/S9 pipeline.
- The only built-in difference is that S1–S3, S5 and S6 do not apply, because they are not jars.
- Built-ins get no capability that jar modules lack, with one exception pending G0:
  `registerListener(Object)` (§8.3).
- **G7 removes FastTransition from Core**:
  - the hard-coded `new FastTransitionModule()` in `NestworldRegionSystem.startModules` goes;
  - so does the `net.nestworld.module.fasttransition` package and the
    `-Dnestworld.fastTransition` switch.

  FastTransition then exists only as `nestworld-modules/fast-transition.jar`, which proves that
  the public mechanism suffices. `ExampleModule` and `FailingExampleModule` stay as Core test
  built-ins.

### 13.3 Target UX (owner, 2026-10-15)
```
1. install NestWorldCore          4. stop the server
2. start the server               5. copy fast-transition.jar → nestworld-modules/
3. Core creates nestworld-modules/ 6. start → descriptor → verify → classloader → discover → RUNNING
```
No Core rebuild, no Core code naming the module.

## 14. Gate plan

Implementation order. Each gate needs its own evidence, and a negative control where it
asserts a refusal. No gate starts before the previous one is VERIFIED.

| Gate | Content | PASS requires | Negative control |
|---|---|---|---|
| **G0** feasibility probe (throwaway, isolated scratch) — **DONE 2026-10-15** (dedicated): U1/U5 CONFIRMED; E1 CONFIRMED with corrected mechanism; typed path PASS on main + RegionThread. `docs/contracts/MODULE-2-G0-RESULTS.md` | Child `URLClassLoader` → SPI/MC/Forge linkage, command, lambda listener; dedicated + integrated | U1 positive on both; event fired from main **and** a region thread reaches the lambda | U2: `@SubscribeEvent` object from the child loader fails as E1a predicts. If it does **not** fail, §8.3 is re-decided |
| **G1** scanner + descriptor (S1–S4) | Directory rules, JSON schema, dedupe, dependency graph | Invalid-jar matrix: each row → the exact FAILED reason, the server starts, other modules RUNNING | Same fixture with refusal disabled → must produce a wrong RUNNING |
| **G2** loader + static validation (S3, S5, §6) | Reserved roots, shadowing, forbidden manifests, Event subclass | Jar bundling `net/nestworld/module/NestWorldModule.class` → refused; bundling `com/google/gson/Gson.class` → refused; relocated library → RUNNING | Shadowing check off → the bundled Gson copy loads (proves U3 detection works) |
| **G3** ABI (§8.1, §8.2) | Gate V + Gate A + surface list | Module compiled against SPI 1.1 on a 1.0 runtime → refused (V); member removed with version unchanged → refused (A); `net.nestworld.region` reference → refused | Gate A off → the same jar fails later with `NoSuchMethodError` (shows what A prevents) |
| **G4** lifecycle integration + hardening (D7) | §9, §10, §8.3, §8.4. Mandatory fixes: **A5** duplicate id (`foo-v1.jar` + `foo-v2.jar` → both refused, no winner); **A6** reverse shutdown (enable A→B→C, disable C→B→A); **A10** `LinkageError` / loader failures attributed per module; **A11** command ownership | Each of A5/A6/A10/A11 fixed and logged; cycle; `DEPENDENCY_UNAVAILABLE` cascade; `critical=true` abort; §10.4 runtime failure (throwing listener on a region thread → rethrown, wrapper fail-closed, quiesce at next tick); MODULE-LIFECYCLE-001 matrix re-run: dedicated `/reload` R1, integrated A→B→C, Create World → Cancel. All with external modules | Per fix: ownership check off → `nestworld` hijack reproduced; dedupe off → arbitrary winner; forward disable order observed; `LinkageError` escapes `startModules`; swallow instead of rethrow → event caller sees no failure |
| **G5** lifetime / reachability (§7, §11) | Jar cache, fingerprint, `dispose` | Instance and context WeakRefs cleared; loader count stable across 5 integrated lifetimes; changed jar → refused with restart message | Retain the instance in a static on purpose → the reachability check FAILS |
| **G6** SPI artifact + external build | §8.5 | `tests/module-external/` example built by its own Gradle with **only** `compileOnly nestworld-module-spi`; dropped into a deployed real Core jar (version-asserting deploy) → RUNNING; zero Core rebuild | Example bundling the SPI → refused at S3 |
| **G7** first-party reference module | §13 | FastTransition removed from Core (§13.2), built as `fast-transition.jar` (package `dev.nestworld.fasttransition`); the **same Core binary** (byte-identical jar, version-asserted) without the jar has no FastTransition; after a plain copy into `nestworld-modules/` and a restart it is RUNNING and passes its FAST-TRANSITION fixtures; passes G1–G6 checks | A deliberate `net.nestworld.region` call in a test build → refused by Gate A |

Regression after G4 and after G7:
- MODULE-1 tests;
- MODULE-LIFECYCLE-001 matrix;
- `tests/modapi-compat/run-matrix.sh` 10/10;
- API-1B A→B→C.

Islands and production are not touched until the owner decides.

## 15. Owner decisions (2026-10-15)

| # | Decision | Where |
|---|---|---|
| D1 | **JSON**, `META-INF/nestworld/module.json`. TOML adds nothing | §5 |
| D2 | A FAILED module does not stop the server; dependents become `DEPENDENCY_UNAVAILABLE`; independent modules load. **`"critical": true`** (default `false`) aborts startup when that module fails. No global strict flag | §5, §10.3 |
| D3 | Listener exceptions: **rethrow, never swallow.** Attribute to id/version/generation, mark failure, quiesce where safe, rethrow the original. Best-effort only as an explicit per-capability contract | §8.3, §10.4 |
| D4 | External modules **enabled by default**. Placing a jar is the explicit install action. Disable list deferred | §3 |
| D5 | Keep `net.nestworld.module`: `modapi` for ordinary mods, `module` for the privileged SPI. No package move (ABI churn without benefit) | §8.5 |
| D6 | Dependencies without class visibility. Future inter-module calls go through formal `publishService` / `requireService` | §9 |
| D7 | G4 fixes **A5, A6, A10 and A11** (the owner added A5 and A10). Commands use an ownership registry checked before `addChild`; a collision is `REJECTED: command name already owned`, never an overwrite | §8.4, §10, §14 |
| Dir | Core creates `nestworld-modules/` (next to `mods/`). Scan one level only, with no `libs/` subdirectory | §3 |
| Ref | FastTransition is the G7 reference module: the hard-coded construction is removed from Core | §13.2 |
| E1 | If G0 confirms E1, the SPI moves to the typed listener API. **G0 confirmed it (2026-10-15)**: the typed API is mandatory, and `Object` is fail-closed for jar modules | §8.3 |

Spec adjustments made while recording (for review):
- `id` admits `-`, since the owner's examples use `fast-transition`.
- `side` values are `server` (default) / `dedicated` / `integrated`. The owner's example uses
  `server`, so `any` was dropped.

| D8 | A runtime failure of a `critical` module → attribute, fail-closed, rethrow the original, then on the server thread quiesce dependents in reverse order, quiesce the module, and request **normal** shutdown (no `System.exit` / `Runtime.halt`; integrated stops only the world). `critical=false` → server continues, persistent ERROR/status, no auto re-enable | §10.5 |

Owner approval of the two adjustments (2026-10-15): canonical `id` format with `-` `_` `.`
APPROVED; `side = server | dedicated | integrated` APPROVED (`any` not needed; MODULE-2 defines no
client-side lifecycle).
