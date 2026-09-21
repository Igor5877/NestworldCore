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
}
