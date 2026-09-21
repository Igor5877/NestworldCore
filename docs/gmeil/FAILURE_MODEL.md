# Failure and containment contract

A controlled callback has an explicit outcome: `SUCCESS`, `FAILED_CONTAINED`,
`FAILED_ESCALATED`, or `FAILED_FATAL`. An exception cannot appear as successful completion.
Containment does not roll back mutations already performed by opaque mod code. Automatic retry
is off unless the operation has an explicit idempotent or transactional retry contract.

Capture the original throwable and evidence before cleanup: execution key/source/policy,
mod/class/method, thread/region, tick/phase, task/parent, token/lease, wait edges, stack trace,
and causal chain if known. A bounded fingerprint groups repeated failures by key, throwable,
relevant frames, policy, and source. Report counts and affected owners without unbounded crash
report generation.

Cleanup order: mark task failed; remove wait edges; release/handoff token; restore lease;
clear active context; restore mailbox/domain bookkeeping; classify consistency; choose recovery;
emit log/JFR/metrics. Cleanup failure or a broken Core invariant is fatal. VM fatal errors and
linkage/verification failures with unknown runtime consistency are not ordinary skipped ticks.

Consistency is `KNOWN`, `LOCAL_UNKNOWN`, or `GLOBAL_UNKNOWN`. A local callback may continue or
quarantine only when scheduler and ownership state are intact and gameplay semantics permit
skipping that owner. Global lifecycle, registry, save, and network transactions cannot be
quarantined as if they were local block entities. Repeated failure under `MAIN_COMPAT` escalates
to quarantine or controlled shutdown rather than infinite per-tick retry.

The current broad `MinecraftServer.runServer` catch around `tickServer` is a last emergency
boundary. It must not be interpreted as successful callback containment; the precise callback
boundary and consistency risk are unavailable there. See `MinecraftServer.java.patch` and
`RegionThread.tickEntities` before changing their catch behavior.
