package net.nestworld.region;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-region entity-tick fairness diagnostics (2026-08-30) -- measurement-only, per the user's
 * explicit "instrument first, don't touch the budget yet" direction following a live chicken-
 * farm point-hotspot report (see project memory: a single ~3100-entity region, round-robin
 * budgeted at {@link NestworldTuning#REGION_ENTITY_BUDGET_NANOS} (default 40ms), only ticks
 * ~500-544 entities per pass -- {@code RegionThread.tickEntities()}'s own {@code lastDeferredCount}
 * already showed THAT most entities are deferred, but not HOW LONG any individual entity
 * actually waits between real updates, which is what the reported visible lag/stutter is a
 * direct function of).
 *
 * <p>Single-writer discipline: an instance lives on its {@link WorldRegion} and is only ever
 * touched by the ONE {@link RegionThread} that owns that region (the same thread that runs
 * {@code tickEntities()}) -- a plain (non-concurrent) {@link HashMap} is correct and cheap, no
 * synchronization needed. This is deliberately the OPPOSITE shape of the FTB Quests/Draconic
 * Evolution races found earlier this session (a mod's SHARED static structure touched from
 * multiple threads) -- this structure is per-region and single-threaded by construction.
 * {@link #maxAgeNanos} and the percentile readers are O(n)/O(n log n) and are only ever called
 * on-demand from the RCON report path, never from the hot tick loop.
 */
public final class EntityFairnessMetrics {
    private final Map<UUID, Long> lastTickNanos = new HashMap<>();

    private static final int RING_CAPACITY = 4096;
    private final long[] intervalRing = new long[RING_CAPACITY];
    private int ringIdx = 0;
    private int ringFill = 0;

    /** Call once per entity actually ticked this round, right after ticking it. */
    public void recordTick(UUID uuid, long nowNanos) {
        Long prev = lastTickNanos.put(uuid, nowNanos);
        if (prev != null) {
            intervalRing[ringIdx] = nowNanos - prev;
            ringIdx = (ringIdx + 1) % RING_CAPACITY;
            if (ringFill < RING_CAPACITY) ringFill++;
        }
    }

    /** Drop tracking for an entity that left this region (removed, transferred, died) -- call
     *  from every {@code region.removeEntity(uuid)} call site in {@code tickEntities()}. Kept
     *  as a cheap best-effort hint for the common case; NOT relied upon for correctness -- see
     *  {@link #maxAgeNanos} javadoc for why. */
    public void forget(UUID uuid) {
        lastTickNanos.remove(uuid);
    }

    /** Worst-off single entity right now: max time since its last real tick, among entities
     *  CURRENTLY owned (per {@code currentlyOwned}). 2026-08-30 correctness fix: entities leave
     *  a region via several paths beyond tickEntities()'s own cleanup (BoundaryEntityTransfer
     *  border-crossing, RegionSplitManager split/merge) that this class was never wired into --
     *  {@link #forget} alone left long-stale orphaned entries in the map for entities that had
     *  transferred elsewhere, silently inflating this reading to minutes (caught live: a
     *  3-entity region with 0 deferred reported a 193s max age). Filtering the scan to the
     *  live-owned set (and opportunistically pruning anything else found stale) is robust
     *  regardless of which departure path was taken -- no need to enumerate every removal call
     *  site across the codebase, avoiding the exact class of "did I cover every path" risk this
     *  session's Draconic Evolution/FTB Quests investigations were about in the first place. */
    public long maxAgeNanos(long nowNanos, java.util.Collection<UUID> currentlyOwned) {
        long max = 0;
        if (lastTickNanos.size() > currentlyOwned.size() * 2 + 16) {
            // Cheap heuristic: only pay for a full prune pass when the map has clearly
            // accumulated stale entries well beyond the live set's size.
            lastTickNanos.keySet().retainAll(currentlyOwned instanceof java.util.Set
                    ? (java.util.Set<UUID>) currentlyOwned : new java.util.HashSet<>(currentlyOwned));
        }
        for (UUID uuid : currentlyOwned) {
            Long t = lastTickNanos.get(uuid);
            if (t == null) continue; // never ticked yet since last reset -- not "starved"
            long age = nowNanos - t;
            if (age > max) max = age;
        }
        return max;
    }

    public int trackedCount() {
        return lastTickNanos.size();
    }

    private long[] sortedIntervals() {
        long[] copy = Arrays.copyOf(intervalRing, Math.min(ringFill, RING_CAPACITY));
        Arrays.sort(copy);
        return copy;
    }

    /** p in [0,1]. Distribution of REAL observed gaps between consecutive ticks of the same
     *  entity -- this is the direct, measured answer to "how often does any given entity's
     *  physics/AI actually get to run", not an estimate derived from ticked/owned ratios. */
    public long percentileIntervalNanos(double p) {
        long[] sorted = sortedIntervals();
        if (sorted.length == 0) return 0;
        int idx = Math.min(sorted.length - 1, (int) (sorted.length * p));
        return sorted[idx];
    }

    public long maxIntervalNanos() {
        long[] sorted = sortedIntervals();
        return sorted.length == 0 ? 0 : sorted[sorted.length - 1];
    }

    public void reset() {
        lastTickNanos.clear();
        ringIdx = 0;
        ringFill = 0;
    }
}
