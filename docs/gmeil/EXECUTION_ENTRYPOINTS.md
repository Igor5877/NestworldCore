# Server-side mod execution entrypoints

This is the Phase 0 inventory, not a claim that every indirect callback has been intercepted.
An entrypoint is ready for enforcement only after its sync/result/phase contract and reachable
callback boundary have been audited. Unknown or unresolved paths remain `MAIN_COMPAT` and cannot
be certified for region execution.

| Source | Existing entrypoint / evidence | Current thread | Contract and unresolved work |
| --- | --- | --- | --- |
| Block entity tick | `Level.tickBlockEntities` collects due tickers; `NestworldRegionSystem.runBlockEntityPhase` dispatches them | Main or region worker, depending on pin/phase | Synchronous for the owning tick; owner by block position. Inventory ticker wrappers and Forge hooks before gateway enforcement. |
| Entity tick | `RegionThread.tickEntities` and `ServerLevel` entity loop | Region worker or main fallback | Synchronous owner mutation; may call another region or nested mod code. Preserve tick order and player exceptions. |
| Forge EventBus listener | `ForgeEventFactory` calls and EventBus `post` | Caller thread, which may be main or region | Listener order, priority, cancellation, result, reentrancy, and exception propagation are synchronous. Per-listener attribution requires adapter work; wrapping only `post` is insufficient. |
| Capability callback | Forge capability invocation sites | Caller-dependent | Often synchronous and result-bearing; exact call-site inventory pending. Never defer a result-bearing call blindly. |
| Network callback | `NetworkInstance` posts network events; packet handling enters game code | Network or scheduled main thread | Ownership and sync/result contract vary; enumerate packet scheduling and handlers. |
| Player action | `PlayerAttackDispatcher`, interaction/use-item dispatchers | Main input thread, optionally owner region | `InteractionResult` paths are synchronous; claimed-CAS/latch fallback exists but is not a generic request primitive. Preserve player handover and ordering. |
| Server scheduled work | `MinecraftServer` task queue and NestWorld work rounds | Main or region worker | Phase and causal ordering matter; `server.execute()` alone does not establish `MAIN_COMPAT`. |
| Scheduled block/fluid tick | `NestworldRegionSystem.runScheduledTicksPhase`, `RegionScheduledTicks` | Main/owner path by current contract | Preserve ordered delivery, apply-time owner check, and no duplication across split/merge. |
| Server/level lifecycle | `MinecraftServer.tickServer`, pre/post server and level tick Forge events | Main, with region phases inside tick | Lifecycle barrier; may not be moved past its tick phase. |
| Command callback | Forge command registration and Brigadier execution | Usually main, verify each ingress | Synchronous result and exceptions; caller/thread classification pending. |
| Custom mod tick handler | Forge tick events and arbitrary registered listeners | Event caller thread | Treat as EventBus listener; no whole-mod certification. |
| External async | Mod-created threads, executors, futures, JNI | Unmanaged | Core cannot intercept every entry without instrumentation. Any Core-owned mutation must enter through owner routing or fail in strict mode. |

For each row still marked pending, record: source, original thread, synchronous completion,
return value, event/phase ordering, owner knowledge, reentrancy, possible cross-region calls, and
loader-specific behavior. The inventory gate is open until these are resolved on both Forge and
the future NeoForge adapter.
