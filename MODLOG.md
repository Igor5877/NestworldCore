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

2026-10-15. Revalidated after the branch fast-forwarded through the AE2 compatibility work:
`tests/fast-transition/check.sh` remains 15/15 and `:forge:compileJava` passes with source/patch
integrity MATCH. The client working tree now replays the persisted registry snapshot on each title
screen reached without injected state, including after a full disconnect. Protocol wording was
updated to distinguish a faster connection path from an unproven launch-to-play improvement. The
current dedicated-server probe passes; its counter was made deterministic (18 distinct checks)
instead of counting the same two waiting-state assertions again on every tick before READY.

2026-10-15. MODULE-2 baseline implemented after the owner required a real module directory.
Core now creates/scans `nestworld-modules/`; validates strict JSON descriptors, SPI/capability and
mapping versions, dependencies, duplicate ids, reserved/shadowed classes, unsupported transformer
metadata and SPI type/member references; and loads each accepted jar through a JVM-lifetime child
classloader. Typed listeners, command ownership, reverse shutdown and fail-closed callback handling
are Core-owned. FastTransition moved to `modules/fast-transition` (`dev.nestworld.fasttransition`),
with no hard-coded constructor or feature switch. A real external-JAR dedicated-server run passed
20 checks; an invalid descriptor was isolated while the valid module reached RUNNING.
