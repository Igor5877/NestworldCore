package net.nestworld.compat;

import java.util.List;
import java.util.Map;

/** Small, game-independent operations used by targeted NestWorld coremods. */
public final class NestworldCoremodSupport {
    private NestworldCoremodSupport() {
    }

    /**
     * Restores a detached producer batch after its consumer failed. Values that arrived while
     * the detached batch was being consumed are appended after the older values, preserving
     * per-key order. The caller owns synchronization for both maps.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Map mergeDetachedBatch(Map detached, Map active) {
        for (Object rawEntry : active.entrySet()) {
            Map.Entry entry = (Map.Entry) rawEntry;
            Object key = entry.getKey();
            List newer = (List) entry.getValue();
            List older = (List) detached.get(key);
            if (older == null) {
                detached.put(key, newer);
            } else {
                older.addAll(newer);
            }
        }
        return detached;
    }

    /** Clears every live list in a mod-owned array while preserving the array's shape. */
    public static void clearLists(Object[] lists) {
        for (Object value : lists) {
            ((List<?>) value).clear();
        }
    }

    // ---- MEKANISM-REGISTRY-RACE-001 (Mekanism 10.4.16 NetworkAcceptorCache / TransmitterNetworkRegistry) ----
    // The transformed classes call these from synthetic synchronized methods: the caller holds the owning
    // object's monitor and only collection operations happen here (no world, capability or network calls).

    /** Accounting, off by default: -Dnestworld.mekRegistryAudit=true. */
    public static final boolean MEK_AUDIT = Boolean.getBoolean("nestworld.mekRegistryAudit");
    /** distinct (transmitter, side) insertions into a pending changedAcceptors generation */
    public static final java.util.concurrent.atomic.LongAdder MEK_PRODUCED = new java.util.concurrent.atomic.LongAdder();
    /** (transmitter, side) entries handed to a commit as a detached generation */
    public static final java.util.concurrent.atomic.LongAdder MEK_PUBLISHED = new java.util.concurrent.atomic.LongAdder();
    /** entries discarded by deregister (Mekanism's own clear() semantics) */
    public static final java.util.concurrent.atomic.LongAdder MEK_DROPPED = new java.util.concurrent.atomic.LongAdder();
    /** entries that already existed in the adopting cache (adoptAcceptors addAll returned false) */
    public static final java.util.concurrent.atomic.LongAdder MEK_MERGED_DUPLICATE = new java.util.concurrent.atomic.LongAdder();
    /** non-empty detached generations (acceptor caches and the changed-network set) */
    public static final java.util.concurrent.atomic.LongAdder MEK_GENERATIONS = new java.util.concurrent.atomic.LongAdder();

    /** Replacement for a pending map: a fresh map when it holds work, otherwise the same (empty) map. */
    @SuppressWarnings("rawtypes")
    public static Map mekReplacementMap(Map pending) {
        return pending.isEmpty() ? pending : new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap();
    }

    /** The detached generation, or an immutable empty map when nothing was swapped out. */
    @SuppressWarnings("rawtypes")
    public static Map mekDetachedMap(Map previous, Map replacement) {
        if (previous == replacement) return java.util.Collections.emptyMap();
        if (MEK_AUDIT) MEK_GENERATIONS.increment();
        return previous;
    }

    @SuppressWarnings("rawtypes")
    public static java.util.Set mekReplacementSet(java.util.Set pending) {
        return pending.isEmpty() ? pending : new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet();
    }

    @SuppressWarnings("rawtypes")
    public static java.util.Set mekDetachedSet(java.util.Set previous, java.util.Set replacement) {
        if (previous == replacement) return java.util.Collections.emptySet();
        if (MEK_AUDIT) MEK_GENERATIONS.increment();
        return previous;
    }

    /** Counts a pending-generation insertion; returns its argument (stack-neutral for the caller). */
    public static boolean mekProduced(boolean added) {
        if (MEK_AUDIT && added) MEK_PRODUCED.increment();
        return added;
    }

    /** Counts a generation handed to commit(); returns it unchanged. */
    @SuppressWarnings("rawtypes")
    public static Map mekPublished(Map detached) {
        if (MEK_AUDIT) MEK_PUBLISHED.add(sizeOfValues(detached));
        return detached;
    }

    /** Counts a generation discarded by deregister(). */
    @SuppressWarnings("rawtypes")
    public static void mekDropped(Map detached) {
        if (MEK_AUDIT) MEK_DROPPED.add(sizeOfValues(detached));
    }

    /**
     * adoptAcceptors' original second loop: merge another cache's detached pending sides into this cache's
     * pending map (containsKey → addAll, else put). Caller holds this cache's monitor only.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void mekMergeAcceptorChanges(Map into, Map from) {
        for (Object raw : from.entrySet()) {
            Map.Entry entry = (Map.Entry) raw;
            java.util.Set sides = (java.util.Set) entry.getValue();
            java.util.Set existing = (java.util.Set) into.get(entry.getKey());
            if (existing == null) {
                into.put(entry.getKey(), sides);
            } else if (MEK_AUDIT) {
                for (Object side : sides) if (!existing.add(side)) MEK_MERGED_DUPLICATE.increment();
            } else {
                existing.addAll(sides);
            }
        }
    }

    public static String mekAuditSummary() {
        return "mekRegistryAudit=" + MEK_AUDIT + " produced=" + MEK_PRODUCED.sum() + " published=" + MEK_PUBLISHED.sum()
                + " dropped=" + MEK_DROPPED.sum() + " mergedDuplicate=" + MEK_MERGED_DUPLICATE.sum()
                + " generations=" + MEK_GENERATIONS.sum();
    }

    @SuppressWarnings("rawtypes")
    private static long sizeOfValues(Map detached) {
        long n = 0;
        for (Object v : detached.values()) n += ((java.util.Collection) v).size();
        return n;
    }
}
