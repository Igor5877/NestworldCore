# SOURCE-PATCH-INTEGRITY-001 — Phase A audit: how committed patches and the built binary diverged

**Status: AUDIT COMPLETE (2026-10-03). No build change; fix not started.** This is a mandatory gate
before PERF-1.

Audit question (owner):
> How could committed Forge/vanilla patches and the generated source tree diverge while the normal
> build continued successfully?

Acceptance invariant for the future fix (owner):
> No compilation of patched Minecraft/Forge classes may begin unless the build system has
> established that their generated source state corresponds to the current committed patch inputs.

## 1. The chain, as wired (measured with an init-script probe of the real task graph)

```
patches/minecraft/**/*.java.patch            (git, normative in review/history)
  → :forge:applyPatches   (projects/forge/build/applyPatches/output.zip, SRG names)
  → :forge:srg2mcp        (MCP names)
  → :forge:extractMapped  (ExtractZip → projects/forge/src/main/java)   ← ONLY on manual `setup`/`extractMapped`
projects/forge/src/main/java                 (.gitignore'd, mutable working copy)
  → :forge:compileJava    (srcDirs: src/main/java, src/fmlcommon/java, src/snapshots_disabled/java,
                           AND projects/forge/src/main/java, added by the ForgeGradle patcher)
  → :forge:jar → :forge:reobfJar
  → :forge:genServerBinPatches / genClientBinPatches
       (BYTES from reobfJar, i.e. from the compiled tree; patches/ only selects WHICH classes)
  → :forge:universalJar / installerJar  (binpatches inside the installer)
  → install: processors apply binpatches → forge-<ver>-server.jar / client jar (the deployed binary)
reverse direction, manual:  projects/forge/src/main/java → :forge:genPatches → patches/
```

`compileJava` dependencies are only the FML sub-projects and `:modapi`. **No task between the
patch files and compilation is part of the normal build graph.** `checkAndFixPatches` validates
patch formatting only, never that the tree and the patches agree. `setup` = `extractMapped` is a
manual task.

## 2. Source of truth, de facto vs. intended
- **In git:** only `patches/` (plus the Core sources). `projects/forge/src/main/java` is ignored.
- **In the build:** only the generated tree is compiled; the patches are not a compile input.
- **In the documented workflow:** the tree is edited, then `genPatches` regenerates *all* patches.
  `docs/OPTIMIZATION_PACKAGE.md` instructs: "genPatches regenerates *all* patches with offset noise
  — revert every patch except the intended one".

So there are **two mutable sources of truth** (patches in git, the tree on disk) with manual,
per-file synchronisation in both directions. The process treats tree↔patch divergence as noise to
discard. Real staleness (reverts of patch content the tree never received) is indistinguishable from
that noise.

## 3. Concrete root-cause chain of the incident
1. 2026-09-22/23: commits `a7550a80f` and `8733411c8` (author Igor5877, made outside this workspace)
   change `Entity`, `ServerChunkCache`, `TheEndGatewayBlockEntity`, `ServerPlayer` and `ChunkMap`
   patches, plus the Core class `DimensionChangeScope` / `SaveRendezvousThrottle`.
2. **2026-09-23 13:46:57: `git pull origin …: Fast-forward`** in this workspace (reflog). It updates
   the tracked `patches/` and `src/main/java`, but not the ignored generated tree.
3. Nothing regenerates the tree: no build task depends on patch content (§1).
4. 18:57 the same day: `ServerPlayer.java` in the tree is hand-edited; its mtime matches and its
   content contains the pulled change. The other four files are not, so the tree is **partially**
   synced.
5. Every later build compiles the old `Entity` / `ChunkMap` / `ServerChunkCache` / `TheEndGateway`
   sources. Gradle's up-to-date checks are correct **for the declared inputs**: the tree did not
   change, and the patches are not inputs. The binpatches are produced from those classes and the
   build says SUCCESSFUL.
6. 2026-10-02: the API-1B-CLOSURE `genPatches` round trip surfaced it: regenerating patches from the
   tree would have reverted the pulled changes. The tree was regenerated (`extractMapped`).

Git history was **not** damaged: `d405f5187` (a later local commit) touched only the
MinecraftServer patch; HEAD still contains every pulled change (verified for `DimensionChangeScope`
in the ServerPlayer patch).

## 4. Impact — measured at the artifact level

Bytecode canary (`tests/source-patch-integrity/canary.sh`, `javap` on patched classes of the **deployed** binary
`forge-<ver>-server.jar`), one symbol per independently patched class:

