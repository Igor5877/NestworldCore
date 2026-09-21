# Positive certification rules

Static analysis first searches for disqualifiers: mutable static access, opaque singleton
graphs, custom threads/executors/futures, reflection and method handles, Unsafe/JNI/native,
unresolved `invokedynamic`, missing classes, unknown call targets, mutable foreign reference
escape, and world APIs whose ownership semantics cannot be proven. A concurrent collection
alone is not proof of a safe callback.

`REGION_CERTIFIED` requires a closed reachable call graph for the exact `ExecutionKey`, a
verified owner context, no unbounded opaque shared mutation, and proof that every Core-owned
world mutation respects owner routing. Unknown means cannot certify. The existing
`StaticCandidateAnalyzer` is race-discovery input, not a positive certifier; runtime race
detectors provide negative evidence, never permission.

A positive artifact binds its proof to Minecraft, loader and loader version, NestWorld version,
analyzer version, mod JAR SHA-256, and relevant configuration signature. Any mismatch discards
positive certification and falls back to `MAIN_COMPAT`. Do not infer that an entire mod is safe
from one certified path.

The existing exact-version coremod scripts remain offline research oracles. They are not
registered in the production `META-INF/coremods.json` and must not be counted as generic GMEIL
certification. Their tests define workloads that original unmodified mod JARs must pass with
GMEIL enabled before the modpack is considered safe for region-parallel execution.
