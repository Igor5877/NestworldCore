package net.nestworld.compat.ae2.glue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixture-only: run a task on the thread that ticks a given host entity, i.e. the free-running
 * RegionThread owning it. Entity ticks of a free-running region are not coordinated by main (block
 * entity ticks are: NestWorld's BE phase is dispatched and awaited by main), so the task can run while
 * main is held inside a fixture hook.
 */
public final class RegionTasks {
    private static final Map<UUID, Runnable> TASKS = new ConcurrentHashMap<>();

    public static void submit(UUID host, Runnable task) {
        TASKS.put(host, task);
    }

    public static void runFor(UUID host) {
        if (TASKS.isEmpty()) return;
        Runnable r = TASKS.remove(host);
        if (r != null) r.run();
    }

    private RegionTasks() {
    }
}
