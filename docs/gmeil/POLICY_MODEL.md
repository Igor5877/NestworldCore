# Execution policy model

`ExecutionKey = (loader, source, modId, ownerClass, methodName, descriptor)` identifies a
callback path. `modId` and class names are diagnostic/key material; hardcoded mod-specific
correctness branches are not permitted in the production policy engine.

| Policy | Permission | Evidence required |
| --- | --- | --- |
| `MAIN_COMPAT` | Execute with actual server-thread affinity, preserving sync/result/phase/order/owner exclusion | Default for unknown opaque code |
| `SERIAL_COMPAT` | One opaque causal chain active at a time; not a Java monitor around callbacks | Proven no main-thread affinity; token handoff and wait model validated |
| `REGION_CERTIFIED` | Execute on owner region with other certified regions | Positive, closed proof for this exact path and runtime artifact |
| `CORE_OWNED` | Core-controlled shared state path | Core actually owns its semantic boundary |
| `EXTERNAL_ASYNC` | Entry from an unmanaged executor | Route Core mutations to owner or reject |
| `DENY` | Do not execute | Explicit restriction or broken invariant |

Resolution priority: hard Core restriction, runtime hardening, valid certification artifact,
conservative static analysis, generic API contract, then `MAIN_COMPAT`. `UNKNOWN` is never a
synonym for `REGION_CERTIFIED`. Runtime observations may only harden a policy:
`REGION_CERTIFIED → SERIAL_COMPAT → MAIN_COMPAT → quarantine/controlled fail`.

The registry should become immutable/read-optimized after bootstrap. Positive certification is
invalidated by a changed mod JAR hash, loader/version, Minecraft version, NestWorld version,
analyzer version, or relevant configuration signature. Restrictive evidence is retained
separately; absence of a crash cannot grant parallel execution.

An `ExecutionContext` must include task/parent IDs, tick/phase, key/policy, region, token, and
lease. It travels with scheduled work and mailbox messages. A `ThreadLocal` may expose the
currently active context, but must never be its sole durable representation.
