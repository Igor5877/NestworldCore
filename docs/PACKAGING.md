# NestWorld Mod API — packaging (API-PKG-1)

Status: CLOSED, evidence-backed (2026-09-29). This document explains how a third-party mod
depends on `net.nestworld.modapi` and records the Gradle module graph that makes it possible.
Full raw evidence for this gate: session history (agent `a2c055ab73129efbc`'s API-PKG-1 run) —
this file is the durable summary.

## What API-PKG-1 proved

Not just that `net.nestworld.modapi.entity`'s ABI is Core-free (already known from API-1A.5P/
API-1A.6's `jdeps` checks) — that it is a genuinely separate, **Maven-consumable build
artifact** a third-party developer can depend on without any knowledge of, or access to,
NestWorldCore's own source:

- Standalone Gradle module (`:modapi`), compiled independently of `:forge` (Core).
- Published to `mavenLocal()` under real coordinates.
- A **genuinely external** Gradle mod project (its own `settings.gradle`/`build.gradle`, not a
  `project(...)` dependency into this repo) resolved it via `compileOnly`/`implementation` +
  `mavenLocal()`, exactly as an outside developer would.
- That external mod, built once, ran correctly and called `setVelocity` successfully against
  **both** genuine upstream Forge (no NestWorldCore present) and a real NestWorld-patched
  dedicated server — same jar, unchanged, both physically verified.

## The artifact

```
groupId:    net.nestworld
artifactId: nestworld-mod-api-forge-1.20.1
version:    1.0.0-SNAPSHOT
```

Published locally via `./gradlew :modapi:publishToMavenLocal`, landing at:

```
~/.m2/repository/net/nestworld/nestworld-mod-api-forge-1.20.1/1.0.0-SNAPSHOT/
    nestworld-mod-api-forge-1.20.1-1.0.0-SNAPSHOT.jar
    nestworld-mod-api-forge-1.20.1-1.0.0-SNAPSHOT-sources.jar
    nestworld-mod-api-forge-1.20.1-1.0.0-SNAPSHOT.pom
    nestworld-mod-api-forge-1.20.1-1.0.0-SNAPSHOT.module
```

`mavenLocal()` only — this gate deliberately did not publish to any real remote repository
(Maven Central, a project-hosted Maven, Modrinth's maven, etc.). Publishing destination is a
separate, later decision; this gate exists to prove the packaging *boundary* itself, not to
ship it publicly yet.

## Module graph

```
:clean  (genuine upstream Forge 1.20.1, unpatched by NestWorld -- pre-existing project,
         this repo's own build already uses it as :forge's patcher `parent`)
   ^                                  ^
   |  compileOnly                    |  patcher { parent = ... }
   |                                  |
:modapi                            :forge
(net.nestworld.modapi.entity,      (NestWorldCore -- patched vanilla + Forge +
 zero net.nestworld.region.*        net.nestworld.region.* etc., ALL monolithic
 imports, confirmed by jdeps)       in one sourceSet, per FORGE-001's finding)
   ^
   |  implementation
   |
 :forge  (also depends on :modapi so Core code can call
          net.nestworld.modapi.entity.* as a normal project dependency)
```

No cycle: `:modapi` and `:forge` both depend on `:clean`; only `:forge` depends on `:modapi`.
`:modapi` deliberately does **not** depend on `:forge` or `:clean`'s Forge-specific API surface
— it compiles against `:clean` only for `net.minecraft.*` classes (confirmed: zero
`net.minecraftforge.*` imports anywhere in `net.nestworld.modapi.entity`, so even `:clean`'s
Forge layer is unused, vanilla alone would have sufficed).

**Why `:clean`, not `:forge`, as the compile-time source of vanilla classes**: `:forge`'s
`sourceSets.main.java.srcDirs` includes `$rootDir/src/main/java` — the same monolithic tree that
mixes NestWorld's own patches with `net.nestworld.region.*`. API-1A.7's `FORGE-001` finding
already found deep, un-strippable Core coupling baked into patched vanilla's own bootstrap
(`EntityType.<clinit>` directly referencing `net.nestworld.region.EntityMutationOp`, among
others). Compiling `:modapi` against `:forge` would silently risk reintroducing exactly that
coupling. `:clean` is genuinely unpatched by NestWorld (it's what `:forge` itself patches
*from*), so it's the correct, evidence-grounded choice.

## Source location

`net.nestworld.modapi.entity`'s five classes live at
`projects/modapi/src/main/java/net/nestworld/modapi/entity/` — moved out of
`src/main/java/net/nestworld/modapi/entity/` (where they compiled as part of `:forge`'s
monolithic sourceSet through API-1A.6/1A.7/1A.8) as part of this gate. No source content
changed, only location and the module wiring around it.

## How an external mod depends on it (dev workflow proven this pass)

```groovy
// settings.gradle -- nothing special needed
// build.gradle
repositories {
    mavenLocal()   // or wherever it's eventually published
}
dependencies {
    minecraft "net.minecraftforge:forge:1.20.1-<forge version>"
    implementation 'net.nestworld:nestworld-mod-api-forge-1.20.1:1.0.0-SNAPSHOT'

    // To ship a single, self-contained deployable jar (real dedicated-server testing used
    // this), embed the dependency via Forge's own jar-in-jar mechanism:
    jarJar('net.nestworld:nestworld-mod-api-forge-1.20.1:[1.0.0-SNAPSHOT,)') {
        jarJar.ranged(it, '[1.0.0-SNAPSHOT,)')
    }
}
```

`implementation` alone is sufficient for `runServer`/`runClient` ForgeGradle dev tasks (Gradle
puts it on the dev run classpath automatically). For a real, standalone deployed server, the
dependency's classes must physically be inside the shipped mod jar (or a second jar Forge's mod
loader recognizes) — `jarJar` is Forge's standard mechanism for this and was confirmed working
(`META-INF/jarjar/nestworld-mod-api-forge-1.20.1-1.0.0-SNAPSHOT.jar` embedded correctly in a
test build). The actual dedicated-server verification runs in this pass merged the dependency's
compiled classes directly into the mod's own `jar` task output before reobfuscation (a
`configurations { modapiEmbed }` + `from zipTree(...)` addition) to get one single, correctly
SRG-remapped jar in one step — either approach produces a working deployable artifact; `jarJar`
is the more idiomatic Forge-native path for a real release.

