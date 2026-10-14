# AE2-COMPAT-DESIGN-001.2 — semantic audits of M1, M3 and M5; AE2 concurrency domain

**Status: DESIGN-1.2 (2026-10-14) — for owner review. No code.** Supersedes M1, M3 and M5 of
AE2-COMPAT-DESIGN-001.1; everything else in 001.1 stands.

Owner rulings this document answers (2026-10-14):
- M5: neither (a) nor (b). M5c is required.
- M1: conditionally approved, after a synchronous-semantics proof.
- M3: blocked until every caller and the meaning of the `boolean` are audited.
- The lock scope is renamed.

## 0. Name: AE2 concurrency domain
The lock scope is **not** energy-only. Energy operations already mutate `TickManagerService` state:
an energy cell calls `alertDevice` on its own grid inside inject and extract.

From here on: **AE2 concurrency domain** (or "guarded domain") is the set of grids in one energy
overlay, guarded as a unit. The services currently inside it are `EnergyService` and the
`TickManagerService` operations of 001.1 §3 (T1, plus T2 queue operations).

- A later audit may add another grid service that energy paths reach transitively.
- Each addition is an explicit document change, never an implicit assumption.

## 1. M5c — fail-closed compatibility gate (replaces M5 a/b)
**Rule:**
- An unknown `IAEPowerStorage` implementation must not be silently excluded from AE2.
- It must not run through a guarded path that has not been proven.
- It makes the adapter **unsupported for this environment**.

