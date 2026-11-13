# Core-assisted import offload: bounded code investigation

## Exact coverage bottleneck

BusTransit.onBusWork calls strategiesAllowed before scheduling any region work. A single foreign FE/soul strategy rejects the entire unfiltered import facade. ItemNoopStrategies is valid only for positive item filters. Simply removing this condition would admit optimistic FE/soul transfers without a proven return/fallback contract.

Exact live bytecode checked with javap:

- StackImportFacade.transfer iterates strategies in their original order and stops when a strategy returns true. It does not itself suppress later strategies just because earlier item work happened.
- FEStackImportStrategy.transfer checks key type, finds ENERGY capability through its own BlockApiCache, then SIMULATE insert, extractEnergy, MODULATE insert, attempted receiveEnergy refund. It always returns false. Therefore its presence is not equivalent to a no-op on an unfiltered inventory that exposes ENERGY.
- SoulsStackImportStrategy similarly checks type and capability, then may drain souls. It cannot be silently filtered out either.
- BusTransitContext is an optimistic held-resource buffer, not AE2 network authority. Broadly admitting those resources would require proof beyond existing item acceptance.

## Existing Core mechanism

BlockEntityExecutionDomain.enqueueForBlockEntityDomain already offers nonblocking owner routing and revalidation before each task. Region tasks execute under the region chunk write lock; MAIN tasks drain at dimension safe points. This is sufficient for a normal owner-side operation. It is not by itself a quiescence acknowledgment for native work collected before a position changes domain.

## Concrete counterexample to proposed MAIN fallback

A proposed protocol was: region task discovers non-item strategy work, pins source to MAIN, publishes continuation; MAIN waits until this record's regionRunning is0, then executes the remaining original strategy.

This is NOT proven safe:

1. RegionThread.runOneTick drains BE_DOMAIN_TASK callbacks (~line582).
2. It subsequently calls tickEntities (~585).
3. tickEntities first runs pre-existing carriedWork (~1038–1039).
4. That work can contain slices created by NestworldDimensionRegion.runBlockEntityPhase (~640), invoking tickBlockEntityList.
5. tickBlockEntityList calls ticker.tick (~570) without resolving the new domain for each ticker.
6. In free-running mode no MAIN latch protects against that carried task starting after the callback publishes a MAIN continuation. Per-record regionRunning only accounts for addon tasks, not these native closures.

This is a counterexample to a NEW proposed fallback protocol, not a reproduced failure of an already VERIFIED AE2 production path. No VERIFIED correctness work is reopened.

## Required boundary before implementation

A Core-assisted continuation must acknowledge that ALL source-reachable pre-collected work from the old owner is finished or has been safely rerouted. Changing a pin or finishing only one addon callback is insufficient. A generic opt-in domain handoff must account for carried and queued BE batches, free-running execution, lifecycle cancellation and split/merge rerouting. Its continuation runs only after that acknowledgment, never via synchronous REGION-to-MAIN waiting. Existing production pin semantics must remain intact.

For an import continuation, also retain exact strategy cursor/order, remaining operation budget, filter snapshot and stop result; do not replay a committed item prefix. Every extracted item remains in the existing transit ledger. MAIN retains network authority. The MM port/controller relationship requires separate actual ownership proof, not a namespace exception.

## Current conclusion

The exact addon entry points and a concrete missing handoff proof are identified. A safe complete implementation and TPS improvement are NOT guaranteed yet. Existing Core mailbox routing can be reused; it must not be mistaken for a complete domain-transfer fence. No guard was removed and no Core production code changed during this investigation.
