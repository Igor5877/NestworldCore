package net.nestworld.region;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Connection/Teleport Burst Attribution (ТЗ, 2026-08-29) -- Step 1 of the "burst admission
 * pipeline" milestone, per the full-stack sweep's finding (project memory
 * fullstack-sweep-2026-08-29.md) that n=200's failure is a CONNECTION/SETUP BURST problem (main
 * thread starved by a spike of ~40
 * simultaneously-RUNNABLE Netty I/O threads vs 20 physical cores), not a steady-state simulation
 * cost problem. MEASUREMENT ONLY -- no admission control, no blocking, no budget yet (explicit
 * instruction: static admission budget is a SEPARATE, later step, only after this data exists).
 *
 * <p>Lifecycle counters map onto the actual join pipeline, one increment per player per phase:
 * TCP accept ({@code ServerConnectionListener}) -&gt; login started ({@code
 * ServerLoginPacketListenerImpl.handleHello}) -&gt; player object created ({@code
 * PlayerList.placeNewPlayer} entry) -&gt; synchronous chunk-tracking/pairing setup call ({@code
 * ServerLevel.addNewPlayer}, timed) -&gt; teleport ({@code ServerPlayer.teleportTo}, the same
 * overload the RCON {@code /tp} command used by every scaling test in this session routes
 * through).
 *
 * <p>{@code threadCensus()} is deliberately NOT sampled per-tick -- {@code
 * Thread.getAllStackTraces()} walks every JVM thread and is too expensive to call every tick; it's
 * exposed as an on-demand RCON snapshot instead, matching how the full-stack sweep's crash report
 * was manually read (this just automates that same reading without needing a crash to trigger it).
 */
public final class BurstAdmissionMetrics {
    private BurstAdmissionMetrics() {}

    private static final LongAdder connectionsAccepted = new LongAdder();
    private static final LongAdder loginStarted = new LongAdder();
    private static final LongAdder playersCreated = new LongAdder();
    private static final LongAdder playersTeleported = new LongAdder();

    private static final LongAdder setupCallCount = new LongAdder();
    private static final LongAdder setupCallTotalNanos = new LongAdder();
    private static final AtomicLong setupCallMaxNanos = new AtomicLong();

    public static void recordConnectionAccepted() { connectionsAccepted.increment(); }
    public static void recordLoginStarted() { loginStarted.increment(); }
    public static void recordPlayerCreated() { playersCreated.increment(); }
    public static void recordPlayerTeleported() { playersTeleported.increment(); }

    /** Called around {@code ServerLevel.addNewPlayer()} in {@code PlayerList.placeNewPlayer()} --
     *  the synchronous call that kicks off initial chunk tracking + entity pairing for a newly
     *  joined player. This IS the "heavy setup pipeline" step 8's admission controller will
     *  eventually gate; today it runs fully synchronously and unbounded for every joining player. */
    public static void recordSetupCall(long elapsedNanos) {
        setupCallCount.increment();
        setupCallTotalNanos.add(elapsedNanos);
        setupCallMaxNanos.accumulateAndGet(elapsedNanos, Math::max);
    }

    public static String threadCensus() {
        int nettyThreads = 0, nettyRunnable = 0;
        int regionThreads = 0, regionRunnable = 0;
        int mainRunnable = 0;
        int otherThreads = 0, otherRunnable = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            boolean runnable = t.getState() == Thread.State.RUNNABLE;
            if (name.startsWith("Netty Epoll Server IO") || name.startsWith("Netty Server IO")) {
                nettyThreads++;
                if (runnable) nettyRunnable++;
            } else if (name.startsWith("NestWorld-Region-")) {
                regionThreads++;
                if (runnable) regionRunnable++;
            } else if (name.equals("Server thread")) {
                if (runnable) mainRunnable = 1;
            } else {
                otherThreads++;
                if (runnable) otherRunnable++;
            }
        }
        int cores = Runtime.getRuntime().availableProcessors();
        int totalRunnable = nettyRunnable + regionRunnable + mainRunnable + otherRunnable;
        return String.format(
                "NW thread-census: availableProcessors=%d | netty=%d(runnable=%d) region=%d(runnable=%d) "
                        + "main(runnable=%d) other=%d(runnable=%d) | totalRunnable=%d oversubscriptionRatio=%.2f",
                cores, nettyThreads, nettyRunnable, regionThreads, regionRunnable, mainRunnable,
                otherThreads, otherRunnable, totalRunnable, cores > 0 ? (double) totalRunnable / cores : 0);
    }

    public static void reset() {
        connectionsAccepted.reset();
        loginStarted.reset();
        playersCreated.reset();
        playersTeleported.reset();
        setupCallCount.reset();
        setupCallTotalNanos.reset();
        setupCallMaxNanos.set(0);
    }

    public static String report() {
        long sc = setupCallCount.sum();
        double avgSetupMs = sc == 0 ? 0 : (setupCallTotalNanos.sum() / 1_000_000.0) / sc;
        double maxSetupMs = setupCallMaxNanos.get() / 1_000_000.0;
        return String.format(
                "NW burst-admission: connectionsAccepted=%,d loginStarted=%,d playersCreated=%,d "
                        + "playersTeleported=%,d | chunkTrackingSetupCalls=%,d avgSetupMs=%.3f maxSetupMs=%.3f",
                connectionsAccepted.sum(), loginStarted.sum(), playersCreated.sum(), playersTeleported.sum(),
                sc, avgSetupMs, maxSetupMs);
    }
}
