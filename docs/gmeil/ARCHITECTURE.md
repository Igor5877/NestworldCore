# GMEIL architecture and rollout

GMEIL is a policy layer over the existing NestWorld region scheduler, ownership rules, and
`RegionMessage` mailboxes. It is not another scheduler or cross-region transport. This directory
records the Phase 0 contract from the user-provided GMEIL v1.3 design before implementation.

## Boundary

Core owns region, entity, block, chunk, player, and scheduled-work ownership metadata. It does
not own a mod's static maps, singleton caches, or logical network graphs. A block entity owned by
Region A does not imply that arbitrary code it calls is safe to run alongside Region B.

The loader adapter identifies an opaque callback and creates an `ExecutionKey`. The gateway
resolves a policy. Unknown mod code defaults to `MAIN_COMPAT`; `REGION_CERTIFIED` requires
positive evidence. The existing scheduler and mailbox then execute the selected path while
preserving the callback's synchronous, phase, ordering, and ownership contract.

## Existing primitives to reuse

| Primitive | Current code | GMEIL role |
| --- | --- | --- |
| Region worker/barriers | `RegionThread`, `RegionThreadPool`, `NestworldRegionSystem` | Execution and phase barriers |
| Owner mapping | `WorldRegion`, dimension grid, `NestworldOwnershipAssertions` | Apply-time owner validation |
| Cross-region transport | `RegionMessage`, `WorldRegion.nestworldSendOrQueue` | Canonical delivery |
| Closed entity operations | `EntityMutationDispatcher` | Existing local/foreign routing, not a general request bus |
| Player input/actions | `PlayerInputDispatcher` and player action dispatchers | Sequencing and handover constraints |
| Race research | `RACE_CLASSIFICATION.md`, mod coremod regressions | Negative evidence and validation workloads |

## Rollout gates

1. Close [entrypoint inventory](EXECUTION_ENTRYPOINTS.md) before claiming complete coverage.
2. Add `ExecutionKey`, immutable policy lookup, context, metrics, and OBSERVE mode without changing
   mailbox behavior.
3. Prove one unknown callback executes under `MAIN_COMPAT` with its original observable semantics.
4. Add request/reply and scheduler-aware waiting to the existing mailbox, then token-based
   `SERIAL_COMPAT` and positive region certification.
5. NestWorld mod bytecode transformers are disabled in production by the user's explicit
   constraint. Their scripts and tests are offline research oracles only. Do not claim that the
   resulting unpatched modpack is concurrency-safe until the same workloads pass under GMEIL.
6. Do not suppress or replace mod Mixins at load time. Fix structural conflicts in Core source
   and verify the original mod callback remains responsible for its own behavior.
7. The old `ModCallbackRouter` next-tick isolation flag is disabled until a main bridge can
   preserve synchronous completion and phase order. The startup compatibility guard logs Canary
   conflicts but does not rewrite that mod's configuration.

`MAIN_COMPAT` restores server-thread affinity and conservative serialization. It does not by
itself reproduce every vanilla temporal ordering; tick, phase, causal order, and owner exclusion
must be checked separately. No policy may become less restrictive merely because no race was
observed.
