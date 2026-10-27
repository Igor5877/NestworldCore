# nestworld-compat-mekanism changelog

## 0.1.1 — 2026-10-27 (MEK-GATE-PERF-001)
- Compatibility gate is ~28x faster (median 5795 ms → 204 ms on a 196-mod pack): it reads only the Mixin
  configs registered with the Mixin runtime and the classes under their packages, instead of every class of
  every mod. Same ACTIVE contract (exact Mekanism 10.4.16 jar SHA, exact target-class SHA, no foreign Mixins).
- **Fix:** the compatibility gate no longer misses `@Mixin` targets declared with dotted class names
  (`@Mixin(targets = "mekanism.common...NetworkAcceptorCache")`). 0.1.0 reported ACTIVE in that case.
- New runtime fail-closed guard: if a foreign Mixin config targets a protected class while the gate said ACTIVE,
  startup is refused (`acceptTargets`).
- `/nwcompatmek status`: state, reason, fingerprints, foreign-Mixin count, gate timing per phase, implementation.
- M-A (the MEKANISM-REGISTRY-RACE-001 fix itself) unchanged.

## 0.1.0 — 2026-10-26 (MEKANISM-REGISTRY-RACE-001)
- M-A: region-thread-safe publication of Mekanism transmitter acceptor changes, exact-version gated.
