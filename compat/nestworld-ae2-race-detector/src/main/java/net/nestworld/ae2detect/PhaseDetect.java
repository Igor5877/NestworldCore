package net.nestworld.ae2detect;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Observation-only timeline for P-T1 (AE2-CROSS-REGION-TOPOLOGY-001): do these execution windows overlap in time?
 *   MAIN_BAND_BE   : NestworldDimensionRegion.tickBlockEntityList on the server thread (border band / unowned BEs)
 *   REGION_BE      : the same method on a region thread (interior BE work round)
 *   REGION_ENTITY  : RegionThread.tickEntities (free-running entity tick; entities may write blocks)
 *   AE2_READY      : AE2 TickHandler.readyBlockEntities (server thread, onReady of new AE2 block entities)
 * An overlap event = a window starts while a window of the other kind is active on ANOTHER thread.
 */
public final class PhaseDetect {
    public enum W { MAIN_BAND_BE, REGION_BE, REGION_ENTITY, AE2_READY }

    static final Map<W, Set<Thread>> ACTIVE = new ConcurrentHashMap<>();
    static final Map<W, LongAdder> COUNT = new ConcurrentHashMap<>();
    static final Map<String, LongAdder> OVERLAP = new ConcurrentHashMap<>();

    static {
        for (W w : W.values()) {
            ACTIVE.put(w, ConcurrentHashMap.newKeySet());
            COUNT.put(w, new LongAdder());
        }
    }

    private PhaseDetect() {}

    public static void enter(W w) {
        Thread me = Thread.currentThread();
        COUNT.get(w).increment();
        for (W o : W.values()) {
            if (o == w) continue;
            for (Thread t : ACTIVE.get(o)) {
                if (t != me) {
                    String k = w.ordinal() < o.ordinal() ? w + "~" + o : o + "~" + w;
                    OVERLAP.computeIfAbsent(k, x -> new LongAdder()).increment();
                    break;
                }
            }
        }
        ACTIVE.get(w).add(me);
    }

    public static void exit(W w) {
        ACTIVE.get(w).remove(Thread.currentThread());
    }

    public static W beWindow() {
        return MekDetect.isMain(Thread.currentThread()) ? W.MAIN_BAND_BE : W.REGION_BE;
    }

    public static String summary() {
        StringBuilder b = new StringBuilder("phase windows:");
        for (W w : W.values()) b.append(' ').append(w).append('=').append(COUNT.get(w).sum());
        b.append("\noverlap events:");
        new java.util.TreeMap<>(OVERLAP).forEach((k, v) -> b.append("\n ").append(k).append(" = ").append(v.sum()));
        return b.toString();
    }
}
