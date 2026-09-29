# CORE-CMD-001 — Brigadier fluent-chain fragility in `NestworldCommand.java`

**Area**: `NestworldCommand` / Brigadier command-tree construction. Deliberately tracked
separately from API-1A.7 — this has nothing to do with `setVelocity`'s correctness and must not
factor into that gate's PASS/FAIL verdict. Originally noted inline as "Finding API1A7-NW-008" in
`docs/contracts/api-1a7-findings.md`; promoted to its own record per explicit instruction to
keep it out of the API-1A.7 evidence set.

**Finding**: a large inline fluent command chain (`NestworldCommand.register`'s single 500+-line
Brigadier statement) allowed a structurally incorrect command tree to be produced despite
successful compilation. Discovered while adding a `testmutate setvelocity <uuid> <x> <y> <z>`
node inline into the existing chain: the resulting parse tree silently accepted zero arguments
after `uuid` — confirmed via an isolated Brigadier repro, not by inference. Root cause not fully
isolated (suspected deep paren-chain miscounting in the single fluent statement); worked around,
not fixed, by extracting the new node into its own `buildSetVelocityTestNode()` helper method
instead of further paren-counting inside the mega-chain.

**API-1A.7 impact**: NONE. This is a pre-existing structural risk in how new command nodes get
added to this file, unrelated to any `setVelocity`/`EntityMutationDispatcher` behavior.

**Action** (not yet done, tracked here for a future, separate decision): separate command
subtree builders (one method per top-level node, matching the workaround already applied for
the test node) instead of one continuously-growing fluent statement, plus some form of
command-tree shape test (even a simple "does node X accept argument type Y" smoke test) to catch
this class of silent-miscompile in the future. Not scheduled — flagging for prioritization,
not committing to a timeline.
