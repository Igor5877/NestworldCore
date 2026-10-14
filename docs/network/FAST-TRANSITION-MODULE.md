# Fast Transition Module — first server integration

The built-in `fast-transition` module uses the existing `net.nestworld.module` SPI.
Enable on a test backend with `-Dnestworld.fastTransition=true`. Default: disabled.
Discovery is startup-only; this is not a JAR-folder loader or hot reload mechanism.

## Implemented behavior

- A ledger-owned login budget sends up to 32 FML payloads / 1 MiB per connection per tick.
  One oversized first packet is allowed to guarantee progress. Packet order, acknowledgements,
  negotiation futures and all payloads remain intact. Completion requires ALL payloads sent.
  Without a running module policy, the normal one-payload-per-tick budget applies immediately.
- Registry SHA-256 covers every Forge-synced registry's IDs, aliases, overrides and blocked IDs.
  Recipe SHA-256 covers complete serialized network recipes sorted by recipe ID, bound to the
  registry generation. Startup and global datapack reload refresh the snapshot; a failed capture
  clears the old snapshot. Custom serializer nondeterminism can cause conservative cache misses.
- Chunk preparations use Core-owned, non-ticking FULL tickets, nearest chunks first. Readiness
  requires completed FULL futures and lighting. No synchronous load, `join`, mailbox bypass,
  entity mutation, player login or teleport is performed. This does not mean client-rendered terrain.
- Each preparation owns a distinct ticket token, including when requests overlap across modules.
  Radius <= 4; at most 8 live leases per module; lifetime 1..1200 server ticks, including the READY
  hold window. Revoke, expiry and module quiesce release tickets. A stalled server also stalls
  tick-based expiration: a future coordinator must enforce its own wall-clock transition deadline.

## Module API additions

`ModuleContext.registerLoginBudget(LoginBudget)` returns a ledger-owned registration.
`ModuleContext.prepareChunks(ChunkPreparationRequest)` returns a `ChunkPreparation` lease.
`ModuleContext.snapshotSyncData()` captures immutable data generations.
`ModuleContext.ownsServer(server)` filters global Forge events without exposing a raw server.

Registration, capture, preparation and cancellation require the owning server thread.
Registration/preparation return null after quiesce; snapshot capture throws. Multiple modules'
login budgets intersect. A module may register one live budget; revocation restores fallback.
Preparation requests are idempotent while their lease is live; reusing the ID with different
parameters is rejected. Use a new ID after expiration. `ready()` completion is historical:
recheck `state() == READY` immediately before consuming it. Never block on completion.

The preparation service is one aggregate ledger resource; completed/expired requests leave its
active map, preventing an ever-growing per-request resource ledger. It never owns a worker pool.

## Operator verification

All new commands require permission level 2:

- `/nwtransition`: JSON manifest and current command preparation status.
- `/nwprepare`: prepare 5x5 chunks at the command source's position for 600 ticks.
  Use `/execute in minecraft:overworld positioned 100 80 100 run nwprepare` for a destination.
- `/nwpreparecancel`: release that preparation immediately.
- `/nestworld module quiesce fast-transition`: existing lifecycle command; disables policy and
  releases all preparations. Startup-only discovery means restart to enable again.

The manifest has its own schema/version and per-module-instance random run ID. It deliberately
reports `fastAttachSupported=false` and `FULL_LOGIN_ONLY`: protocol/channel/config/tag/datapack/
player-attach compatibility is not yet established. It is NOT the complete TransitionManifest
from FAST-TRANSITION-1 and MUST NOT be used to authorize FAST_ATTACH or data skipping.

## Migration boundary

This migrates handshake pacing and introduces a real pre-attach chunk preparation capability.
Existing CRP client and Velocity packets are unchanged. The external server-mod's recipe_offer,
canonical IDs and other behavior are not replaced in this step. Its batch mixin cancels the
original Forge method and therefore overrides this bounded policy if installed: test pacing
without that mixin. Do not remove the whole external server-mod expecting feature parity.

Next integration requires a versioned client/proxy protocol: publish the full manifest before
switching, request/ack preparation, validate run and generation, then negotiate per-domain
WARM reuse. No legacy recipe-epoch bypass is enabled here. No arbitrary client lifecycle event
suppression is introduced. FULL Forge login remains the fallback and the only supported mode.

## Checks

`bash tests/fast-transition/check.sh` checks content-key framing/invalidation and pacing limits.
`:forge:compileJava` checks the real patched Forge sources, including source/patch integrity.
Runtime acceptance should cover preparation READY/cancel/expiry, overlapping leases, quiesce,
global `/reload` (unchanged recipes stable; changed recipe body changes generation), and a
real CRP connection including packet/ACK order. Compilation alone does not prove these behaviors.
