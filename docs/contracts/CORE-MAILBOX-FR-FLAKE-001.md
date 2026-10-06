# CORE-MAILBOX-FR-FLAKE-001 — intermittent free-running mailbox fixture failure (OPEN)

**Status (owner, 2026-10-06): OPEN / PRE-EXISTING / DEFERRED INVESTIGATION / NON-BLOCKING FOR PERF-1B**, on the condition that every PERF-1B run proves this state absent in its own window (per-run ownership validity gate, `PERF-1B-spec.md` §8). Not to be closed as a "fixture race" until that is proven. The A/B (same signature on 264 and 306) excludes the D1 instrumentation as the cause, but does not decide whether the defect is in production Core or in the fixture. Related gate: CORE-MAILBOX-MERGE-001 stays CLOSED / FROZEN (its closure property, no accepted message lost, holds in these runs).

Observed 2026-10-06; not investigated; no fix proposed. Found during the
PERF-1B pre-pilot API regression of candidate 47.4.306. It is **pre-existing**: the trusted
reference 47.4.264 shows the same failure signature. It is therefore not a regression of the
PERF-1B candidate.

## Observation
`/nestworld testmutate mailbox all` (CORE-MAILBOX-MERGE-001 fixtures, `-Dnestworld.mailboxAudit=true`,
`-Dnestworld.freeRunningRegions=true`) on a freshly installed scratch server (`api1b7/server`, the
same instance and procedure as the CORE-MAILBOX closure runs):

| Build | Runs | Failing runs | Failing-run result |
|---|---|---|---|
| 47.4.306 (candidate, `7803c678`) | 9 | 1 | pass=53 fail=7 |
| 47.4.264 (trusted reference, `dab499ff`) | 7 | 1 | pass=56 fail=7 |

All other runs gave pass=69 fail=0. Barrier mode (free-running off) on 306 gave pass=64 fail=0 in its
single run. The fixture start delay after `Done` (0.8 s … 61 s) does not separate passing from
failing runs.

**Same signature on both builds:**
- the same cluster fails together: `l5.blockWriteAndWorldState`, `l1-split.exactlyOneApply`,
  `l1-split.appliedAtSuccessorOwner`, `l1-merge`, `l2a`, `l2b` (or `l2b.removeFailedHandedOff`),
  `l2b-consumed.preRetirementConsumer`;
- the scenario's test entity ends with **no owning region** ("entity owner now rnull"). Its
  message closes as `TARGET_NOT_FOUND` instead of applying at the successor owner;
- later scenarios then hit `findOwningRegion(uuid) == null` or `region == null` (NPE in the
  fixture).

**What still holds in the failing runs:** the transport accounting closes at every scenario
(`unaccounted=0 balance=0 duplicates=0 misrouted=0`), and C10 has 0 violations. No message is
lost or applied twice; the affected messages end in an explicit terminal.

## Open question (not answered here)
Why does a live test entity end up owned by no region in free-running mode? Two candidates, neither
tested:
- a fixture precondition race (the fixture assigns ownership manually);
- a real ownership gap in free-running mode (a live entity with no owner is not ticked).

## Evidence
`/root/perf1-evidence/core-mailbox-fr-flake/`:
- failing-run logs `fail-306-142136.log` and `fail-264-152140.log`;
- run tables `perf1b-reg-*.out`;
- deploy script `deploy-art2.sh` (sha-verified exact artifact, marker-class assertion);
- JVM args `jvm-audit*.txt`.

## Impact on PERF-1B
- PERF-1B windows run with a fixed layout (no split or merge), check owned = ticked per run, and
  run ownership assertions.
- Owner decision: non-blocking, with the per-run ownership gate. A PERF-1B run in which this state
  appears is INVALID.
- The question is separate from CORE-MAILBOX-MERGE-001: can a live entity end up without an
  authoritative region owner in the free-running lifecycle?
