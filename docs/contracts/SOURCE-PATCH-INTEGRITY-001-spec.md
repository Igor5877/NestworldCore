# SOURCE-PATCH-INTEGRITY-001 — fail-closed source/patch integrity (contract)

**Status: CLOSED / VERIFIED / FROZEN (2026-10-04).** Implementation: commits 655f554e6 and cb289dfc0.

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

## Verification (DoD, 2026-10-04)

The implementation is in 655f554e6. The fresh-clone DoD run found that `setup` failed on every
fresh checkout: the guard treated the empty `projects/forge/src/main/java` that ForgeGradle creates
at configuration time as a tree with possible edits. That was fixed in cb289dfc0, and every check
below ran on the fixed code.

| # | DoD | Result |
|---|---|---|
| 1–2 | patches/ is the source of truth; the tree is derived | this document |
| 3 | the build never destroys developer edits | DIVERGED: `extractMapped` refused ("has edits since the last consistent state"); the edit was preserved |
| 4 | compileJava cannot run on stale or diverged sources | `compileJava` depends on the gate; every mismatch → BUILD FAILED |
| 5 | **exact incident** | fresh clone at cb289dfc0; tree generated from the **real pre-`a7550a80f` patches** (MATCH); then the five patches restored to HEAD (the `git pull`), tree left old → `reobfJar installerJar` → **BUILD FAILED, PATCHES_AHEAD**; explicit `extractMapped` → MATCH |
| 6 | tree ahead | edit to Entity.java → **TREE_AHEAD, BUILD FAILED** |
| 7 | diverged | patch and tree changed independently → **DIVERGED, BUILD FAILED** |
| — | reconciliation | TREE_AHEAD → `:forge:genPatches` (tolerated, no artifact task) → MATCH → BUILD SUCCESSFUL; `genPatches` + `reobfJar` together → BUILD FAILED |
| 8 | clean setup → build | fresh clone: `setup` SUCCESSFUL → MATCH → production build SUCCESSFUL |
| 9 | clean-room ≡ incremental | same `Generated-Source-Digest` (7bc077cd…:4787) and `Patch-Set-Digest`; `server.lzma` / `client.lzma` **byte-identical**; reobfJar 8651/8651 entries, one debug-only LVT difference (SOURCE-BUILD-REPRO-001) |
| 10 | provenance | both manifests: `Git-Commit: cb289dfc`, `Git-Dirty: false`, `Patch-Set-Digest`, `Generated-Source-Digest`, `Source-Patch-Integrity: MATCH` |
| 11 | canaries on the production artifact | clean-room reobfJar 4/4 PRESENT |
| 12 | stale artifact fails the canary | deployed 47.4.246: 4/4 MISSING, exit 1 |
| 13 | the installer / binpatch path consumes the verified state | server **installed from the clean-room installer** (47.4.263): canary 4/4 PRESENT |
| 14 | no bypass | the single `compileJava` edge gates jar → reobfJar → binpatches / universal / installer; the only tolerated mismatch (TREE_AHEAD under genPatches) is refused when any artifact task is in the build |

Environment note: a fresh clone with `--offline` cannot build the installer, because the
`downloadCrowdin` and `downloadVersionManifest` `onlyIf` predicates need network or cached files.
The clean-room production build therefore ran with network. This is unrelated to the gate.

Pre-existing and unchanged: the 5 `checkAndFixPatches` whitespace errors, which fail
`genPatches`' finalizer the same as before the gate.