| Canary | Commit |
|---|---|
| `Entity.nestworldChangeDimensionInner` | a7550a80f |
| `ServerChunkCache` → `DimensionChangeScope.isActive` | a7550a80f |
| `TheEndGatewayBlockEntity` → `DimensionChangeScope.enter` | a7550a80f |
| `ChunkMap.nestworldSaveThrottle` | 8733411c8 |

| Deployed binary | Canary |
|---|---|
| scratch 47.4.237 (10-01), 47.4.246 (10-02 08:33), 47.4.247 (10-02 12:52) | **4/4 MISSING** |
| scratch 47.4.249 (10-02 19:16, after regeneration), 47.4.258 (10-03) | 4/4 PRESENT |
| **`/root/skyblock-island-deploy/extracted/minecraft`, 47.4.221 (09-23 14:01) and 47.4.233 (09-25 08:08)** | **4/4 MISSING** |

Builds older than 2026-09-22 also show MISSING, which is expected because the commits did not exist
yet. **The SkyBlock island deployment template ships a binary without the dimension-change
real-chunk fix and the save-rendezvous duty cycle**, although both are in git. In those binaries the
Core class `DimensionChangeScope` exists, but no vanilla hook calls it, so the feature is silently
inert. Nothing was changed there; this is reported for the owner's decision.

## 5. Artifact provenance — what can be verified today
- **Manifest:** `GitCommit` / `Git-Branch` / `Timestamp` / version `47.4.<commit count>`.
  **The GitCommit does not record a dirty working tree**, and it **records nothing about the
  generated tree**. Example: artifact 47.4.258 says `GitCommit: 6351a67f` but contains the then
  uncommitted option-A code (committed later as `cf215a70a`). "Commit in the jar" therefore proves
  neither "only that commit's code" nor "that commit's patches".
- **Verifiable now:**
  - `git rev-parse <commit>:patches` (the patch-set tree hash) is a deterministic patch-set digest;
  - the bytecode canary on the deployed patched classes;
  - regenerate-and-compare: clean-room build of the same commit, then compare binpatches (§6).
- **Not recorded anywhere today:** a digest of the generated tree used for compilation; the
  dirty/clean state; the patch-set digest.

## 6. Clean-room reproduction (same commit `6cae26ac5`)

A fresh clone was set up (`./gradlew setup`, 264 s) and built (`reobfJar` + binpatches, 451 s). It
was compared with the incremental build in the main workspace, where the tree had been regenerated
on 10-02:

| Stage | Result |
|---|---|
| generated tree | **0 / 4 787 files differ** |
| `server.lzma`, `client.lzma` (the binpatches the installer ships) | **byte-identical** |
| `reobfJar` (8 651 entries) | 1 entry differs: `net/minecraftforge/resource/ResourcePackLoader$1.class`, **only LocalVariableTable parameter names** (`packId`/`isBuiltin`/`source` vs `arg0`/`arg1`/`arg2`); instructions identical. Cause not proven (likely the reobf renaming depending on classpath state); recorded as residual, debug-only nondeterminism |
| canary | 4/4 PRESENT in both |

Conclusion: with a tree that **does** match the patches, the pipeline is semantically reproducible.
The defect is entirely the missing link "patch content → generated tree → compile", plus the lack of
provenance for it.

## 7. Requirements for the fix (to be chosen after review)
- **One source of truth:** the committed patches. The generated tree must be derived state, never
  an independent input.
- The acceptance invariant above, enforced by the build graph: either regenerate automatically when
  the patch inputs change, or fail the build on a mismatch. **Silent success on stale sources must
  become impossible.**
- **Developer edits to the tree** (the genPatches workflow) must not be silently discarded by
  automatic regeneration. The fix must distinguish "tree is ahead (local edit, run genPatches)"
  from "tree is behind (patches changed, regenerate)". Both cases fail or act loudly.
- **Provenance:** the artifact records the patch-set digest, a generated-tree digest and dirty
  state, so a jar can be checked against git without trusting `GitCommit`.
- **Negative canary (DoD):** change a patch after generation (separately for at least Entity,
  ChunkMap, ServerChunkCache, TheEndGatewayBlockEntity). Acceptable outcomes are correct automatic
  regeneration or BUILD FAILED; stale source with BUILD SUCCESSFUL must be impossible. The
  bytecode canary on the resulting artifact confirms which one happened.
- **Out of scope here:** fixing the residual LocalVariableTable nondeterminism; redeploying the
  SkyBlock template (owner decision).
