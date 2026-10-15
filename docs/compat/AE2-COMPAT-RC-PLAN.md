# nestworld-compat-ae2 — plan to production canary / RC (owner ТЗ, 2026-10-15)

Standing plan. Each step follows evidence-first: audit → STOP / review → implementation →
deterministic fixtures + negative controls → stress / soak.

**Baseline**

| State | Items |
|---|---|
| Frozen (do not touch without a concrete regression) | ENERGY-001, T2 / Design-1.5, QUEUE-001, LEGACY-ORIENTATION-DISK, GRID-PHASE-001 |
| Verified fail-closed | LEGACY-ORIENTATION-LIVE |
| Open | AE2-COMPAT-P2P-001, SOLARFLUX-COMPAT-001, production canary, selective / full unpin review, RC |

**Order**
1. P2P audit (`AE2-COMPAT-P2P-001-AUDIT.md`)
2. STOP: review the P2P design
3. P2P implementation
4. Fixtures P2P-1…7, each with a discriminating negative control
5. 120 s stress, then soak
6. SolarFlux source audit (`SOLARFLUX-COMPAT-001-AUDIT.md`)
7. STOP: review the SolarFlux design
8. SolarFlux fix. A patched mod is allowed. Classify the result as STOCK / ADAPTER / PATCHED MOD /
   UNSUPPORTED
9. Fixtures SF-1…6, then stress
10. `AE2-UNPIN-READINESS.md`. The verdict is exactly one of FULL PIN / SELECTIVE UNPIN /
    FULL AE2 UNPIN
11. Production-like canary C0 → C3 (30 min → 2 h → 6 h → 24 h). Change one variable at a time
12. v1.0-rc1, with the audited matrix only

**Binding rules**
- Lock rules for P2P:
  - per-grid P2P state;
  - the lock is never held across a foreign capability / FE / item / fluid callback;
  - writers are linearizable;
  - "find free + register" is one transaction;
  - continuation after a foreign call runs capture generation → unlock → call →
    revalidate → commit / retry / cancel.
- Lock rules for SolarFlux:
  - every mutation of one panel's energy state goes through one SolarFlux-owned linearizable
    primitive;
  - that lock is never held across an AE2, Forge Energy, foreign-capability, world-mutation or
    event callback.
- Manifest: unknown → diagnostic → `manifestEligible=false` → DEGRADED / retain pin. Never
  fail-open.
- Selective unpin needs a real enforcement boundary (classifier / routing), not documentation.
- Incident policy: faulting mod ≠ mutating mod. The chain is stack → shared state → all writers →
  thread ownership → reproduce → classify → minimal mitigation → adapter / patch → negative
  control → stress.
- **Never move from audit to implementation while the audit shows an unknown synchronous contract
  or external-callback boundary.** Fix the model and get review first.
- No cleanup / simplification / kernel unification of frozen mechanisms.

**Definition of Done (v1.0)**
- CLOSED / VERIFIED: ENERGY, T2, QUEUE, GRID-PHASE, P2P, LEGACY-DISK.
- SolarFlux: CLOSED / VERIFIED, or explicitly excluded and pinned.
- LEGACY-LIVE: fail-closed, documented.
- Manifest PASS, canary PASS, unpin policy formally documented, negative controls present.
- No known unclassified race in the audited scope.

**Canary telemetry per interval**
- Kernel: mode, `unauditedPaths`, `manifestEligible`, `mainAffinityViolation`.
- P(G), staging and T2 counters.
- Energy: contention / retries.
- P2P: reads / writers / retries / generation invalidations.
- SolarFlux: contention / retries.
- Server: TPS, tick ms, region TPS, watchdog, exceptions, crash reports, thread dumps on hang.