## Evidence (2026-09-29)

| Check | Result |
|---|---|
| `:modapi:compileJava` standalone (against `:clean` only) | PASS |
| `:forge:compileJava` with `:modapi` as a project dependency | PASS, no cycle |
| `jdeps` on the published jar | zero `net.nestworld.region`/`adapter`/`net.minecraftforge.*` — only `net.minecraft.*`, `org.slf4j.*`, `java.*` |
| `publishToMavenLocal` | real artifact at the path above |
| External consumer project, real Maven coordinate resolution | `compileJava` succeeds against `mavenLocal()` only, zero `project(...)` dependency |
| External consumer mod, genuine upstream Forge 1.20.1-47.4.20 dedicated | **PASS, physically verified** — `setVelocity` from server thread and from an explicitly spawned worker thread both applied exactly the requested velocity (`Motion` NBT read back matched request exactly, both cases) |
| Same consumer mod jar (unchanged), real NestWorld-patched dedicated (47.4.234) | **PASS, physically verified** — `VelocityBackendRegistry.install()` fired on boot, both calls routed through the real `NestworldVelocityBackend`/`EntityMutationDispatcher` region-aware path (confirmed via `[API1A7] NestworldVelocityBackend.setVelocity() ... intentVersion=1` log lines — API-1A.8's temporal-versioning mechanism active end-to-end through the external artifact too), `Motion` NBT matched requested velocity exactly in both cases |
| Integrated (client), either platform | NOT ATTEMPTED this pass — explicitly lower priority per this gate's own directive (the packaging boundary, not `setVelocity`'s runtime behavior, was the new thing under test; `setVelocity` itself was already proven on integrated Forge in API-1A.7's `API1A7-FORGE-INTEGRATED` finding) |

**One real bug found and fixed during this pass, worth recording**: the reused test-mod
template's `@Mod("...")`/`@Mod.EventBusSubscriber(modid=...)` annotations were hardcoded string
literals left over from an earlier throwaway mod id, independent of `gradle.properties`'
`${mod_id}`-templated `mods.toml`. The mismatch produced `ModLoadingException: mods.toml missing
metadata for modid <old-id>` at boot — a real, reproducible Forge mod-loading trap (annotation
literal vs. templated `mods.toml` can silently diverge when reusing a template), not a
`net.nestworld.modapi` defect. Fixed by updating the two annotation literals to match.

## Regression found and fixed (2026-09-30) — `:forge`'s OWN deployed jar was broken

**What happened**: API-PKG-1's verification tested the standalone `:modapi` artifact against an
EXTERNAL consumer (mavenLocal(), a separate project) — real and correctly evidenced for that
question. It never re-booted `:forge`'s own actual deployed server jar after the extraction.
`implementation project(':modapi')` in `:forge`'s dependencies gives compile/IDE classpath
resolution only — it is NOT fat-jar inclusion. Result: `net/nestworld/modapi/**` was silently
**absent** from `:forge:universalJar`'s actual output (confirmed: 0 entries, vs.
`net/nestworld/region`'s 222) ever since the API-PKG-1 commit (`7123ef366`) — meaning the real,
deployable NestWorldCore server jar could not boot at all
(`NoClassDefFoundError: net/nestworld/modapi/entity/VelocityExecutionBackend` at
`NestworldMod.<init>`, since `NestworldVelocityBackend`/`NestworldRegionSystem`/
`EntityMutationDispatcher` all call `net.nestworld.modapi.entity.*` directly). Found during
MODULE-1's live-verification pass (`docs/contracts/Module1Foundation.md`), the first time
anything rebuilt and booted `:forge`'s own jar from a fresh build since the extraction.

**Fix**: `build.gradle`'s `universalJar` task now includes `from project(':modapi')
.sourceSets.main.output` alongside its existing sources — merges `:modapi`'s compiled classes
directly into the same jar, same classloader, same package layout Core already expects. This is
deliberately NOT `jarJar`/shading (that mechanism is for embedding an EXTERNAL artifact with its
own isolated classloader-remapping — the wrong tool for merging a project's own module into its
own deployed jar).

**Verified, not assumed**: rebuilt `:forge:universalJar`, confirmed `net/nestworld/modapi/**`
now present (8 entries: 2 directories + 6 class files, matching all 5 source classes + 1 nested
record). Rebuilt `:forge:installerJar`, ran the real installer into a fresh isolated instance,
booted via the real `run.sh` launcher (not a bare `java -jar`, which fails differently on this
project's SRG-obfuscated server jar — same lesson as `API1A7-FORGE-002`'s original tooling
mistake) — server reached `Done (9.339s)! For help, type "help"` with zero
`NoClassDefFoundError`. Test server process confirmed not left running afterward.

**Lesson for future module/artifact extractions**: a `project(...)` dependency proves compile-time
correctness, never packaging correctness. Any future extraction (API-1B or beyond, per the "Not
yet decided" item below about extending this pattern) must re-verify the CONSUMING project's own
deployed artifact boots, not only that an external consumer of the extracted artifact does.

## Regression found and fixed (2026-10-01) — `:modapi` shipped un-reobfuscated (MCP names on an SRG runtime)

**Symptom** (found during API-1B live verification, isolated scratch instance): the first Mod API
call on a deployed NestWorld jar failed with
`java.lang.NoSuchMethodError: 'java.util.UUID net.minecraft.server.level.ServerPlayer.getUUID()'`.

**Cause**: the 2026-09-30 fix above merged `project(':modapi').sourceSets.main.output` into
`universalJar` — a stage AFTER `reobfJar`. `:modapi` compiles against MCP-named vanilla
(`:clean`); the production runtime is SRG-named. `javap` on the deployed jar: every
`net.nestworld.modapi.*` class referenced vanilla by MCP names (`Entity.getUUID`,
`MinecraftServer.isSameThread`) while Core classes used SRG (`m_20148_`, `m_18695_`).
**Impact**: the public Mod API — including the frozen `setVelocity` (`NestWorldEntities`,
`ForgeVelocityExecution`) — threw on first use on every deployed NestWorld jar built since
`7123ef366`. It went unnoticed because nothing exercised the Mod API on a deployed jar after
API-PKG-1 (MODULE-1's live pass never called it) and the canary only checked class *presence*.
The published Maven artifact (MCP-named by design, for ForgeGradle `fg.deobf` consumers) is
unaffected.

**Fix** (`build.gradle`): merge `:modapi`'s output into `:forge`'s `jar` task — the input of
`reobfJar` — instead of `universalJar`. Besides putting the classes through the renamer, this
gives the renamer the full patched-vanilla class hierarchy it needs for inherited members
(`ServerPlayer.getUUID` → `Entity.m_20148_`); remapping `:modapi`'s jar on its own would lack it.
The `universalJar` canary now also fails the build if a listed `:modapi` class lacks the SRG name
`m_20148_` or still contains `getUUID`.

**Evidence**:
- Negative test: reintroducing the post-reobf merge → `BUILD FAILED`, `'…/ForgeVelocityExecution.class'
  references vanilla by MCP names (expected SRG m_20148_ …)`; `build.gradle` restored byte-identical.
- Positive: installer build, fresh `--installServer`, real boot to `Done`; `javap` shows SRG names in
  `ForgeVelocityExecution`, `NestWorldEntities` (incl. inherited `ServerPlayer.m_20148_` in the API-1B
  work-in-progress classes built alongside).
- `setVelocity` through the public facade on the deployed jar: player target
  `OWNER_RESOLVED owner=null → TERMINAL APPLIED`; region-owned armor stand
  `ENQUEUE to=2 → NestWorld-Region-2 APPLY → TERMINAL APPLIED`. `NoSuchMethodError` count: 0.
- Re-verified on the exact commit tree (all API-1B work stashed): installer build incl. the new
  canary PASS, fresh install + boot `Done (59.444s)`, zero `modapi/inventory` classes in the jar,
  SRG names in `ForgeVelocityExecution`/`NestWorldEntities`, public-facade `setVelocity` player
  `opId=200 owner=null → TERMINAL APPLIED` and armor stand `opId=201 ENQUEUE to=3 →
  NestWorld-Region-3 APPLY → TERMINAL APPLIED`, `NoSuchMethodError` 0.

**Lesson**: compile success, class presence, and an external consumer passing all prove nothing
about the *deployed* jar's linkage. The canary now checks naming, and any Mod API gate must call
the API on a deployed NestWorld jar at least once.

## Not yet decided / out of scope for this gate

- Publishing to a real, non-local Maven repository (Maven Central, GitHub Packages, a
  self-hosted Maven, Modrinth's maven) — separate, later decision.
- Versioning policy beyond the placeholder `1.0.0-SNAPSHOT`.
- Whether/how `jarJar` vs. a documented "put both jars in `mods/`" vs. some other distribution
  shape becomes the *recommended* pattern for real mod authors — this gate proved the mechanism
  works, not which one NestWorld should officially recommend.
- Extending this same module-extraction pattern to any future primitive (API-1B and beyond) —
  each would need its own evaluation of what it needs from `:clean` vs. what, if anything, is
  genuinely Core-specific.
