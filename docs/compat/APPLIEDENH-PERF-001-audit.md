# APPLIEDENH-PERF-001 — appliedenhancements `recheckExtractionType` hotspot: audit + equivalence proof

Status: AUDIT (step 1). Date 2026-11-09. Scope: appliedenhancements-1.1.2-forge.jar
(sha256 a11fa8ea…eb625, identical on live and pi-smoke) on AE2 15.4.10/15.4.11 (`ExternalStorageFacade*`,
`AEItemKey*` class bytes identical between the two AE2 jars, checked with `cmp`). Rule: GAMEPLAY_EQUIVALENCE —
the fix must give exactly the results AE2 + appliedenhancements give today.

## 1. Evidence: the live profile

Live spark 2026-11-09 (`p3.bin`, async-profiler engine: native frames such as `[vdso]` and `itable stub`
are present; interval 4 ms; `Server thread` total 468 300 ms):

| frame (Server thread) | total | self |
|---|---|---|
| `ItemHandlerFacade.wrapOperation$…$appliedenhancements$recheckExtractionType` | 6.31 % | **5.93 %** |
| `ItemHandlerFacade.extractFromHandler` (AE2, caller of the recheck) | 9.97 % | 0.24 % |
| ↳ first check: `CachedFailedInsertInventoryHandler.getStackInSlot` (sophisticatedcore) | 2.99 % | 0.38 % |
| ↳ recheck children: Operation lambda → `wrapHandlerExtract` → `extractItem` | 0.36 % | — |
| ↳ recheck children: `getStackInSlot` / `AEItemKey.matches` | ≈0.00 % | — |

Callers (by total under `extractFromHandler`): 7.19 % `StorageHelper.poweredExtraction` from
`NetworkStorage.extract` (appliedenhancements `extractInfiniteCell` wrapper) → storage bus → `extractExternal`;
1.84 % `MEInventoryHandler.isPreferredStorageFor` from import buses; rest < 0.3 %. Every path enters through the
appliedenhancements `@WrapMethod extractFromIndexedSlots` and then AE2's per-slot `extractExternal` loop.
The handler behind ~99 % of the first-check time is sophisticatedcore `CachedFailedInsertInventoryHandler`
(Sophisticated Storage / Backpacks block capability).

Attribution of the recheck's 5.93 % *self* time: its own body is ~15 bytecodes, so the self time is
inlined callee work (`getStackInSlot` chain + `AEItemKey.matches`, which serializes the stack's Forge
capabilities, + the `Object[]`/boxing of the `Operation.call`) folded into the frame — async-profiler attached at
runtime without `-XX:+DebugNonSafepoints` attributes inlined callees to the enclosing compiled frame. The
asymmetry (first check 3.0 % over **all** slots vs recheck 5.9 % over **matching** slots only) is explained by
`AEItemKey.matches`: it short-circuits on `stack.is(item)` for non-matching slots, but on a matching slot runs
`Objects.equals(tag)` and `serializeStackCaps` (Forge `CapabilityProvider.serializeCaps`). STRONGLY INDICATED (not
CONFIRMED: the split between getStackInSlot / matches / boxing inside the 5.9 % is not observable in this profile).

## 2. What `recheckExtractionType` does (bytecode, `ExternalStorageFacadeItemHandlerMixin`)

`@WrapOperation(method="extractFromHandler", at=@At(INVOKE, "…ItemHandlerFacade;wrapHandlerExtract(IItemHandler;IIZ)I"))`,
static handler, captures the target's arguments `(source, sourceSlot, requested:AEItemKey, maxExtract, mode)`:

```
if (!requested.matches(handler.getStackInSlot(slot))) return 0;     // no extractItem at all
return original.call(new Object[]{handler, slot, amount, simulate}); // → AE2 wrapHandlerExtract → handler.extractItem
```

So: per `wrapHandlerExtract` call — one `IItemHandler.getStackInSlot` (interface dispatch through the handler's
whole delegate chain), one `AEItemKey.matches` (item, NBT `equals`, capability serialization), one `Object[4]`
allocation + 3 boxings + the MixinExtras `Operation` dispatch. No per-slot scan, no capability *lookup*, no
cross-call state. State it depends on: **the live contents of exactly one slot of the handler at the moment of
the call.** No field of the facade is read.

Where it runs (AE2 `ItemHandlerFacade.extractFromHandler`, unchanged by any other mixin):

```
stack = handler.getStackInSlot(slot); if (!itemKey.matches(stack)) return 0;   // AE2's own check (C1)
SIMULATE: n = stack.getCount(); x = wrapHandlerExtract(h, slot, max, true); …  // 1 call  → recheck R1
MODULATE: total=0; loop { x = wrapHandlerExtract(h, slot, max-total, false);   // calls R1, R2, … until x==0
                          if (x>0) total += x; else break; }
```

