# PERF-1A W2 — single unsplittable hotspot (negative control) — spec DRAFT

**Status: APPROVED by the owner (2026-10-05).** The reading rule and the per-cell harness change
are approved. Next: implementation → pilot → W2 freeze → official. W1 Revision 1 is
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
