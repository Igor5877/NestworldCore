# MODULE-1 — Startup-Loaded Module SPI Foundation

**Status: IMPLEMENTED, VERIFIED (2026-09-30).** Real code, compiles against this repo's actual
build, and both central tests below have run against a real, isolated server with real RCON
evidence. See "Live verification results" near the end of this document.

**Blocking issue found and fixed during verification, unrelated to MODULE-1's own design**:
`:forge`'s own deployed jar could not boot at all post-API-PKG-1 (`net.nestworld.modapi` was
silently missing from `universalJar`'s actual output). Fixed in `build.gradle` — full writeup
in `docs/PACKAGING.md`'s "Regression found and fixed" section. This was a packaging gap, not a
MODULE-1 defect; MODULE-1 itself is what finally re-booted the real jar and caught it.

Scope: exactly what MODULE-0 (`docs/MODULE0_AUDIT.md`) and MODULE-0.5
(`docs/MODULE0.5_LIFECYCLE_CONTRACT.md`) resolved a design for — nothing more. **Not
implemented, deliberately**: `ModuleClassLoader` isolation, hot load/reload, same-JVM module
replacement, generation-tagged async (scheduled/mailbox) work, dynamically-supplied Mixins,
arbitrary scheduler replacement, arbitrary Core object access, a DI framework, an inter-module
dependency solver, a marketplace/update system, a security sandbox. Process restart remains the
only physical unload boundary.

## What was built

Package `net.nestworld.module` (privileged, NestWorld-specific — unlike `net.nestworld.modapi`,
this is NOT required to be Core-free):

| Class | Role |
|---|---|
| `ModuleId`/`ModuleVersion`/`ModuleGeneration`/`ModuleIdentity` | MODULE-0.5 §G1's identity model, carried through lifecycle/ledger/attribution from day one — not decorative, even though no async work exists yet to exercise cross-generation staleness |
| `ModuleDescriptor` | Static metadata, known before a module is ever loaded |
| `ModuleLifecycleState` | `DISCOVERED → LOADED → ENABLED → RUNNING → QUIESCING → DISABLED`, + `FAILED` from any state. `UNLOADED` deliberately not implemented (no classloader isolation this pass) |
| `ModuleExecutionContext` | MODULE-0.5 §G2's decision: `MAIN_ONLY`/`REGION_LOCAL`/`CORE_OWNED` — a genuinely separate vocabulary from GMEIL's `ExecutionPolicy`, composing rather than colliding |
| `NestWorldModule` | The interface a module implements: `descriptor()`, `onEnable(ModuleContext)`, `onDisable()` |
| `ModuleContext`/`ModuleContextImpl` | The only way a module acquires Core-tracked resources — `registerListener`/`registerCommand`, both ledger-backed |
| `ResourceHandle`/`ResourceLedger` | Core-owned revocation record — `revokeAll()` runs in **reverse** registration order, tolerant of a throwing handle (logs, continues, never abandons the sweep) |
| `ModuleFailure` | Attribution record (moduleId/version/generation/phase/capability/cause) — attribution only, never claims rollback |
| `ModuleRegistry` | The orchestrator: `discover`/`enableAll`/`quiesceAll`/`quiesce(id)`, owns `CurrentGeneration`, drives every transition |
| `ExampleModule` | The "deliberately boring" test module — registers exactly 3 resources (1 listener, 2 commands), no more |
| `FailingExampleModule` | Registers 2 resources then throws before a 3rd, for the failure-injection test |

## Where it's wired in

`NestworldRegionSystem.onRegisterCommands` — discovers both test modules and calls `enableAll`
here specifically (not `onServerStarting`) because this is the first point a
`CommandDispatcher` exists; avoids an event-ordering assumption between `RegisterCommandsEvent`
and `ServerStartingEvent`. `NestworldRegionSystem.onServerStopping` — calls `quiesceAll()`
before Core's own teardown; this gives modules a real drain step **Core's own shutdown still
lacks** (`docs/RACE_CLASSIFICATION.md` item 15 — the mailbox-not-drained gap — remains
unfixed for Core itself; MODULE-1 does not fix it, only avoids repeating it for modules).

## Honest limitations, stated plainly, not buried

- **Command revocation is soft, not real tree removal.** Confirmed via `javap` against this
  project's actual Brigadier (1.1.8) before writing the code: `CommandNode.addChild` exists,
  `removeChild`/`removeCommand` do not. A revoked command's Brigadier node stays visible in
  tab-complete; its `execute` is wrapped to refuse to run once revoked. Per MODULE-0.5 §G3's
  honesty framework: "stops taking effect" is `ASSERTABLE`/`TESTABLE`; "no longer appears to
  exist" is `NOT ACHIEVABLE` with this Brigadier version, not claimed.