Typical counts: SIMULATE = 1 recheck; MODULATE = k extracting calls + 1 terminating call (k = 1 for a slot
≤ one stack) ⇒ usually 2 rechecks.

Purpose (why it exists): in the MODULATE loop, R2…Rn run right after `extractItem` mutated the slot. If the
slot now holds a *different* item (refill-from-buffer inventories, compacting/compression views, slot
re-mapping), AE2's unguarded loop would extract that other item and account it as `itemKey` (item conversion:
dupe + loss). The recheck stops the loop instead.

## 3. When its result can change

| event | can change recheck result? |
|---|---|
| an `extractItem` earlier in the same MODULATE loop (R2…Rn) | **yes — by design** (that is the bug it guards) |
| any foreign code between two facade calls (hopper, BE tick, player, other region thread) | yes |
| same game tick, same handler identity, same slot count | yes (in-tick mutation is the normal case) |
| between C1 and R1 of the same `extractFromHandler` invocation | only through code that runs between them: `ItemStack.getCount()` and the `Actionable` enum switch — **no handler call, no foreign code** |

### Rejected fixes (provably NOT equivalent)

* per-tick memo, memo keyed by (handler identity, slot count, game tick), per-handler memo across facade
  calls: counterexample = the MODULATE loop itself. R2 runs in the same tick, same handler, same slot count,
  right after R1's `extractItem` emptied the slot and (refill inventory) put a different item in it. A memo would
  answer "match", AE2 would extract the other item and count it as `itemKey` → conversion. Also any foreign
  mutation between two facade calls in the same tick defeats it.
* memo "invalidated on any mutation through the facade": every R2…Rn is preceded by an `extractItem` through
  the facade, so it is invalidated every time; the only surviving hit is R1 — i.e. it degenerates to §4, but
  additionally relies on "no foreign mutation between facade calls", which is false. Rejected in favour of §4,
  which needs no cross-call state at all.

## 4. Proposed fix: elide only R1 (the recheck that is back-to-back with AE2's own check C1)

