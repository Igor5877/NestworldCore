package net.nestworld.region;

import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Connection/Teleport Burst Concurrency Control -- Step 4 (ТЗ, 2026-08-30), responding to the
 * n=225 confirmed-ceiling finding (project memory n225-confirmed-ceiling-2026-08-30.md): the
 * remaining burst ceiling is NOT RegionSplitManager, NOT an unbounded queue, NOT GC/heap -- it is
 * sustained Netty I/O thread oversubscription (40 "Netty Epoll Server IO" threads all RUNNABLE
 * vs 20 physical cores, oversubRatio ~2.4-2.55, sustained 50+ seconds) that leaves zero CPU slack
 * once the teleport burst's additional load lands on top.
 *
 * <p>This gates the EARLIEST possible point -- TCP channel accept, in {@code
 * ServerConnectionListener}'s {@code initChannel()} -- BEFORE any handshake/login packet is ever
 * processed, unlike {@link AdmissionController} (Step 2, gates LOGIN-&gt;SETUP, much later, after
 * the connection is already fully established and its Netty thread already busy with the login
 * handshake).
 *
 * <p><b>Hold, not reject</b>: unlike a naive "close excess connections" design, this PAUSES reads
 * on the channel ({@code channel.config().setAutoRead(false)}) instead of closing it -- Netty
 * stops even attempting to read from that socket, costing ~zero further CPU for that connection
 * until it's released, while the TCP connection itself stays alive (the client sees a slow-to-
 * respond server, not a rejected/reset connection -- important because neither a real Minecraft
 * client nor this project's test-bot harness implements automatic reconnect-on-failure; a hard
 * reject would simply lose that player, not queue them). {@link #onTick()} (main-thread, hooked
 * into {@code MinecraftServer.tickServer()}) drains the queue as budget frees up, calling {@code
 * setAutoRead(true)} to let each connection's handshake finally proceed.
 *
 * <p>Thread-safety note: unlike {@link AdmissionController}/{@link PairingAdmissionGate} (both
 * safely main-thread-only), {@code initChannel()} runs on WHICHEVER Netty event-loop thread owns
 * the newly-accepted channel -- genuinely concurrent, potentially many different threads at once.
 * This class is therefore built with real atomics/concurrent structures throughout, not the
 * plain-field shortcut those two use.
 *
 * <p>Budget is a time-window token bucket (not a tick-counter) specifically so it works correctly
 * regardless of which thread calls it and independent of how slow the main thread's own tick loop
 * gets under load -- a tick-counple-coupled design would either race or stall if read from
 * multiple Netty threads while the main thread itself is delayed.
 */
public final class ConnectionAdmissionGate {
    private ConnectionAdmissionGate() {}

    public static volatile boolean ENABLED = false;
    public static volatile int MAX_NEW_CONNECTIONS_PER_WINDOW = 8;
    private static final long WINDOW_NANOS = 50_000_000L; // ~1 tick at 20 TPS

    private static final AtomicLong windowStart = new AtomicLong(System.nanoTime());
    private static final AtomicInteger admittedThisWindow = new AtomicInteger();

    private static final ConcurrentLinkedQueue<Channel> waiting = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger queueDepth = new AtomicInteger();
    private static final AtomicInteger maxQueueDepthEver = new AtomicInteger();

    private static final LongAdder admittedImmediateTotal = new LongAdder();
    private static final LongAdder queuedTotal = new LongAdder();
    private static final LongAdder releasedFromQueueTotal = new LongAdder();
    private static final LongAdder droppedDeadInQueueTotal = new LongAdder();

    private static boolean tryConsumeSlot() {
        long now = System.nanoTime();
        long ws = windowStart.get();
        if (now - ws >= WINDOW_NANOS && windowStart.compareAndSet(ws, now)) {
            admittedThisWindow.set(0);
        }
        return admittedThisWindow.incrementAndGet() <= MAX_NEW_CONNECTIONS_PER_WINDOW;
    }

    /** Called from {@code ServerConnectionListener.initChannel()} right after the channel's
     *  pipeline is fully wired up (cheap, no data flows without a read) -- may run on any Netty
     *  event-loop thread. */
    public static void admitOrQueue(Channel ch) {
        if (!ENABLED) return;
        if (tryConsumeSlot()) {
            admittedImmediateTotal.increment();
            return;
        }
        ch.config().setAutoRead(false);
        queuedTotal.increment();
        int depth = queueDepth.incrementAndGet();
        maxQueueDepthEver.accumulateAndGet(depth, Math::max);
        waiting.add(ch);
    }

    /** Called once per server tick (main thread) -- drains the queue as budget frees up. */
    public static void onTick() {
        if (!ENABLED) return;
        Channel ch;
        while ((ch = waiting.peek()) != null) {
            if (!tryConsumeSlot()) break;
            waiting.poll();
            queueDepth.decrementAndGet();
            if (ch.isActive()) {
                ch.config().setAutoRead(true);
                releasedFromQueueTotal.increment();
            } else {
                droppedDeadInQueueTotal.increment();
            }
        }
    }

    public static void reset() {
        windowStart.set(System.nanoTime());
        admittedThisWindow.set(0);
        queueDepth.set(0);
        maxQueueDepthEver.set(0);
        admittedImmediateTotal.reset();
        queuedTotal.reset();
        releasedFromQueueTotal.reset();
        droppedDeadInQueueTotal.reset();
        waiting.clear();
    }

    public static String report() {
        return String.format(
                "NW connection-admission: enabled=%s maxPerWindow=%d(~%dms) | admittedImmediate=%,d "
                        + "queued=%,d releasedFromQueue=%,d droppedDeadInQueue=%,d | queueDepthNow=%d maxQueueDepth=%d",
                ENABLED, MAX_NEW_CONNECTIONS_PER_WINDOW, WINDOW_NANOS / 1_000_000,
                admittedImmediateTotal.sum(), queuedTotal.sum(), releasedFromQueueTotal.sum(),
                droppedDeadInQueueTotal.sum(), queueDepth.get(), maxQueueDepthEver.get());
    }
}
