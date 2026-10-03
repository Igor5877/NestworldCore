# MODULE-LIFECYCLE-001 — contract (owner decisions 2026-10-03)

The audit is in `MODULE-LIFECYCLE-001-audit.md` (R1–R4). This document is the contract the fix
implements.

## Model
```
JVM lifetime
├── GENERATION_COUNTERS (per ModuleId, monotonic, never reset)
├── MinecraftServer A lifetime
│   ├── ModuleRegistry A  (server-owned; created for A, unreachable after A stops)
│   ├── DISCOVER → LOAD → ENABLE → RUNNING            exactly once per server
│   ├── dispatcher generation 1  ← bind command declarations of RUNNING modules
│   ├── dispatcher generation 2 (/reload) ← bind the same declarations again
│   └── QUIESCING → DISABLED → dispose
└── MinecraftServer B lifetime
    └── NEW ModuleRegistry B
```

## Invariants
- **I1.** No Module reaches ENABLED/RUNNING without an owning MinecraftServer. Resource sets built
  without a server (the Create World screen, validation, datagen) never cause module lifecycle.
- **I2.** Discovery, load and enable happen exactly once per MinecraftServer lifetime, anchored at
  a server lifecycle point. **RegisterCommandsEvent is not a module lifecycle event.**
- **I3.** Module lifetime ≠ command-registration lifetime. A **command declaration** is
  module-owned: it lives with the module generation, and revoking it is a soft revoke (Brigadier
  cannot remove nodes). A **binding** is per dispatcher generation: it dies with its dispatcher.
  On every RegisterCommandsEvent of the owning server, Core binds the declarations of RUNNING
  modules into the new dispatcher. There is no rediscovery, no re-enable and no new
  ModuleGeneration.
- **I4.** Before the server becomes operational, every RUNNING module's command declarations are
  present in the server's active dispatcher, whether that dispatcher was created before or after
  the module was enabled.
- **I5.** ModuleRegistry is a server-owned object, not a JVM-global mutable singleton. Global
  lookup goes through a weak, server-keyed facade. After ServerStoppedEvent, A's registry, module
  contexts, ledgers and runtime instances (and A's server through them) are unreachable. The only
  exceptions are class metadata and the JVM-lifetime generation counters. The fix is not a
  legalised version of the test-harness reset.
- **I6.** Every lifecycle method runs on the owning server's thread.

## Anchor
**`ServerStartingEvent`**, after Core's own initialisation in the same handler.

At that point:
- the MinecraftServer exists;
- levels are loaded and NestworldRegionSystem is initialised, so every Core dependency is available;
- the tick loop has not started, so there is no gameplay yet.

The server's active dispatcher already exists (built with the server's resources before the server
object), so I4 is met by binding into `server.getCommands().getDispatcher()` at enable.

`ServerAboutToStartEvent` was rejected: it fires before `loadLevel`, so Core dependencies such as
the region system do not exist yet. A single event is used; there is no lifecycle split without
need.

## Wiring

| Event | Action |
|---|---|
| ServerStartingEvent | `ModuleRegistry.create(server)` → discover → load/enable → RUNNING; declarations bound into the active dispatcher |
| RegisterCommandsEvent | NestWorld's own commands as before; then, **only if** `ServerLifecycleHooks.getCurrentServer()` has a registry and the event fires on that server's thread, bind its RUNNING declarations into `event.getDispatcher()` (next dispatcher generation). Otherwise nothing happens: there is no server, or this resource set does not belong to a running server |
| ServerStoppingEvent | quiesce all modules of this server's registry |
| ServerStoppedEvent | dispose this server's registry (removed from the facade; unreachable) |

## Verification
- **Dedicated server:** R1 `/reload` succeeds, module commands still execute, ModuleGeneration is
  unchanged; R4 modules are enabled after "Starting minecraft server", on the Server thread.
- **Client, one JVM:**
  - Create World → Cancel twice;
  - world A (play, `/reload`, commands);
  - stop to title, then wait longer than the watchdog threshold;
  - world B (play, `/reload`);
  - stop;
  - world C.

  At every step:
  - discovery count per server = 1 and enable count per server = 1;
  - zero modules RUNNING without a server, zero duplicate ModuleIds;
  - commands available, and still available after `/reload`, with the ModuleGeneration unchanged;
  - the previous server's registry and server are garbage-collectable.
- **API-1B A→B lifecycle matrix** with **no harness reset**.

## Verification results (build 1.20.1-47.4.256, scratch instances)

**Client, real production client, one JVM:**
- Create World → Cancel ×2: `registriesCreated=0`, `liveRegistries=0` after each (I1).
- Worlds A, B, C. In each, modules are DISCOVERED/RUNNING on the **Server thread**:
  - discoveries = 2 and enables = 2 (one per module; failing-example fails by design), running = 1;
  - example-module generation 1 → 2 → 3 (JVM monotonic);
  - `exmodping` executes in the active dispatcher;
  - a real `reloadResources` gives a **new dispatcher**, `exmodping` still executes, discoveries,
    enables and generation are unchanged, dispatcher bindings 0 → 1.
- Boundary after A and after B: the weak facade `BY_SERVER` is empty.
- After GC, A's server, region system, player and **module registry** are collected (checked in
  B), and so are B's server and module registry (checked in C).
- The client stayed alive 147 s after server A stopped (100 s hold > watchdog threshold).
- Total 102 pass, 1 fail. The fail is A.fixture.ordering, which is CORE-PARKED-REGION-001, not
  this gate.
- **No harness reset exists any more**; it was removed from the test code.

**Dedicated:**
- R4: modules DISCOVERED/RUNNING on the Server thread, after "Starting minecraft server" and
  "Preparing level". Forge logs "Done" before ServerStartingEvent on a dedicated server; the tick
  loop has not started.
- I4: `exmodping` works immediately. The first dispatcher was built before the server and received
  the declarations at enable.
- R1: `/reload` twice succeeds. `exmodping` works after each; generation stays `gen1`; bindings
  0 → 1 → 2; discoveries/enables stay 2/2.