Within one invocation of `extractFromHandler`, the **first** `wrapHandlerExtract` call (R1) is preceded by C1 on
the same handler, same slot, same key, with nothing in between but `stack.getCount()` and an enum switch. R1
re-runs `matches(getStackInSlot(slot))`; C1 just returned `true` for the same expression. Eliding R1 (calling
AE2's raw `wrapHandlerExtract` directly) is behaviour-identical under these proof obligations, all checked at
runtime, fail-closed (any failure ⇒ the original chain runs unchanged):

* **P1 — handler read purity.** A second, immediately repeated `getStackInSlot(slot)` returns the same stack
  instance and has no observable side effect. Not true for arbitrary `IItemHandler`s (a getter may log, rebuild,
  copy, or compute), so it is enforced by an exact-class allowlist with a bytecode-audited, closed-world
  delegate closure (§5): Forge `ItemStackHandler` (exact class) and sophisticatedcore
  `CachedFailedInsertInventoryHandler` (exact class) whose `wrappedHandlerGetter` is a lambda of
  `StorageBlockEntity` or `BackpackBlockEntity` (controllers excluded: `ControllerBlockEntityBase` maps slots to
  other handlers through `validateHandlerSlotIndex`, not audited). Sophisticated entries are enabled only when the
  three sophisticated jars have the audited sha256.
* **P2 — `matches` purity on that instance.** `AEItemKey` is `final`; `matches` = `!isEmpty && is(item) &&
  Objects.equals(getTag(), tag) && Objects.equals(serializeStackCaps(stack), caps)`. Everything is a pure read
  except `serializeStackCaps` → Forge `CapabilityProvider.serializeCaps()`: if `isLazy && !initialized` it returns
  `lazyData` (pure); else if `capabilities == null` it returns `null` (pure); else it calls arbitrary mods'
  `serializeNBT` (not provably pure). R1 is elided only in the two pure cases (checked by reflection on the
  captured C1 stack instance).
* **P3 — chain shape.** R1 is skipped by calling AE2's private static `wrapHandlerExtract` directly instead of
  `original.call(…)`, so everything nested *inside* our wrapper is skipped. Checked in the mixin plugin's
  `postApply` on the final class: exactly two `WrapOperation` handlers exist for that call site — ours and
  appliedenhancements' `…$appliedenhancements$recheckExtractionType` — the latter's instruction sequence equals the
  audited one (§2), and `extractFromHandler` invokes our handler directly (ours is outermost). Pack-wide scan: no
  other mixin in the 348 production jars references `ExternalStorageFacade`.
* **P4 — nothing between C1 and R1.** Verified from AE2 source/bytecode (§2); our wrapper only elides the first
  call per invocation (MixinExtras `@Share` boolean local, fresh per invocation).
* **Concurrency (NestWorld region threads).** If another thread mutates the slot concurrently, the original
  outcome is already interleaving-dependent. With P1/P2 every execution of the new code (C1 → extractItem) equals
  an execution of the old code in which the concurrent write lands after R1 (C1 → R1 → write → extractItem), so the
  new behaviours are a subset of the old ones (refinement). No new outcome is introduced.

R2…Rn (after a mutation) always run the original appliedenhancements recheck — the guard the mod exists for is
untouched. Counters: `calls`, `elided` (cache hits), `afterMutation` (re-checks required because the previous
call mutated the slot: the "invalidations"), `ineligibleHandler`, `capsNotPure`, `disabled`.

Expected saving: R1 is 1 of 1 recheck on SIMULATE and 1 of ~2 on MODULATE; `poweredExtraction` does both,
`isPreferredStorageFor` simulates ⇒ roughly 55–70 % of the recheck's cost on the live mix, i.e. ~3–4 % of the
server thread. HYPOTHESIS until measured (step 3).

## 5. P1 closure audit (bytecode, this pack)

Closed world = the 348 jars of `/root/project-infinity/mods` (class hierarchy scanned for every interface in the
chain). All members are pure reads, or idempotent lazy initialisers that already ran during C1:

* `CachedFailedInsertInventoryHandler.getStackInSlot` → `wrappedHandlerGetter.get().getStackInSlot(slot)` (pure
  delegate; the failed-insert cache is touched only by `insertItem`).
* Suppliers: `StorageBlockEntity.lambda$getCapability$11` → `getStorageWrapper().getInventoryForInputOutput()`
  (no subclass of `StorageBlockEntity` overrides `getStorageWrapper`); `BackpackBlockEntity.lambda$getCapability$3`
  → `getBackpackWrapper().getInventoryForInputOutput()`.
* `StorageWrapper` / `StorageBlockEntity$1` / `MovingStorageWrapper` / `BackpackWrapper` /
  `LinkedStorageBackpackWrapper` / `NoopStorageWrapper` `getInventoryForInputOutput`, `getInventoryForUpgradeProcessing`,
  `getInventoryHandler`: field read, lazy create-once (`InventoryIOHandler`, `ContentsFilteredItemHandler`,
  `InventoryModificationHandler`, `initInventoryHandler`, backpack `fillWithLoot` on first creation), or delegate
  to a linked `IStorageWrapper` (closure: the same classes).
* `InventoryIOHandler` (no subclasses) `getFilteredItemHandler` = field; `FilteredItemHandler(.Modifiable)`,
  `ContentsFilteredItemHandler` `getStackInSlot` = pure delegates.
* `InventoryHandler.getStackInSlot` → `InventoryPartitioner.getPartBySlot` (array read) → part
  `getStackInSlot(slot, this::lambda$10)`; no `InventoryHandler` subclass in the pack overrides `getStackInSlot`;
  `lambda$10` = `invokespecial ItemStackHandler.getStackInSlot` (`validateSlotIndex`; `stacks.get`).
* `IInventoryPartHandler` implementers in the pack: `Default` (calls the IntFunction), `CompressionInventoryPart`
  (map reads), `InfinityInventoryPart` (+`$Admin`/`$Survival`, no override): `computeIfAbsent` memo — after C1 the
  memo is consistent with the slot, so R1 would return the same cached instance.
* `InceptionInventoryHandler` (backpack inception upgrade): `refreshHandlersIfNeeded` (rebuild once when
  `handlersInvalidated`, cleared by C1) → Forge `CombinedInvWrapper` (pure index mapping) over the same closure.
* Mixins into any of these: pack-wide scan finds none touching `sophisticatedcore/inventory` getters
  (`MixinVanillaInventoryCodeHooks` targets Forge hopper hooks only).

Confidence: P1 for the listed closure CONFIRMED by bytecode for these jar hashes; P2 CONFIRMED (Forge source of
this fork + AE2 source); P3/P4 CONFIRMED by bytecode, re-verified at runtime by the plugin; saving HYPOTHESIS.

## 6. Implementation constraints (step 2)

Separate mixin config `nestworld_compat_ae2_appliedenh.mixins.json` (required=false, `defaultRequire 0`,
priority 1500 so it applies after appliedenhancements' default 1000) + plugin `AppliedEnhMixinPlugin` (applies
only when the appliedenhancements mixin class is present; `postApply` verifies P3 and records applied/verified);
glue `AppliedEnhRecheckElision` (allowlist, caps check, counters); flag default ON,
`-Dnestworld.compatAe2.appliedEnhCacheOff=true` = negative control (mixin applied, counters run, nothing elided).
Independent of the energy-domain gate; `-Dnestworld.compatAe2.disable=true` also disables it.
