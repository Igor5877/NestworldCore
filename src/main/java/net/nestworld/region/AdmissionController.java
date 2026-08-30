package net.nestworld.region;

import java.util.ArrayDeque;

/**
 * Connection Burst Admission Pipeline -- Step 2 (ТЗ, 2026-08-29), responding directly to Step 1's
 * measured finding (see project memory burst-admission-step1-attribution-2026-08-29.md): a burst
 * of simultaneous new connections saturates Netty to 40/40 RUNNABLE threads within ~1.6 seconds
 * and keeps it there for the ENTIRE burst-absorption window, while the actual per-player
 * synchronous setup cost stays cheap (~8ms avg) throughout -- i.e. the problem is BURST
 * CONCURRENCY, not per-player work. This class paces how many NEW players enter the expensive
 * setup pipeline per tick; it does not reduce total work, does not cap online count, and does not
 * touch anything about already-connected players.
 *
 * <p>Gates ONLY the transition from "login accepted" to {@code PlayerList.placeNewPlayer()} (see
 * the call site in {@code ServerLoginPacketListenerImpl.handleAcceptedLogin()}). Does NOT touch
 * connection accept (TCP/Netty level), does NOT touch teleportTo() or any other in-game player
 * action, does NOT change vanilla gameplay semantics for connected players -- a lone player
 * joining an idle server (empty queue, budget available) is always admitted the same tick with
 * zero added latency; only a genuine burst of simultaneous NEW connections gets paced.
 *
 * <p>Pipeline semantics per the user's explicit diagram (JOIN -&gt; LOGIN -&gt; READY -&gt;
 * ADMISSION QUEUE -&gt; SETUP -&gt; TELEPORT -&gt; ACTIVE): FIFO, FULLY PIPELINED -- as soon as
 * one admission is processed, the next queued arrival becomes eligible, up to
 * {@link #BUDGET_PER_TICK} admissions can complete in a SINGLE tick, and once a player IS
 * admitted it proceeds independently through the (already separately paced) chunk+pairing setup
 * via OutboundBatchQueue/ChunkOutboundQueue/PairingOutboundQueue -- this class only meters entry
 * into that pipeline, never blocks a player already inside it.
 *
 * <p>Main-thread-only by construction: {@code ServerLoginPacketListenerImpl.tick()}/{@code
 * handleAcceptedLogin()} are {@code TickablePacketListener} methods vanilla always invokes from
 * the server thread (packets are dequeued and handled on the main thread), and {@link #onTick()}
 * is called once per tick from {@code MinecraftServer.tickServer()}, also main-thread-only -- so
 * plain fields suffice, no atomics/locks needed (consistent with this session's established
 * "don't synchronize a single-threaded call site" discipline, e.g. the OutboundBatchQueue/
 * ChunkOutboundQueue/PairingOutboundQueue lean-rewrite lessons).
 */
public final class AdmissionController {
    private AdmissionController() {}

    public static volatile boolean ENABLED = false;
    public static volatile int BUDGET_PER_TICK = 8;

    public interface AdmissionCallback {
        void onAdmitted();
    }

    private static final class Pending {
        final AdmissionCallback cb;
        final long enqueuedAtTick;
        Pending(AdmissionCallback cb, long tick) { this.cb = cb; this.enqueuedAtTick = tick; }
    }

    private static final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private static long currentTick = 0;
    private static int admittedThisTick = 0;

    private static long enqueuedTotal = 0;
    private static long admittedTotal = 0;
    private static long immediateAdmitTotal = 0;
    private static int maxQueueDepthEver = 0;
    private static long waitTicksTotal = 0;
    private static long waitTicksMax = 0;

    /** Called once per server tick, BEFORE tickChildren() processes this tick's incoming login
     *  packets -- so any backlog from previous ticks gets first claim on this tick's budget, and
     *  only leftover budget can admit brand-new arrivals in the same tick (FIFO fairness). */
    public static void onTick() {
        currentTick++;
        admittedThisTick = 0;
        while (admittedThisTick < BUDGET_PER_TICK && !queue.isEmpty()) {
            Pending p = queue.poll();
            admittedThisTick++;
            admittedTotal++;
            long wait = currentTick - p.enqueuedAtTick;
            waitTicksTotal += wait;
            if (wait > waitTicksMax) waitTicksMax = wait;
            p.cb.onAdmitted();
        }
    }

    /** Called from the LOGIN-&gt;SETUP transition point. Returns true if the caller should
     *  proceed synchronously RIGHT NOW (admission disabled, or a budget slot is free and nothing
     *  is already backlogged). Returns false if {@code cb} was queued instead -- the caller must
     *  NOT proceed synchronously; {@code cb} fires later from {@link #onTick()}. */
    public static boolean tryAdmitOrQueue(AdmissionCallback cb) {
        if (!ENABLED) return true;
        if (queue.isEmpty() && admittedThisTick < BUDGET_PER_TICK) {
            admittedThisTick++;
            admittedTotal++;
            immediateAdmitTotal++;
            return true;
        }
        enqueuedTotal++;
        queue.add(new Pending(cb, currentTick));
        if (queue.size() > maxQueueDepthEver) maxQueueDepthEver = queue.size();
        return false;
    }

    public static void reset() {
        queue.clear();
        currentTick = 0;
        admittedThisTick = 0;
        enqueuedTotal = 0;
        admittedTotal = 0;
        immediateAdmitTotal = 0;
        maxQueueDepthEver = 0;
        waitTicksTotal = 0;
        waitTicksMax = 0;
    }

    public static String report() {
        double avgWaitTicks = admittedTotal > 0 ? (double) waitTicksTotal / admittedTotal : 0;
        return String.format(
                "NW admission: enabled=%s budgetPerTick=%d | enqueued=%,d admitted=%,d (immediate=%,d queued=%,d) "
                        + "| pendingNow=%,d maxQueueDepth=%,d | avgWaitTicks=%.2f maxWaitTicks=%,d",
                ENABLED, BUDGET_PER_TICK, enqueuedTotal, admittedTotal, immediateAdmitTotal,
                admittedTotal - immediateAdmitTotal, queue.size(), maxQueueDepthEver, avgWaitTicks, waitTicksMax);
    }
}
