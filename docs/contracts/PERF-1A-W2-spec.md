# PERF-1A W2 — single unsplittable hotspot (negative control) — spec DRAFT

**Status (2026-10-05): implemented (`d18a4c2b3`); the pilot meets the freeze criterion; the W2
FREEZE is pending owner approval.** The spec, reading rule and per-cell harness change were
approved by the owner earlier the same day. W1 Revision 1 is
CLOSED as PASS for N = 1 / 2 / 4 / 8 (see `PERF-1A-spec.md`); this is the next step under
PERF-1 TZ §13.

## Question
If the work is concentrated in one ownership domain that cannot be split, does adding CPUs leave
throughput flat? If W2 scaled like W1, the benchmark would be painting scaling through its method.

| Workload | Expectation | Result |
|---|---|---|
| W1 (independent regions) | scaling | measured 8.41× on 8 cores |
| W2 (one unsplittable hotspot) | saturation | to be measured |

W2 is a negative control, not a performance target. If it scales unexpectedly, the execution path
must be explained; it does not count as success.

## Design
**Unsplittable by construction**, with two independent reasons:
1. **Chunk granularity.** All W2 load sits in **one chunk column (16 × 16 blocks)**. Region cuts
   are whole chunks, so no cut can separate it.
2. **Core's own point-hotspot rule.** Automatic split refuses any cut whose smaller side carries
   < 10% of the load (`RegionSplitManager.MIN_SPLIT_SEPARATION_SHARE`). With all load in one
   column, every cut puts 100% on one side.

**Hotspot geometry:**
- A tower of sealed compartments in that one chunk.
- Per layer, 8 × 8 one-block compartments (odd offsets 1…15), with walls on even offsets and the
  outer wall at offset 16.
- Each layer is a floor plus 2 high of air (3 blocks), stacked from the surface up for K layers.
- One cow with AI per compartment, tagged.
- AI and pathfinding run as in W1, and no mob can leave its compartment.

**Topology:** identical to W1. The same 16-region layout is built the same way: a marker per cell,
gap-marker splits, `maxRegions = 16`, pinned manual children. The hotspot sits in cell 0's region;
regions 1–15 are empty. This keeps everything except the load distribution equal to W1:
- harness, CPUs, JVM flags and config;
- warmup criterion, window and checks.

**M:** K = 120 layers → 120 × 64 = **7 680 cows**.
- Single-region throughput is ≲ 33k ticks/s, so TPS ≈ 4 at every N, far below the 19 cap.
- 7 680 < 2 304 × 16, so W2 is not comparable to W1 in absolute numbers; only the shape of S(N) is
  compared.

**Construction safeguards** (Revision 1 standard):
- the tower is built only after `execute if loaded` passes;
- `prepare` verifies the exact stone count of the tower with a masked clone;
- at the end of each window, all 7 680 cows must be inside the tower bounds;
- in every sample, the hotspot region must own and tick all of them.

**Matrix:** N = 1, 2, 4, 8, ×3 VALID each. Same order rules, CPUs, housekeeping and interference
gates as W1.

## Reported, not targeted
T(N), S(N), E(N), MSPT, utilisation of the benchmark CPUs and of the hotspot region thread, main
thread, GC, JIT, and JFR monitor blocking (TickAttribution included).

**Expected shape** (hypothesis, not a pass threshold):
- S(N) stays close to 1. A small gain, well under 2×, is plausible because main, GC and other
  threads move off the hotspot core as N grows.
- The hotspot region thread stays near one full core, and the other cores sit mostly idle.

**Reading rule (owner-approved):**

| Result | Reading |
|---|---|
| S(8) ≤ 1.5 | negative control PASS |
| 1.5 < S(8) ≤ 2.5 | investigate the source of the gain before accepting |
| S(8) > 2.5 | the negative control fails, so the method or Core execution path must be explained |

## Harness impact
The frozen harness assumes every cell holds the same number of cows: the containment, per-region
ownership, wait-for-load and layout checks all use one `cows_per_cell`. W2 needs those checks
driven by a **per-cell expected count** from `cells.json`. This changes the harness, so W2 gets
its own revision, pilot and freeze.
- The change must leave W1 behaviour byte-for-byte equivalent: W1's per-cell list is
  [2304] × 16.
- W1 results stay tied to `cc0a19510`.
- The W2 pilot (N = 1, 2 ×3, plus one N = 8 headroom run) precedes the W2 freeze.

## Out of scope
N = 12 / 16 (moved to PERF-1A-EXTENDED), PERF-FIX-001, PERF-1B.

## Pilot (2026-10-05) — HARNESS VALIDATION ONLY
| Item | Value |
|---|---|
| Implementation | `d18a4c2b3`. W1 equivalence verified: helper outputs equal the old logic, and all 12 official W1 runs still validate with unchanged T and CV |
| Snapshot | `e7858bac`: 2 160 / 2 160 wall commands executed; 120 / 120 layer boxes verified (88 391 stone); 0 entities |
| CPUs | as W1 (benchmark on 2… in core order, housekeeping on 1) |

| Run | Verdict | ticks/s | TPS | benchmark CPU | hotspot region thread | main | other | CPU per 1 M ticks |
|---|---|---|---|---|---|---|---|---|
| N1-r1 | VALID | 20 988 | 2.73 | 0.99 | 0.77 | 0.09 | 0.12 | 47.3 s |
| N1-r2 | VALID | 21 256 | 2.76 | 0.99 | 0.77 | 0.09 | 0.12 | 46.7 s |
| N1-r3 | VALID | 21 768 | 2.83 | 0.99 | 0.77 | 0.08 | 0.12 | 45.6 s |
| N2-r1 | VALID | 30 107 | 3.92 | 1.15 | 0.95 | 0.08 | 0.10 | 38.0 s |
| N2-r2 | VALID | 30 437 | 3.96 | 1.15 | 0.95 | 0.08 | 0.10 | 37.7 s |
| N2-r3 | VALID | 29 598 | 3.85 | 1.15 | 0.95 | 0.08 | 0.10 | 39.0 s |
| N8-r1 | VOID (siblings 22–27 at 3.3–4.4%) | 30 860 | 4.01 | 1.17 | 0.96 | 0.08 | 0.11 | 37.9 s |

Summary:
- **N=1:** T = 21 337, CV 1.5%.
- **N=2:** T = 30 047, CV 1.1%, S(2) = 1.408.
- Every run: containment held (all 7 680 cows in the tower), deferred = 0, primary ≈ secondary.

**Observations (pilot, not evidence):**
- **Saturation is visible.** The hotspot region thread caps at about one core (0.95–0.96), and the
  benchmark CPUs are 1.15–1.17 used whether 2 or 8 are given. The VOID N=8 run reached the same
  throughput as N=2.
- **The 1 → 2 gain (≈ 1.41×) has two measured components:**
  - at N=1 the hotspot thread shares its core with main, GC and other threads (0.77 of the core),
    against 0.95 at N=2;
  - CPU per 1 M ticks falls from about 47 s to 38 s.
- **Close to the reading-rule boundary.** Both effects are complete by N=2, so the expected
  official S(8) is about 1.4–1.5. A value in 1.5–2.5 would trigger "investigate", and this
  attribution already covers it.

**Freeze criterion met** (N = 1, 2 ×3, all VALID, CV ≤ 5%, no cpuset or affinity violation).
W2 freeze at `d18a4c2b3`, snapshot `e7858bac`, is proposed. The official W2 matrix N = 1 / 2 / 4 / 8
needs CPUs 4–9 and siblings 22–27 quiet again: the host showed 2–4% background there during this
pilot.
