package net.nestworld.compat.ae2.glue;

import appeng.api.networking.IGridNode;
import appeng.me.Grid;
import appeng.parts.storagebus.StorageBusPart;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AE2-GRID-DOMAIN-MVP-001 storage-bus option (1) — owner-approved EXPLICIT EXCEPTION (2026-11-12): the AE2 worker may
 * SIMULATE/MODULATE a Sophisticated Storage handler mounted by a storage bus of the hot grid directly, because EVERY
 * other accessor of that same handler enters through the same serialisation contract P(G) — P(G) is the ownership lock
 * of this shared inventory interaction:
 * <ul>
 * <li>worker in its phase: already holds P(G) (reentrant, no cost);</li>
 * <li>MAIN acting for the parked worker of G (MainService delegation): passes (the worker holds P(G) and is parked);</li>
 * <li>MAIN otherwise: {@link MainService#lockOnMain} (services parked-worker calls while waiting — no MAIN⇄worker cycle);</li>
 * <li>REGION / any other thread: P(G) {@code lock()} — holds no AE2 lock at that point (lock order P → T0 → domains);</li>
 * <li>inside a kernel section (T0 held): tryLock only, a miss is counted (gate: 0) — never T0 → P(G) waiting.</li>
 * </ul>
 * Scope: only targets of storage buses of grids with ≥ {@link #MIN_NODES} nodes (the hot grid #1). Waits measured by
 * thread kind (p50/p95/p99/max).
 */
public final class ChestGuard {
    private ChestGuard() {}

    static final int MIN_NODES = Integer.getInteger("nestworld.compatAe2.chestGuardMinNodes", 1000);
    /** guarded object (Sophisticated wrapper / fluid tank / interface inventory) → node source (StorageBusPart or
     *  InterfaceLogic): resolved at access time, so a grid change re-keys automatically. */
    private static final Map<Object, Object> GUARDED = new ConcurrentHashMap<>();

    private static IGridNode nodeOf(Object source) {
        if (source instanceof StorageBusPart sb) return sb.getGridNode();
        if (source instanceof appeng.helpers.InterfaceLogic l) return l.getActionableNode();
        return null;
    }
    static final LongAdder delegated = new LongAdder(), kernelMiss = new LongAdder(), locked = new LongAdder(), registered = new LongAdder();
    static final Map<String, Waits> WAITS = new ConcurrentHashMap<>();

    static final class Waits {
        private final long[] ring = new long[20_000];
        private long count, total, max;
        synchronized void add(long n) { ring[(int) (count++ % ring.length)] = n; total += n; if (n > max) max = n; }
        synchronized String describe() {
            int n = (int) Math.min(count, ring.length);
            if (n == 0) return "n=0";
            long[] s = java.util.Arrays.copyOf(ring, n);
            java.util.Arrays.sort(s);
            return String.format("n=%d total=%.1fms p50=%.3f p95=%.3f p99=%.3f max=%.3f ms", count, total / 1e6, s[n / 2] / 1e6,
                    s[Math.min(n - 1, (int) (n * 0.95))] / 1e6, s[Math.min(n - 1, (int) (n * 0.99))] / 1e6, max / 1e6);
        }
    }

    /** MAIN (ownership computation): guard this handler for this storage bus. */
    static void register(Object wrapper, Object nodeSource) {
        if (GUARDED.put(wrapper, nodeSource) != nodeSource) registered.increment();
    }

    static boolean isGuarded(Object wrapper) { return wrapper != null && GUARDED.containsKey(wrapper); }

    /** Any thread, before a handler access: returns the lock to release afterwards, or null (nothing taken). */
    public static ReentrantLock enter(Object wrapper) {
        if (wrapper == null || GUARDED.isEmpty()) return null;
        Object src = GUARDED.get(wrapper);
        if (src == null) return null;
        IGridNode node = nodeOf(src);
        if (node == null || !(node.getGrid() instanceof Grid g)) return null;
        return enterGrid(g, src);
    }

    /** Same contract for grid-internal ingress handlers (Mekanism chemical P2P input) of hot grids only. */
    public static ReentrantLock enterNode(IGridNode node) {
        if (node == null || !(node.getGrid() instanceof Grid g) || g.size() < MIN_NODES) return null;
        p2p.increment();
        return enterGrid(g, node.getOwner());
    }

    static final LongAdder p2p = new LongAdder();

    private static ReentrantLock enterGrid(Grid g, Object what) {
        ReentrantLock p = ((NwGrid) g).nw$p();
        if (p.isHeldByCurrentThread()) return null;
        if (MainService.delegatedFor(g)) { delegated.increment(); return null; }
        long t0 = System.nanoTime();
        String kind;
        if (PLocks.holdsAny()) {
            // MVP-001 cross-grid rule: holding another grid's P ⇒ tryLock only; miss ⇒ neutral answer this tick
            if (!p.tryLock()) {
                PLocks.miss(g, what);
                return MISS;
            }
            PLocks.crossGridTryLockHit.increment();
            kind = "cross-grid-tryLock";
        } else if (Ae2Compat.KERNEL.inSection()) {
            if (!p.tryLock()) { kernelMiss.increment(); return null; }
            kind = "kernel-tryLock";
        } else if (Ae2Compat.onMainThread()) {
            MainService.lockOnMain(g, p);
            kind = "MAIN";
        } else {
            p.lock();
            kind = GridWorker.isWorkerThread() ? "AE2-worker(outside phase)" : Thread.currentThread().getName().contains("Region") ? "REGION" : "other";
        }
        locked.increment();
        PLocks.acquired();
        long t1 = System.nanoTime();
        WAITS.computeIfAbsent(kind, k -> new Waits()).add(t1 - t0);
        if (t1 - t0 > 20_000_000L && LONG_WAITS.size() < 50) {     // attribution of long tails (owner: explain the max once)
            String at = what instanceof appeng.parts.AEBasePart part && part.getHost() != null
                    ? part.getHost().getBlockEntity().getBlockPos().toShortString() + " " + part.getClass().getSimpleName() : String.valueOf(what);
            var srv = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            LONG_WAITS.add(String.format("%.1fms %s thread=%s at=%s tick=%d gridNodes=%d mainService=%s", (t1 - t0) / 1e6, kind,
                    Thread.currentThread().getName(), at, srv == null ? -1 : srv.getTickCount(), g.size(), MainService.status()));
        }
        HELD.set(new Object[] { kind, t1 });
        return p;
    }

    private static final ThreadLocal<Object[]> HELD = new ThreadLocal<>();

    /** Returned by {@link #enter} on a cross-grid tryLock miss: the caller answers neutrally (no partial mutation). */
    public static final ReentrantLock MISS = new ReentrantLock();

    /** For mutators that cannot answer neutrally (set / NBT / resize): a cross-grid miss retries with a 2 ms bounded
     *  tryLock (never an unbounded wait while holding another P — no cycle); still missing ⇒ the mutation runs unguarded
     *  and is counted (`crossGridUnguardedMutation`, gate: 0). */
    public static ReentrantLock enterMutation(Object key) {
        ReentrantLock p = enter(key);
        if (p != MISS) return p;
        Object src = GUARDED.get(key);
        var node = src == null ? null : nodeOf(src);
        if (node != null && node.getGrid() instanceof Grid g) {
            ReentrantLock q = ((NwGrid) g).nw$p();
            try {
                if (q.tryLock(2, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    PLocks.acquired();
                    HELD.set(new Object[] { "cross-grid-bounded-mutation", System.nanoTime() });
                    return q;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        PLocks.crossGridBlockingMutation.increment();   // reported as crossGridUnguardedMutation
        return null;
    }

    public static void exit(ReentrantLock p) {
        if (p == null || p == MISS) return;
        PLocks.released();
        Object[] h = HELD.get();
        if (h != null && p.getHoldCount() == 1) {   // outermost section of this thread: hold time under P(G)
            HOLDS.computeIfAbsent((String) h[0], k -> new Waits()).add(System.nanoTime() - (long) h[1]);
            HELD.remove();
        }
        p.unlock();
    }

    static final Map<String, Waits> HOLDS = new ConcurrentHashMap<>();
    static final java.util.List<String> LONG_WAITS = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    static String status() {
        StringBuilder sb = new StringBuilder("chestGuard{guarded=" + GUARDED.size() + " registered=" + registered.sum() + " locked="
                + locked.sum() + " delegated=" + delegated.sum() + " p2pEntries=" + p2p.sum() + " kernelMiss(must be 0)=" + kernelMiss.sum() + "} P(G) wait by thread:");
        WAITS.forEach((k, v) -> sb.append("\n    wait ").append(k).append(" ").append(v.describe()));
        HOLDS.forEach((k, v) -> sb.append("\n    hold ").append(k).append(" ").append(v.describe()));
        synchronized (LONG_WAITS) { for (String l : LONG_WAITS) sb.append("\n    long wait ").append(l); }
        return sb.toString();
    }
}
