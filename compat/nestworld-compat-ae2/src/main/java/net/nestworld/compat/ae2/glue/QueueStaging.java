package net.nestworld.compat.ae2.glue;

import appeng.util.ILevelRunnable;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.world.level.LevelAccessor;

/**
 * Producer→main handoff for AE2 TickHandler deferred work (AE2-COMPAT-QUEUE-001 §3, approved in
 * principle). Only off-main producers are staged; main keeps AE2's exact path. Work published before a
 * phase-drain linearization point (main's take at the head of the consumption phase) is eligible for
 * that phase; later work for the next one.
 */
public final class QueueStaging {
    static final Queue<ILevelRunnable> SERVER = new ConcurrentLinkedQueue<>();
    static final Map<LevelAccessor, Queue<ILevelRunnable>> LEVELS = new ConcurrentHashMap<>();

    /** True when the caller must stage instead of touching AE2's unsynchronized queues. */
    public static boolean offMain() {
        return net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() != null && !Ae2Compat.onMainThread();
    }

    public static void stage(LevelAccessor level, ILevelRunnable c) {
        if (level == null) {
            SERVER.add(c);
            Ae2Compat.COUNTERS.queueStagedServer.increment();
        } else {
            LEVELS.computeIfAbsent(level, k -> new ConcurrentLinkedQueue<>()).add(c);
            Ae2Compat.COUNTERS.queueStagedLevel.increment();
        }
    }

    /** Main: move staged server work into AE2's serverQueue (drain LP). */
    public static void drainServer(Queue<ILevelRunnable> into) {
        for (ILevelRunnable c; (c = SERVER.poll()) != null; ) into.add(c);
    }

    /** Main: move staged work of one level into AE2's callQueue, before AE2's remove/process/put. */
    public static void drainLevel(LevelAccessor level, Map<LevelAccessor, Queue<ILevelRunnable>> callQueue) {
        Queue<ILevelRunnable> q = LEVELS.get(level);
        if (q == null || q.isEmpty()) return;
        Queue<ILevelRunnable> target = callQueue.computeIfAbsent(level, k -> new java.util.ArrayDeque<>());
        for (ILevelRunnable c; (c = q.poll()) != null; ) target.add(c);
    }

    /** Main, level unload: AE2 drops that level's pending callables; staged ones are dropped too. */
    public static void dropLevel(LevelAccessor level) {
        Queue<ILevelRunnable> q = LEVELS.remove(level);
        if (q != null && !q.isEmpty()) Ae2Compat.COUNTERS.queueDroppedLevelUnload.add(q.size());
    }

    public static void clear() {
        SERVER.clear();
        LEVELS.clear();
    }

    private QueueStaging() {
    }
}