- **No timeout/`FAILED` mechanism for in-flight `RUNNING` work during `QUIESCING`.** MODULE-0.5
  §G1 designed this for when async (scheduled/mailbox) module work exists — it doesn't yet in
  MODULE-1's scope (registrations are synchronous: a listener registration or a command add
  either succeeds or throws immediately, nothing runs "in the background" that quiescing would
  need to wait for). Building a timeout mechanism with nothing real to time out on would be
  exactly the "invent APIs for resource types that have no current use" the scope explicitly
  forbids. This is deferred honestly, not silently dropped — the design exists in
  `MODULE0.5_LIFECYCLE_CONTRACT.md`, ready for whenever a real async capability is added.
- **`ModuleRegistry` is a JVM-global static holder**, the same class of risk already flagged for
  `NestworldRegionSystem.INSTANCE`. Not solved here — no per-server-instance isolation, no
  same-JVM-restart test for modules. Documented as the same open risk, not a new one.
- **Only 2 resource types implemented** (listener, command) — exactly what
  `docs/MODULE0_AUDIT.md` §D evidenced as low-risk and Core-precedented. Scheduled work, mailbox
  work, and services are explicitly NOT implemented (they need generation-tagging machinery
  MODULE-1 deliberately doesn't build yet).

## The two tests that actually matter (per explicit instruction — not "did it load")

**1. Ledger lifecycle** (`ExampleModule`): register A/B/C (RUNNING, ledger size 3) → `/nestworld
module quiesce example-module` → new registration attempt via `/nestworld module testregister
example-module late-cmd` must return `REJECTED` → ledger must be empty → state `DISABLED`.
Revocation must be observable as REAL, not just recorded: `ExampleModule`'s own
`/exmodstatus` command reports a live tick counter from its listener — the counter must stop
incrementing after quiesce, confirmed by reading it again after a few more ticks.

**2. Failure injection** (`FailingExampleModule`): registers A (listener) then B (command), then
throws before ever registering C. Module must never reach `RUNNING` — must go straight to
`FAILED`. Ledger must be revoked (B then A, reverse order) and end up empty. Confirmed via
`/nestworld module status`'s `FAILURE:` line showing phase=`ENABLED`, capability=`onEnable`.

## Live verification results (2026-09-30) — PASS on every test in scope

**Test 1 (ledger lifecycle) — PASS.** `example-module` booted `RUNNING`, `ledgerSize=3`.
Listener confirmed genuinely live: `/exmodstatus` tick count rose `538 -> 771` over 5 real
seconds of RCON polling. `/nestworld module quiesce example-module` -> `state=DISABLED
ledgerSize=0`. Late registration attempt -> `REJECTED`. Post-quiesce `/exmodstatus` ->
`"'exmodstatus' was revoked (module resource no longer live)"` — the soft-revoke wrapper
genuinely refuses to run, exactly matching the documented Brigadier limitation (node still
routable, execution correctly blocked).

**Test 2 (failure injection) — PASS.** `failing-example-module` never reached `RUNNING` —
straight to `FAILED` at boot, log showing "revoking 2 already-registered resource(s) in reverse
order". `/nestworld module status` showed the correct `FAILURE:` line
(`phase=ENABLED capability=onEnable`, exception message intact), `ledgerSize=0`.

**Additional checks — PASS.** Brigadier argument validation correct on missing `id`/`name`
(proper usage errors, no repeat of `CORE-CMD-001`'s silent-wrong-tree failure). Shutdown path
confirmed on a second fresh boot with `example-module` left genuinely `RUNNING` (not manually
quiesced): `stop` -> log shows `quiesceAll()` driving it to `DISABLED` (ledger empty) BEFORE
Core's own region-system teardown — modules get a real drain step Core's own shutdown still
lacks, exactly as this document claims above. No crashes in any boot/shutdown cycle across two
independent fresh boots.

**Incidental, higher-priority finding from this same pass**: this was the first fresh rebuild+
boot of `:forge`'s own deployed jar since API-PKG-1 — it failed to boot at all
(`NoClassDefFoundError`, `net.nestworld.modapi` missing from `universalJar`). Fixed in
`build.gradle`, independently verified (real installer, real boot, `Done (9.339s)!`) — full
writeup in `docs/PACKAGING.md`. Not a MODULE-1 defect, but MODULE-1's live verification is what
surfaced it.
