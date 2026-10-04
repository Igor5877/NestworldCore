# SOURCE-BUILD-REPRO-001 — residual non-semantic build nondeterminism (registered, not scheduled)

**Status: REGISTERED (2026-10-04). Not a blocker; out of scope of SOURCE-PATCH-INTEGRITY-001.**

**Finding.** A clean-room build and an incremental build of the same reconciled commit (6cae26ac5,
and again cb289dfc0) produce a `reobfJar` that differs in one entry out of 8651:
`net/minecraftforge/resource/ResourcePackLoader$1.class`.
- **What differs:** only the LocalVariableTable parameter names (`packId`/`isBuiltin`/`source` vs
  `arg0`/`arg1`/`arg2`).
- **What is identical:** the bytecode instructions, the generated source tree (same digest), and
  the binpatches `server.lzma` / `client.lzma` (byte-identical).

**Probable cause (unproven):** the reobf renaming step assigns parameter names depending on
classpath/incremental state.

**Impact.** No semantic difference and no difference in any shipped binpatch. It only prevents a
byte-for-byte reproducible-build claim for the universal jar.

**Owner decision (2026-10-03):** record it; do not fix inside the source-integrity gate.