**1a. Startup verification (primary).**
- Forge's mod scan gives every class in every mod file as `ModFileScanData.ClassData(clazz,
  parent, interfaces)`. That includes mixin classes: SolarFlux's `SolarPanelTileMixin` declares
  `implements IAEPowerStorage`.
- During load, the adapter computes the transitive closure:
  - every class whose own interfaces, or whose parent chain (across mod files, ending in AE2
    classes), reaches `appeng.api.networking.energy.IAEPowerStorage`;
  - plus every interface that extends it.
- Each result must match the **allowlist**: exact class, owning mod id, mod version and jar
  sha256.
  - AE2's own classes.
  - ExtendedAE `TileExCharger`, `TileExInscriber`, `Crankable`; megacells energy cells. These
    inherit AE2's `final` inject/extract.
  - `SolarPanelTileMixin` for SolarFlux 20.1.11.
- **Match:** the adapter activates.
- **Mismatch:**
  - no mixin of the adapter applies (the mixin config plugin returns false);
  - one loud diagnostic lists class, mod, version, jar and the reason;
  - conservative pins stay in force (C).
  - The environment runs exactly as today. Nothing is excluded and nothing is guarded.

**1b. Runtime barrier (second safety).** Dynamically created implementations (proxies, generated
classes) are not visible to the scan.
- At `EnergyService.addNode`, before `addProvider` / `addRequester`, the participant's class is
  checked against the activated allowlist.
- An unknown class triggers a one-way switch to **DEGRADED** mode.
  - **What changes:** from then on every outermost AE2 guarded entry first takes one AE2-global
    reentrant lock `G` (order G → T0 → domains). The whole of AE2's guarded surface becomes a
    single serialization domain.
  - **Gameplay:** the participant joins the power sets, with no change.
  - **Correctness:** no AE2 lock inversion is possible with a single outer lock. M1 still
    applies.
  - **Parallelism:** lost for the AE2 guarded surface until restart.
  - **Diagnostics:** loud, naming class, mod and stack. The class is recorded, so the next
    startup fails closed under 1a.
- **Transition safety.**
  - The switch happens inside `addNode`, i.e. under T0 plus the involved domains.
  - Threads already inside a guarded section keep their domain locks and never request G, because
    nested entries never take G.
  - New outermost entries take G before anything else.
  - A G holder can wait for a domain held by an older thread, but that thread never waits for G.
    No cycle is possible.
- **Owner alternative to DEGRADED:** a controlled server stop with a crash report (strict).
  Proposed: DEGRADED, because it is correct, keeps gameplay intact and is loud.

## 2. M1 — semantic audit (conditionally approved contract)
**Contract (owner wording):** defer reentrant topology-capable callbacks across the AE2 critical
section, while preserving same-thread FIFO delivery before the outer operation returns.

**2.1 Exactly which path is intercepted.**
- Of the audited participants (001.1 §2), only one reaches vanilla neighbour notification: the
  SolarFlux panel, through `BlockEntity.setChanged()` → `setChanged(level, pos, state)` →
  `level.blockEntityChanged(pos)` + `level.updateNeighbourForOutputSignal(pos, block)`.
  - This is the Forge 47.4.x patched code in this repository.
- `updateNeighbourForOutputSignal` loops over all 6 directions:
  - it calls `onNeighborChange(level, neighbourPos, pos)` on each neighbour state;
  - for a redstone-conductor neighbour, it calls `level.neighborChanged(state2, pos2, block, pos,
    false)` on the block beyond, when that block reports `getWeakChanges`.
- AE2's energy cell uses `setChangedNoTicketUpdate`, which only sets the chunk-unsaved flag.
- AE2 powered block entities' inject and extract fire grid events only.
- **Therefore M1 intercepts exactly one method: `Level.updateNeighbourForOutputSignal`.** The
  narrowest scope.
- Other notification entry points (`Level.neighborChanged`, `updateNeighborsAt`, `setBlock`
  update flags) are **not** reached from the audited set.
  - They get a **detector only**, not deferral.
  - If one is reached inside a guarded section, the cause is an unaudited path: the runtime
    barrier switches to DEGRADED (§1b).

**2.2 Does the caller of `setChanged()` expect an immediate neighbour side effect?**
- No. `setChanged` returns nothing and promises "this block entity changed".
- The only caller under the lock is `SolarPanelTile.extractEnergy`. Its bytecode ends with
  `putfield energy`, `invokevirtual setChanged`, `iload 4`, `ireturn`. The return value was
  computed before the call, and nothing after the call reads any state.

**2.3 Can AE2 read, later in the same operation, state that a neighbour callback would have
changed?** No.
- After a participant returns, `extractProviderPower` / `injectProviderPower` read the returned
  amount, the iterator over its own `providers` / `requesters`, and its own accounting fields.
- Callbacks reachable through neighbour notification (001.1 §1) change other state:
  - AE2 part redstone flags, render updates (`markForUpdate`) and tick scheduling (`alertDevice`
    on the part's grid);
  - topology (ToggleBus connect or destroy).
- None of that is read by the running energy operation.
- **Feedback into that operation's sets is already forbidden by AE2 itself.** A topology change
  that removes a provider during an ongoing extract throws "Cannot modify energy providers while
  energy is being extracted" even single-threaded. Deferring it past the section removes a path
  AE2's contract already rejects; it removes no valid observation.

**2.4 Coalescing.** None. Each call is recorded as `(level, pos, block)` and delivered once, in
issue order, even for repeated notifications of the same block.

**2.5 FIFO and nesting.** One thread-local queue and a depth counter.
- Nested guarded sections (reentrant E1 → E5, overlay members, T1) append to the same queue.
- Only the **outermost** release delivers.
- **Delivery:** take the current list, clear the queue, deliver in order.
  - If a callback during delivery enters a new guarded section, that section is outermost at
    that moment. Its own notifications are delivered at its exit, before the outer loop resumes.
  - That is the same depth-first order vanilla produces, where callback k's own notifications run
    inside k before k+1.

**2.6 Flush boundary.** In the outermost wrapper's `finally`:
1. release all domain locks (and G / T0 if held);
2. deliver the queue;
3. return or rethrow.

Delivery always happens on the same thread, before control returns to the external caller (for
example Pipez's `receiveEnergy`), also when the AE2 operation threw.

**2.7 Exceptions.**
- **Vanilla behaviour.** A throw inside notification k propagates through
  `SolarPanel.extractEnergy` into AE2's `extractProviderPower`. Its `finally` resets the phase
  flag, and the energy operation is abandoned **half-applied**: some providers are already
  drained and the accounting is not updated.
- **Behaviour with M1.**
  - The energy operation completes atomically.
  - Notifications 1..n are then delivered. **All of them are attempted**: they are idempotent
    "something changed" hints, and dropping later ones would leave comparators stale.
  - The first exception is rethrown to the external caller, with any later ones attached as
    suppressed.
  - If the AE2 operation itself threw, the queue is delivered and the operation's exception is
    rethrown, with delivery exceptions attached as suppressed.
- **Divergence from vanilla.** The energy state is consistent instead of half-applied. The
  external caller sees the same exception type at the same call boundary. Stated as a deliberate
  semantic change for owner acceptance.

**2.8 Threads and world access.** Delivery reads neighbour block states exactly as vanilla would
have done inline, on the same thread. No new cross-region access is introduced.

**2.9 Blast radius.**
- Outside a guarded section, `updateNeighbourForOutputSignal` does one thread-local depth check
  and then runs vanilla code unchanged.
- Inside one, it only appends.
- Most Minecraft execution never enters a guarded section.

## 3. M3 — audit result: WITHDRAWN (no deferred TickManager calls at all)
**Contract of the `boolean`** (`TickManagerService`, installed bytecode):

| Method | `true` means | `false` means | Also |
|---|---|---|---|
| `alertDevice` | node moved `sleeping` → `awake`, tick-on-next-tick set, queue position updated | node is ticking now, or is not a TickManager node | **throws `IllegalArgumentException`** if the node is known but not alertable |
| `sleepDevice` | node moved `awake` → `sleeping` and left the queue | node is ticking now, or was not awake | |
| `wakeDevice` | node moved `sleeping` → `awake`, queue updated | node is ticking now, or was not sleeping | |

`true` means **a completed state change**, not "request accepted". Returning `true` for a deferred
call would be false, and deferral would also move the `IllegalArgumentException` to another frame.

**Callers:**
- AE2 has 29 call sites (energy cell, IO bus, storage bus, annihilation plane, pattern provider,
  interface, P2P, inscriber, charger, IO port, molecular assembler, vibration chamber, spatial
  anchor, …). Every one discards the result: statement calls or lambda bodies.
- The pack's AE2 addons have 42 call sites, checked in bytecode: the call is always followed by
  `pop`. AdvancedAE, ExtendedAE, extendedae_plus, megacells.

**Why M3 is not needed.**
- Under M5c and M1, every TickManager call made while a domain is held falls into one of two
  cases:
  - **Same grid, inline.** An energy cell's `alertDevice` on its own grid, which is a member of
    the held domain. It runs inline under the held lock, with its exact return value and
    exception behaviour.
  - **Other grid, inside a deferred notification.** For example IOBus `alertDevice` reached
    through a neighbour notification. The **whole** callback is deferred by M1, so it later runs
    outside any section, with its natural return value and exception, against a lock it acquires
    normally.
- No TickManager call is ever deferred individually. **M3 is withdrawn.**
- If a guarded section ever reaches T1 on a grid outside the held set, the cause is an unaudited
  path: the detector counts it and switches to DEGRADED (§1b). There is no substitution of a
  return value.

## 4. Updated mechanism set (vs 001.1 §5)
| Mechanism | State |
|---|---|
| M1 | narrowed to `Level.updateNeighbourForOutputSignal` only; contract §2; detector on the other notification methods |
| M2 | unchanged (refusal of nested lower-order synchronous energy calls: "nothing moved", no side effects); expected count 0 |
| M3 | **WITHDRAWN** (§3) |
| M4 | unchanged reasoning, now resting on M5c + narrowed M1: T0 is never requested under a held domain on any audited path; an unaudited path → DEGRADED |
| M5 | **replaced by M5c** (§1): startup allowlist gate (inactive on mismatch) + runtime barrier (DEGRADED single-lock mode) |

**Proof sketch, restated.**
- With the adapter active, the startup gate guarantees that every `IAEPowerStorage` class is
  audited.
- The runtime barrier guarantees that any unaudited class or path switches to the single-lock
  mode before it can run under multiple domain locks.
- Audited code under a lock reaches only four kinds of thing:
  - held locks (same-grid events, same-grid T1);
  - lock-free writes (fields, the chunk-unsaved flag);
  - `updateNeighbourForOutputSignal`, deferred by M1;
  - after-commit callbacks (E8, G2, T2 device ticks).
- Locks are therefore acquired only at outermost entries, in the order G (DEGRADED only) → T0 →
  ascending domains.
- No waits-for cycle can form, and no acquisition happens after an irreversible mutation inside a
  section.

## 5. Owner decisions requested
1. **M5c runtime barrier:** DEGRADED single-lock mode (proposed) vs controlled stop.
2. **M1 §2.7:** accept the exception semantics (operation atomic; deliver all notifications;
   rethrow the first, others suppressed).
3. **M3 withdrawal** (§3).

Still no addon implementation.
