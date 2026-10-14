# Fast Transition module work

2026-10-14. User authorized adding the server-side module to NestWorldCore.
Minecraft Java 1.20.1 / this repository's Forge 47 fork; existing owned server network.
Route: existing `net.nestworld.module` SPI, with Core-owned capabilities, not runtime Mixins.
The prior FAST-TRANSITION-1 document is design input, not an instruction to stop implementation.

First integration: opt-in built-in module; bounded FML batching without skipping packets;
content generations for registry snapshots and complete serialized recipes; cancellable,
bounded chunk preparation through vanilla tickets. Existing CRP/Velocity wire protocol stays
unchanged. FAST_ATTACH and network cache negotiation are not claimed by this integration.

Relevant hooks: `NetworkHooks.tickNegotiation`, `HandshakeHandler.tickServer`,
`NestworldRegionSystem.startModules`, `ModuleContext`, server END ticks and datapack reload.
Chunk readiness must mean a completed FULL future with lighting, not just NBT prefetch.
No production saves, installations or running servers are modified.

Build preparation: source/patch integrity found one pre-existing generated-source mismatch in
`projects/forge/src/main/java/net/minecraft/server/MinecraftServer.java` (old shutdown code).
Preserved it at `/tmp/nestworld-fast-transition-backup/MinecraftServer.java`, then synchronized
that generated file to the committed patch output. Integrity now reports MATCH. The login
listener's generated source was synchronized with this change's tracked patch as well.
Offline compile then found missing `org.slf4j:slf4j-api:2.0.9`; normal dependency resolution follows.

Important source finding: `ServerChunkCache.getChunkFuture` DOES managedBlock on the main thread.
The preparation service instead uses Core's already-public `getChunkFutureMainThread(..., false)`
and polls with getNow, which neither creates UNKNOWN tickets nor waits for generation.

2026-10-14 (later): isolated server probe now runs (`server-probe.gradle` fixed: lazy forge_server hook,
RunConfig working dir, modapi as its own dev mod, probe as MOD_CLASSES source, modapi jar filtered from
runtimeClasspathArtifacts). Result: `FT_SERVER_PROBE PASS checks=22 ticks=6` on scratch /tmp/nestworld-ft-probe.
The logged "failing-example-module" error is the intentional built-in failure-injection example.
Still untested at runtime: global /reload, real CRP client connection (packet/ACK order).
