package net.nestworld.compat.ae2.glue;

import appeng.me.cluster.IAECluster;
import appeng.me.cluster.MBCalculator;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * AE2-DEMAIN-001 B1 — the MULTIBLOCK lane (owner decisions D1–D5, 2026-11-09): AE2's JVM-global
 * {@code MBCalculator.modificationInProgress} no longer relies on MAIN for serialization.
 *
 * <p><b>D1 token.</b> One logical, non-blocking serialization token; no AE2 thread, MAIN is not the lane executor. The
 * token decides who may touch MBCalculator's global state, not which thread does world access. A structural operation
 * (CALC = {@code MBCalculator.calculateMultiblock}, TEARDOWN = the D4a cluster-state transition, ADOPT = an unknown
 * caller's {@code setModificationInProgress(c) … (null)} window) runs only while its thread owns the token:
 * <ul>
 * <li>free → acquire, run inline in the caller's (current, host) domain, release;</li>
 * <li>owned by this thread → run inline (AE2's own single-chain re-entrancy, e.g. a nested destroy inside a calc);</li>
 * <li>busy → register a per-target request and return (all entry points are void ASYNC_SIDE_EFFECT, audit §4.4).</li>
 * </ul>
 * <p><b>D3 bounded pending.</b> Per target (MBCalculator target block entity; TEARDOWN: cluster identity) at most
 * {@code 1 active + 1 pending}: a request while the target's ticket is still queued is COALESCED (the queued calc
 * recomputes from the world as it is when it runs); a request while the target is RUNNING creates exactly one fresh
 * rerun (dirtyRerun, {@code dirtyGeneration}). The FIFO therefore holds each target at most once — no unbounded queue.
 * <p><b>Handoff.</b> On release the head ticket is RESERVED (token held by the ticket, not by a thread) and enqueued into
 * its target's CURRENT block-entity execution domain (Core {@code enqueueForBlockEntityDomain} via
 * {@link BeDomainReady#enqueueTo}, re-resolved at execution). There it converts the reservation into ownership and
 * revalidates: CALC — still loaded, same block-entity identity at its position, not removed, still needs a recalc (no
 * calc of that target started after the request); stale → counted, released. TEARDOWN tickets are never stale
 * (exactly-once D4a transition); a Core terminal runs them on the server thread (D4a fallback, counted).
 * <p><b>Operation identity (D4).</b> Ticket id = operationId; a ticket runs only by CAS RESERVED → RUNNING, so a repeated
 * delivery of the same operationId is a counted no-op ({@code repeatIgnored}), never a second run and never a wait.
 * There is exactly one lane token in B1; acquiring "another" token while holding this one is impossible by construction
 * (and B3 keyed lanes must keep that rule: one token per thread, no nesting).
 * <p><b>D2 cross-domain multiblock (transitional).</b> Before any mutation (after AE2's bounds scan, at
 * {@code checkMultiblockScale}) every position of the structure's bounds must resolve to the current thread's domain.
 * Otherwise the positions are position-pinned to MAIN through Core CORE-PINZONE-001 (reflectively; ref-counted) and
 * the calc is aborted before mutating and re-queued — it then runs on MAIN with all members local
 * ({@code crossDomainMultiblockPinned}). Pins are released by the D4a teardown of a cluster with those bounds. Core
 * without position pins: counted ({@code straddleUnfenced}) and AE2's behaviour kept (degrade, never hide).
 * <p><b>Never a ThreadLocal of the global flag</b>: AE2's static is untouched; only the token holder writes it.
 * Unknown writers ({@link #set}: addon cluster destroys, {@code QuantumCluster.onUnload}) ADOPT the token when free;
 * when busy they are counted ({@code flagWithoutToken}) and run unchanged — the verifier, not a hiding layer.
 * <p><b>Lock order.</b> The token is try-only: no thread ever blocks waiting for it, so it adds no wait-for edge and
 * cannot close a cycle in {@code nw$life → P(G) → T0 → domains}. A token holder may block on nw$life / P / T0 exactly as
 * AE2 code on MAIN does today; none of their holders waits for the token (they enqueue). {@link #LOCK} is a leaf
 * monitor: held only for O(1) bookkeeping, never across AE2, Core, logging or another lock. The token is never held
 * across a safe point (release happens in {@code finally} of the same operation; enqueue never waits).
 * <p>{@link #ENABLED} off ({@code -Dnestworld.compatAe2.mbLaneOff=true}) = negative control: AE2 unchanged.
 */
public final class MbLane {
    public static volatile boolean ENABLED = !Boolean.getBoolean("nestworld.compatAe2.mbLaneOff");

    /** Fixture-only probe points inside AE2's calc (null in production): "scale" (bounds found, before any set/mutation), "status". */
    public interface Hook {
        void at(String point, BlockEntity target);
    }

    public static volatile Hook hook;

    enum Kind { CALC, TEARDOWN }

    static final int QUEUED = 1, RESERVED = 2, RUNNING = 3, DONE = 4;

    static final class Ticket {
        final long id;
        final Kind kind;
        final Object key;
        final BlockEntity host;
        final ServerLevel level;
        final BlockPos pos;
        final MBCalculator<?, ?> calc;
        final BlockPos loc;
        final Runnable body;
        final String label;
        final long reqSeq;
        final long enqNanos = System.nanoTime();
        int state = QUEUED;

        Ticket(long id, Kind kind, Object key, BlockEntity host, ServerLevel level, MBCalculator<?, ?> calc, BlockPos loc,
               Runnable body, String label, long reqSeq) {
            this.id = id;
            this.kind = kind;
            this.key = key;
            this.host = host;
            this.level = level;
            this.pos = host.getBlockPos();
            this.calc = calc;
            this.loc = loc;
            this.body = body;
            this.label = label;
            this.reqSeq = reqSeq;
        }
    }

    /** Thrown (no stack) by the D2 fence before AE2 mutates anything; caught by the lane, never escapes to AE2 callers. */
    static final class MbStraddle extends RuntimeException {
        MbStraddle() {
            super("nestworld mb straddle", null, false, false);
        }
    }

    private static final MbStraddle STRADDLE = new MbStraddle();

    // ---- lane state (guarded by LOCK) ----
    private static final Object LOCK = new Object();
    private static Thread owner;
    private static int depth;
    private static Ticket ownerTicket;      // ticket being executed by owner (null: inline / adopt)
    private static Object runningKey;       // target / cluster of the outermost op
    private static boolean adopted;         // owner holds the token through an unknown setter window
    private static Ticket reserved;
    private static Ticket laneInvoke;       // owner's own calculateMultiblock call for ownerTicket (not a new request)
    private static final ArrayDeque<Ticket> FIFO = new ArrayDeque<>();
    private static final Map<Object, Ticket> PENDING = new IdentityHashMap<>();
    private static final Map<BlockEntity, Long> LAST_START = new WeakHashMap<>();
    private static long seq, ids;
    private static final long[] LAT = new long[8192];
    private static int latN;
    private static long latMax;
    private static final Map<String, long[]> STRADDLE_PINS = new HashMap<>();

    public static final LongAdder requested = new LongAdder(), inline = new LongAdder(), queued = new LongAdder(),
            coalesced = new LongAdder(), dirtyRerun = new LongAdder(), ticketRan = new LongAdder(), completedInline = new LongAdder(),
            stale = new LongAdder(), staleSatisfied = new LongAdder(), dropped = new LongAdder(), failed = new LongAdder(),
            reentrant = new LongAdder(), repeatIgnored = new LongAdder(), offDomain = new LongAdder(), enqueueFailed = new LongAdder(),
            tdRequested = new LongAdder(), tdInline = new LongAdder(), tdQueued = new LongAdder(), tdRan = new LongAdder(),
            tdFallback = new LongAdder(), tdReentrant = new LongAdder(), adopt = new LongAdder(), flagWithoutToken = new LongAdder(),
            foreignClearWithoutToken = new LongAdder(), straddleDetected = new LongAdder(), crossDomainMultiblockPinned = new LongAdder(),
            straddlePinnedPositions = new LongAdder(), straddleReleasedPositions = new LongAdder(), straddleUnfenced = new LongAdder(),
            straddleUnresolved = new LongAdder(), straddleRequeued = new LongAdder(), bodyConcurrent = new LongAdder(),
            releaseAnomaly = new LongAdder();
    static volatile String firstFlagWithoutToken = "-", firstFailure = "-";
    private static volatile Thread bodyThread;
    /** owner-only: the last body run under this token straddled (aborted before mutation, re-queued). */
    private static boolean straddled;
    static final java.util.concurrent.ConcurrentHashMap<String, LongAdder> BY_CALCULATOR = new java.util.concurrent.ConcurrentHashMap<>();

    private MbLane() {
    }

    private static BlockEntity hostOf(MBCalculator<?, ?> calc) {
        Object t = ((net.nestworld.compat.ae2.mixin.MBCalculatorAccessor) (Object) calc).nw$target();
        return t instanceof BlockEntity be ? be : null;
    }

    // ------------------------------------------------------------------ CALC entry (MBCalculator.calculateMultiblock)

    /** Shared semantic entrypoint for every MBCalculator subclass (AE2 + addons): {@code body} = AE2's original method. */
    public static void calc(MBCalculator<?, ?> calc, ServerLevel level, BlockPos loc, Runnable body) {
        BlockEntity host = hostOf(calc);
        BY_CALCULATOR.computeIfAbsent(calc.getClass().getName() + " / " + (host == null ? "?" : BeDomainReady.typeOf(host)),
                k -> new LongAdder()).increment();
        if (!ENABLED || host == null || level == null) {
            body.run();
            return;
        }
        Thread me = Thread.currentThread();
        boolean acquired = false;
        synchronized (LOCK) {
            if (owner == me) {
                if (laneInvoke != null && laneInvoke.calc == calc) {
                    laneInvoke = null;                       // the ticket's own execution
                } else {
                    reentrant.increment();
                }
            } else if (owner == null && reserved == null && FIFO.isEmpty()) {
                owner = me;
                depth = 1;
                runningKey = host;
                acquired = true;
                requested.increment();
                inline.increment();
                LAST_START.put(host, ++seq);
            } else {
                requested.increment();
                register(Kind.CALC, host, host, level, calc, loc, null, "calc");
                return;
            }
        }
        boolean ok = false;
        try {
            ok = runBody(calc, level, loc, host, body);
        } finally {
            if (acquired) {
                if (ok) completedInline.increment();
                release();
            }
        }
    }

    /** AE2 body under the token; a D2 straddle abort becomes a re-queued ticket (never reaches AE2's caller). */
    private static boolean runBody(MBCalculator<?, ?> calc, ServerLevel level, BlockPos loc, BlockEntity host, Runnable body) {
        Thread me = Thread.currentThread();
        Thread prev = bodyThread;
        if (prev != null && prev != me) bodyConcurrent.increment();      // must stay 0: verifier of the token itself
        bodyThread = me;
        try {
            body.run();
            return true;
        } catch (MbStraddle s) {
            straddleRequeued.increment();
            synchronized (LOCK) {
                straddled = true;
                register(Kind.CALC, host, host, level, calc, loc, null, "calc.straddle");
            }
            return false;
        } catch (Throwable t) {
            failed.increment();
            if ("-".equals(firstFailure)) firstFailure = t + " on " + me.getName();
            throw t;
        } finally {
            bodyThread = prev == me ? me : null;
        }
    }

    /** Under LOCK. Per-target pending with max 1 active + 1 pending (D3). */
    private static void register(Kind kind, Object key, BlockEntity host, ServerLevel level, MBCalculator<?, ?> calc, BlockPos loc,
                                 Runnable body, String label) {
        Ticket p = PENDING.get(key);
        if (p != null && (p.state == QUEUED || p.state == RESERVED)) {
            if (kind == Kind.CALC) coalesced.increment();
            else repeatIgnored.increment();               // TEARDOWN: same cluster already pending (D4a claim makes this 0)
            return;
        }
        if (runningKey == key) dirtyRerun.increment();
        Ticket t = new Ticket(++ids, kind, key, host, level, calc, loc, body, label, ++seq);
        PENDING.put(key, t);
        FIFO.add(t);
        if (kind == Kind.CALC) queued.increment();
        else tdQueued.increment();
    }

    private static void release() {
        Ticket next = null;
        synchronized (LOCK) {
            if (owner != Thread.currentThread()) {
                releaseAnomaly.increment();
                return;
            }
            if (--depth > 0) return;
            owner = null;
            ownerTicket = null;
            runningKey = null;
            adopted = false;
            laneInvoke = null;
            next = FIFO.poll();
            if (next != null) {
                next.state = RESERVED;
                reserved = next;
            }
        }
        if (next != null) dispatch(next);
    }

    private static void dispatch(Ticket t) {
        if (!BeDomainReady.primitive()) {
            runTicket(t);                                  // no Core primitive: no region threads, run here
            return;
        }
        try {
            BeDomainReady.enqueueTo(t.level, t.pos, BeDomainReady.typeOf(t.host), () -> runTicket(t), kind -> terminal(t, kind));
        } catch (Throwable e) {
            enqueueFailed.increment();
            terminal(t, "enqueue failed: " + e);
        }
    }

    /** Core terminal (server thread) or enqueue failure: CALC dropped (counted), TEARDOWN run on the server thread. */
    private static void terminal(Ticket t, Object kind) {
        if (t.kind == Kind.TEARDOWN) {
            tdFallback.increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] MB lane teardown {} at {} fell back to the server thread: {}",
                    t.label, t.pos.toShortString(), kind);
            MinecraftServer server = t.level.getServer();
            if (server != null) server.execute(() -> runTicket(t));
            else runTicket(t);
            return;
        }
        Ticket next = null;
        synchronized (LOCK) {
            if (reserved != t || t.state != RESERVED) {
                repeatIgnored.increment();
                return;
            }
            reserved = null;
            t.state = DONE;
            if (PENDING.get(t.key) == t) PENDING.remove(t.key);
            dropped.increment();
            if (owner == null) {
                next = FIFO.poll();
                if (next != null) {
                    next.state = RESERVED;
                    reserved = next;
                }
            }
        }
        Ae2Compat.LOG.warn("[nestworld-compat-ae2] MB lane calc at {} dropped by the BE domain: {}", t.pos.toShortString(), kind);
        if (next != null) dispatch(next);
    }

    /** Executed in the target's current domain (or the fallback): reservation → ownership, revalidate, run, release. */
    private static void runTicket(Ticket t) {
        Thread me = Thread.currentThread();
        synchronized (LOCK) {
            if (reserved != t || t.state != RESERVED || owner != null) {
                repeatIgnored.increment();
                return;
            }
            reserved = null;
            owner = me;
            depth = 1;
            ownerTicket = t;
            runningKey = t.key;
            t.state = RUNNING;
            straddled = false;
            if (PENDING.get(t.key) == t) PENDING.remove(t.key);
            long l = System.nanoTime() - t.enqNanos;
            LAT[latN++ & (LAT.length - 1)] = l;
            if (l > latMax) latMax = l;
        }
        try {
            if (t.kind == Kind.TEARDOWN) {
                t.body.run();
                tdRan.increment();
                return;
            }
            if (!valid(t)) {
                stale.increment();
                return;
            }
            synchronized (LOCK) {
                Long last = LAST_START.get(t.host);
                if (last != null && last > t.reqSeq) {
                    stale.increment();                   // a calc of this target started after the request
                    staleSatisfied.increment();
                    return;
                }
                LAST_START.put(t.host, ++seq);
                laneInvoke = t;
            }
            if (BeDomainReady.primitive() && !BeDomainReady.currentThreadIn(t.level, t.pos, BeDomainReady.typeOf(t.host))) {
                offDomain.increment();
            }
            t.calc.calculateMultiblock(t.level, t.loc);    // re-enters calc(): laneInvoke marks it as this ticket
            boolean s;
            synchronized (LOCK) {
                s = straddled;
            }
            if (!s) ticketRan.increment();
        } catch (Throwable e) {
            if (t.kind == Kind.CALC) failed.increment();
            if ("-".equals(firstFailure)) firstFailure = e + " on " + me.getName() + " (" + t.label + ")";
            Ae2Compat.LOG.error("[nestworld-compat-ae2] MB lane {} at {} failed on {}", t.label, t.pos.toShortString(), me.getName(), e);
        } finally {
            synchronized (LOCK) {
                t.state = DONE;
                if (laneInvoke == t) laneInvoke = null;
            }
            release();
        }
    }

    /** Owner thread, outside LOCK: still loaded (BE-ticking), same block-entity identity at its position, not removed. */
    private static boolean valid(Ticket t) {
        BlockEntity be = t.host;
        if (be.isRemoved() || (be instanceof NwAeBlockEntity life && life.nw$removed())) return false;
        LevelChunk chunk = t.level.getChunkSource().getChunkNow(t.pos.getX() >> 4, t.pos.getZ() >> 4);
        if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)) return false;
        return chunk.getBlockEntity(t.pos, LevelChunk.EntityCreationType.CHECK) == be;
    }

    // ------------------------------------------------------------------ TEARDOWN (D4a transition, D4)

    /** D4a's cluster-state transition (already claimed, in the anchor's domain): under the same token. */
    public static void teardown(Object cluster, BlockEntity anchor, String label, Runnable transition) {
        if (!ENABLED || anchor == null || !(anchor.getLevel() instanceof ServerLevel level)) {
            transition.run();
            return;
        }
        Thread me = Thread.currentThread();
        boolean acquired = false;
        synchronized (LOCK) {
            tdRequested.increment();
            if (owner == me) {
                tdReentrant.increment();
            } else if (owner == null && reserved == null && FIFO.isEmpty()) {
                owner = me;
                depth = 1;
                runningKey = cluster;
                acquired = true;
                tdInline.increment();
            } else {
                register(Kind.TEARDOWN, cluster, anchor, level, null, null, () -> {
                    transition.run();
                    releasePins(cluster, level);
                }, label);
                return;
            }
        }
        try {
            transition.run();
            releasePins(cluster, level);
        } finally {
            if (acquired) release();
        }
    }

    // ------------------------------------------------------------------ unknown setter callers (adoption / verifier)

    /** Wraps every {@code MBCalculator.setModificationInProgress(c)}; {@code op} = AE2's original setter. */
    public static void set(Object cluster, Runnable op) {
        if (!ENABLED) {
            op.run();
            return;
        }
        Thread me = Thread.currentThread();
        if (cluster != null) {
            boolean adoptedNow = false;
            synchronized (LOCK) {
                if (owner == me) {
                    // inside an owned operation: pass
                } else if (owner == null && reserved == null && FIFO.isEmpty()) {
                    owner = me;
                    depth = 1;
                    adopted = true;
                    runningKey = cluster;
                    adoptedNow = true;
                    adopt.increment();
                } else {
                    flagWithoutToken.increment();
                    if ("-".equals(firstFlagWithoutToken)) {
                        firstFlagWithoutToken = "flag set without token by " + me.getName() + " while owner=" + owner + " reserved=" + reserved;
                    }
                }
            }
            try {
                op.run();
            } catch (Throwable t) {
                if (adoptedNow) release();
                throw t;
            }
            return;
        }
        boolean releaseAdopt = false;
        synchronized (LOCK) {
            if (owner == me) {
                releaseAdopt = adopted && depth == 1 && ownerTicket == null;
            } else if (owner != null) {
                foreignClearWithoutToken.increment();
            }
        }
        op.run();
        if (releaseAdopt) release();
    }

    // ------------------------------------------------------------------ D2 fence (checkMultiblockScale wrap)

    /** Called after AE2's bounds scan, before any set/mutation, only when the scale check passed. May throw MbStraddle. */
    public static void fence(MBCalculator<?, ?> calc, ServerLevel level, BlockPos min, BlockPos max) {
        Hook h = hook;
        if (h != null) h.at("scale", hostOf(calc));
        if (!ENABLED || !BeDomainReady.primitive() || level == null) return;
        synchronized (LOCK) {
            if (owner != Thread.currentThread()) return;  // not under the lane (cannot happen when ENABLED)
        }
        if (!straddles(level, min, max)) return;
        straddleDetected.increment();
        if (!NeighborAffinity.available()) {
            straddleUnfenced.increment();                 // Core without position pins: AE2 behaviour kept, counted
            return;
        }
        min = min.immutable();
        max = max.immutable();
        String key = key(level, min, max);
        long[] positions;
        synchronized (LOCK) {
            if (STRADDLE_PINS.containsKey(key)) {
                straddleUnresolved.increment();           // already pinned yet still straddling: AE2 behaviour, counted
                return;
            }
            positions = box(min, max);
            STRADDLE_PINS.put(key, positions);
        }
        if (!NeighborAffinity.pinRaw(level, positions)) {
            synchronized (LOCK) {
                STRADDLE_PINS.remove(key);
            }
            straddleUnfenced.increment();
            return;
        }
        crossDomainMultiblockPinned.increment();
        straddlePinnedPositions.add(positions.length);
        Ae2Compat.LOG.info("[nestworld-compat-ae2] MB lane: multiblock {}..{} spans execution domains; {} positions pinned to MAIN",
                min.toShortString(), max.toShortString(), positions.length);
        throw STRADDLE;
    }

    public static void statusHook(MBCalculator<?, ?> calc) {
        Hook h = hook;
        if (h != null) h.at("status", hostOf(calc));
    }

    private static boolean straddles(ServerLevel level, BlockPos min, BlockPos max) {
        Map<Long, Boolean> cache = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockEntity be = level.getBlockEntity(p);
            String type = be == null ? null : BeDomainReady.typeOf(be);
            long ck = (((long) (p.getX() >> 4)) << 32) ^ ((p.getZ() >> 4) & 0xffffffffL) ^ ((long) (type == null ? 0 : type.hashCode()) * 31L);
            Boolean in = cache.get(ck);
            if (in == null) {
                in = BeDomainReady.currentThreadIn(level, p, type);
                cache.put(ck, in);
            }
            if (!in) return true;
        }
        return false;
    }

    private static void releasePins(Object cluster, ServerLevel level) {
        if (!(cluster instanceof IAECluster c) || STRADDLE_PINS.isEmpty()) return;
        BlockPos min = c.getBoundsMin(), max = c.getBoundsMax();
        if (min == null || max == null) return;
        long[] positions;
        synchronized (LOCK) {
            positions = STRADDLE_PINS.remove(key(level, min, max));
        }
        if (positions == null) return;
        NeighborAffinity.releaseRaw(level, positions);
        straddleReleasedPositions.add(positions.length);
    }

    private static String key(ServerLevel level, BlockPos min, BlockPos max) {
        return level.dimension().location() + "|" + min.asLong() + "|" + max.asLong();
    }

    private static long[] box(BlockPos min, BlockPos max) {
        int n = (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        long[] out = new long[n];
        int i = 0;
        for (BlockPos p : BlockPos.betweenClosed(min, max)) out[i++] = p.asLong();
        return out;
    }

    // ------------------------------------------------------------------ control + metrics

    /** Fixture-only runtime switch; refused while the lane is not quiescent. */
    public static String setEnabled(boolean on) {
        synchronized (LOCK) {
            if (owner != null || reserved != null || !FIFO.isEmpty()) return "mblane busy (owner=" + owner + " queued=" + FIFO.size() + ")";
            ENABLED = on;
        }
        return "mblane enabled=" + on;
    }

    public static String calculators() {
        StringBuilder b = new StringBuilder("mblane calculateMultiblock entries by calculator class / target BE type:");
        new java.util.TreeMap<>(BY_CALCULATOR).forEach((k, v) -> b.append("\n ").append(k).append(" = ").append(v.sum()));
        return b.toString();
    }

    public static long outstanding() {
        synchronized (LOCK) {
            return FIFO.size() + (reserved != null ? 1 : 0);
        }
    }

    public static String summary() {
        long[] lat;
        long max, n;
        int queuedNow;
        String own;
        synchronized (LOCK) {
            n = Math.min(latN, LAT.length);
            lat = Arrays.copyOf(LAT, (int) n);
            max = latMax;
            queuedNow = FIFO.size();
            own = owner == null ? (reserved == null ? "free" : "reserved#" + reserved.id) : owner.getName();
        }
        Arrays.sort(lat);
        long req = requested.sum(), comp = completedInline.sum() + ticketRan.sum(), out = outstanding();
        long bal = req - comp - coalesced.sum() - stale.sum() - dropped.sum() - failed.sum() - out;
        return "mblane{enabled=" + ENABLED + " token=" + own + " queuedNow=" + queuedNow + " outstanding=" + out
                + " requested=" + req + " completed=" + comp + " (inline=" + completedInline.sum() + " ticket=" + ticketRan.sum()
                + ") coalesced=" + coalesced.sum() + " stale=" + stale.sum() + " (satisfied=" + staleSatisfied.sum() + ") dropped="
                + dropped.sum() + " failed=" + failed.sum() + " balance=" + bal + " queued=" + queued.sum() + " dirtyRerun=" + dirtyRerun.sum()
                + " reentrant=" + reentrant.sum() + " repeatIgnored=" + repeatIgnored.sum() + " offDomain=" + offDomain.sum()
                + " enqueueFailed=" + enqueueFailed.sum() + " bodyConcurrent=" + bodyConcurrent.sum() + " releaseAnomaly=" + releaseAnomaly.sum()
                + " | latency n=" + n + " p50=" + us(pct(lat, .50)) + "us p95=" + us(pct(lat, .95)) + "us p99=" + us(pct(lat, .99))
                + "us max=" + us(max) + "us | teardown requested=" + tdRequested.sum() + " inline=" + tdInline.sum() + " queued="
                + tdQueued.sum() + " ran=" + tdRan.sum() + " reentrant=" + tdReentrant.sum() + " fallback=" + tdFallback.sum()
                + " | setter adopt=" + adopt.sum() + " flagWithoutToken=" + flagWithoutToken.sum() + " foreignClearWithoutToken="
                + foreignClearWithoutToken.sum() + " | straddle detected=" + straddleDetected.sum() + " crossDomainMultiblockPinned="
                + crossDomainMultiblockPinned.sum() + " pinnedPositions=" + straddlePinnedPositions.sum() + " releasedPositions="
                + straddleReleasedPositions.sum() + " requeued=" + straddleRequeued.sum() + " unfenced=" + straddleUnfenced.sum()
                + " unresolved=" + straddleUnresolved.sum() + " | firstFailure=" + firstFailure + " firstFlagWithoutToken="
                + firstFlagWithoutToken + "}";
    }

    private static long pct(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * (sorted.length - 1)))];
    }

    private static String us(long nanos) {
        return String.valueOf(nanos / 1000);
    }
}
