# SOURCE-PATCH-INTEGRITY-001 — fail-closed source/patch integrity (contract)

The audit is in `SOURCE-PATCH-INTEGRITY-001-audit.md`. Owner decisions (2026-10-03/04): fail closed,
no automatic regeneration, patches are the source of truth, three-way diagnosis, provenance in this
gate.

## Source of truth
- **`patches/` (committed) is the only source of truth** for patched Minecraft/Forge classes.
- **`projects/forge/src/main/java` is DERIVED.** It is the mutable development representation of
  the current patch set, never an independent input. Local edits to it are uncommitted developer
  state; the gate protects them from loss until they are turned into patches (`genPatches`).

## Acceptance invariant
> No compilation of patched Minecraft/Forge classes may begin unless the build system has
> established that their generated source state corresponds to the current committed patch inputs.

## Mechanism (`build.gradle`, project `:forge`)

**`verifySourcePatchIntegrity`** (`compileJava` depends on it, so does `universalJar`):
- **Expected state** = `srg2mcp/output.zip`. This is exactly what `extractMapped` would write; its
  inputs are the patches plus every other generation input (access transformer, exc, mappings), and
  it is computed without touching the tree.
- **Comparison:** canonical SHA-256 of the zip entries vs. of the tree files (sorted relative path +
  per-file hash, plus the file count).
- **MATCH:** the build proceeds; the baseline `projects/forge/.patch-state` (git-ignored, survives
  `clean`) is refreshed to the consistent digests.
- **Mismatch:** BUILD FAILED, with a three-way diagnosis against the baseline:

| State | Meaning | Action shown |
|---|---|---|
| PATCHES_AHEAD | generation inputs changed, tree did not (e.g. after `git pull`) | regenerate: `:forge:extractMapped` |
| TREE_AHEAD | tree changed, generation inputs did not | `:forge:genPatches`, keep the intended patch changes |
| DIVERGED | both changed independently | manual reconciliation |
| UNKNOWN_BASELINE / INCONSISTENT_BASELINE | the direction cannot be determined | explicit decision required |

- **Reconciliation path:** `genPatches` compiles the tree itself (`applyRangeMapBase` →
  `extractRangeMap`). So **TREE_AHEAD only** is tolerated when `:forge:genPatches` is in the build
  **and no artifact task is**. Artifact tasks: `reobfJar`, `universalJar`, `signUniversalJar`,
  `installerJar`, `genServerBinPatches`, `genClientBinPatches`. `:forge:jar` is the intermediate
  MCP jar inside genPatches' own graph and is not shipped. PATCHES_AHEAD and DIVERGED fail even
  then, because genPatches from a stale tree would revert committed patch content, which is the
  incident mechanism.
- **The build never regenerates the tree.** Regeneration is explicit (`setup` / `extractMapped`).
  `extractMapped` itself refuses to overwrite a tree that has edits since the last consistent state,
  or that has no baseline, unless `-PforceRegeneratePatchedSources=true` is passed. A fresh checkout
  without a tree is always allowed.
- **Coverage:** every shipped patched class comes from `compileJava` → `jar` → `reobfJar` →
  binpatches / universal / installer. The single `compileJava` edge therefore gates all
  production artifact paths.

**Provenance** (universal jar manifest, from the verified state):
- `Git-Commit`;
- `Git-Dirty` (`true` = not reproducible from Git-Commit alone; not "bad");
- `Patch-Set-Digest` (canonical digest of `patches/`);
- `Generated-Source-Digest` (canonical digest of the compiled tree);
- `Source-Patch-Integrity` (`MATCH`).

A timestamp is not integrity evidence.

**End-to-end assertion:** `tests/source-patch-integrity/canary.sh <jar>` checks one committed patch
symbol per independently patched class (Entity, ServerChunkCache, TheEndGatewayBlockEntity,
ChunkMap) in the bytecode of the produced or deployed binary. The canary does not replace the gate.

## Out of scope
- **SOURCE-BUILD-REPRO-001** (registered): the residual non-semantic nondeterminism of
  `ResourcePackLoader$1`, whose LocalVariableTable names differ between clean-room and incremental
  builds.
- The SkyBlock template redeploy: a separate operational change, done with a verified artifact.
- The 5 pre-existing `checkAndFixPatches` whitespace errors (BulkSectionAccess and others).
