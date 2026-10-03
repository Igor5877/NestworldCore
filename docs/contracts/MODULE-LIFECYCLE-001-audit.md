# MODULE-LIFECYCLE-001 — ModuleRegistry lifecycle audit (`/reload` + server A → stop → server B)

**Fix: IMPLEMENTED + VERIFIED 2026-10-03, see `MODULE-LIFECYCLE-001-spec.md`.**

**Status at audit time: AUDIT + DETERMINISTIC REPRODUCTIONS COMPLETE (2026-10-03). No product code
changed; fix not started.** This gate merges MODULE-RELOAD-001 with the API-1B-CLOSURE finding MODULE-LIFECYCLE.
It is a blocking dependency of API-1B CLOSED.

Target invariant (owner, 2026-10-02):
> MODULE-1 is startup-loaded per MinecraftServer lifetime, not permanently per JVM lifetime.
> Re-registration of commands must not rediscover modules, and termination of server A must leave
> no module runtime state capable of preventing server B from starting.

This does **not** mean hot unloading of module code. Physical class unloading stays a
restart-only / MODULE-3/4 question.

## 1. Ownership and lifetime of the state

`net.nestworld.module.ModuleRegistry` is static and JVM-global:

| Field | Holds | Cleared |
|---|---|---|
| `MODULES` (LinkedHashMap ModuleId → ModuleRuntime) | runtime per module: state, identity (incl. generation), ResourceLedger, ModuleContextImpl bound to **one CommandDispatcher** | **never** |
| `FAILURES` (ArrayList) | ModuleFailure records | **never** |
| `GENERATION_COUNTERS` (ModuleId → AtomicLong) | monotonic generation per module | never (correct: generations must never be reused) |

Lifecycle wiring (`NestworldRegionSystem`):
- `onRegisterCommands(RegisterCommandsEvent)` calls `discover(ExampleModule)`,
  `discover(FailingExampleModule)` and `enableAll(dispatcher)`.
- `onServerStopping` calls `quiesceAll()`. That only moves RUNNING → QUIESCING → DISABLED; the
  runtimes stay in `MODULES`.

`discover()` throws `IllegalStateException("ModuleId already discovered")` if the id is present.

## 2. What RegisterCommandsEvent actually is

Forge posts it in the `Commands` constructor (`Commands.java:221`). A `Commands` is built **per set
of server resources, not per server**:

| Path | When | MinecraftServer exists? | Thread |
|---|---|---|---|
| `server/Main` → `WorldLoader.load` → `ReloadableServerResources` | dedicated start | **not yet** | `main` |
| `MinecraftServer.reloadResources` → `ReloadableServerResources` | `/reload`, data-pack changes | yes, same server | Server thread |
| `CreateWorldScreen.openFresh` → `WorldLoader.load` | opening "Create New World" | **no; may never exist** (Cancel) | Render thread |
| `WorldOpenFlows` → `WorldLoader.load` | creating / opening a world in the client | not yet | Render thread |
| `Commands.validate`, `CommandsReport` | dev validation / datagen | no | — |

So module discovery and enable are coupled to the **lifetime of a resource set**. The event can
fire several times per server, before any server exists, and with no server ever following.

## 3. Contract contradictions found statically

The ModuleRegistry javadoc states three things the wiring breaks:
- **"discover — startup-only, before server start".** It actually runs on every resource load.
- **"Threading: main/server thread only".** It actually runs on `main` (dedicated, pre-server) and
  on the client Render thread.
- **"MODULE-1 exercises exactly one generation per server run".** In fact, by the second resource
  load in one JVM the registry is already populated.

Its own "Lifecycle" note already flags the JVM-global singleton risk and says no
integrated-restart test existed. API-1B-CLOSURE was that test.

## 4. Deterministic reproductions (scratch instances only)

| # | Scenario | Evidence | Result |
|---|---|---|---|
| R1 | dedicated, `/reload` | `Exception caught during firing event: ModuleId already discovered: example-module` at `ReloadableServerResources.<init>` → `ReloadCommand` | **"Reload failed; keeping old data"**: the whole data-pack reload is broken for every user, not only for modules |
| R2 | client: world A → quit → world B, **no harness intervention** (`harnessModuleReset=false`) | world B: `Failed to load datapacks, can't proceed with server load` / `ModuleId already discovered: example-module` | **world B never loads** (180 s timeout) |
| R3 | client: open "Create New World", Cancel, then create a world (`createScreenCancel=true`) | modules DISCOVERED and **RUNNING (3 resources registered) on the Render thread with no server** at screen open; world creation then fails with the same exception | **even the first world fails to load** |
| R4 | dedicated start ordering | `main`: DISCOVERED ×2, RUNNING `example-module#gen1` → only then `Server thread`: "Starting minecraft server" | modules are enabled **before the MinecraftServer exists** |

R2 and R3 ran in a real production client (installer `--installClient`, headless), with the
driver modes added in this commit. R1 and R4 ran on the dedicated scratch server.

## 5. Questions the fix contract must define

These are owner decisions, recorded here rather than resolved.

1. **Anchor of module enable.** The owner invariant says per MinecraftServer lifetime. Candidate:
   discover/enable at a server lifecycle event (ServerAboutToStart / ServerStarting) instead of at
   resource construction. Then resource sets built without a server (create-world screen, validate,
   datagen) never enable modules.
2. **Meaning of RegisterCommandsEvent for a RUNNING module.** Per the owner, re-registration must
   not rediscover. But module commands are resources in the module's ledger, bound to the
   dispatcher passed at enable. After `/reload`, a **new** dispatcher replaces the old one. Without
   re-registration, module commands silently disappear from the reloaded command tree; the old
   ledger handles point at a dead dispatcher. The contract must say whether a command registration
   is per-dispatcher (re-applied on every RegisterCommandsEvent for RUNNING modules) and what the
   ledger records.
3. **Ordering problem created by (1)+(2).** On a dedicated server the first RegisterCommandsEvent
   fires before the server exists (R4). If enable moves to a server event, the first dispatcher's
   command registration happens **before** enable. The contract must define how a module enabled
   later gets its commands into the already-built dispatcher. The dispatcher is reachable via
   `server.getCommands()` at ServerStarting.
4. **Server-stop boundary.** After quiesce, which state is discarded? Candidate: `MODULES` and
   `FAILURES` are per-server, so failure history becomes per server run and must be reported before
   discard. `GENERATION_COUNTERS` are per-JVM and monotonic.
5. **Threading.** Once (1) is decided, the threading contract becomes enforceable: Server thread
   only.

## 6. Out of scope
- hot unload or replacement of module code;
- classloader isolation;
- any change to API-1B / setSlot;
- packaging.
