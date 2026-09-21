import codechicken.lib.packet.PacketCustom;
import com.brandon3055.draconicevolution.network.CrystalUpdateBatcher$BatchedCrystalUpdate;
import com.buuz135.industrial.utils.explosion.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.p3pp3rf1y.sophisticatedcore.util.RecipeHelper$RecipeCache;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Actual transformed server methods, with minimal stubs at external game/network boundaries. */
public final class ConcurrencyRegression {
    static Class<?> define(ClassNode node) {
        byte[] bytes = CoremodRegression.bytes(node);
        return new ClassLoader(ConcurrencyRegression.class.getClassLoader()) {
            Class<?> loadTarget() { return defineClass(node.name.replace('/', '.'), bytes, 0, bytes.length); }
        }.loadTarget();
    }
    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static void invoke(Method method, Object... args) {
        try { method.invoke(null, args); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static void batch(Path root, Path mods) throws Exception {
        ClassNode original = CoremodRegression.load(mods, "Draconic-Evolution-1.20.1-3.1.2.621-universal.jar",
                "com/brandon3055/draconicevolution/network/CrystalUpdateBatcher");
        var fn = CoremodRegression.transformer(root, "mod_compound_races.js");
        ClassNode patched = CoremodRegression.transform(fn, original);
        // Client-only decoding is unrelated and requires an entire client game.
        patched.methods.removeIf(m -> m.name.equals("handleBatchedData"));
        Class<?> target = define(patched);
        Method enqueue = target.getMethod("queData", CrystalUpdateBatcher$BatchedCrystalUpdate.class, ServerPlayer.class);
        Method tick = target.getMethod("tickEnd");
        ServerPlayer player = new ServerPlayer();
        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            PacketCustom.RECEIVED.clear();
            PacketCustom.SENT_ORDER.clear();
            CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
            PacketCustom.beforeSend = () -> { sending.countDown(); await(release); };
            invoke(enqueue, new CrystalUpdateBatcher$BatchedCrystalUpdate(-1), player);
            Future<?> first = executor.submit(() -> invoke(tick));
            await(sending);
            // Enqueue must finish while packet I/O is paused. It belongs to the next batch.
            try {
                executor.submit(() -> invoke(enqueue, new CrystalUpdateBatcher$BatchedCrystalUpdate(-2), player))
                        .get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
            PacketCustom.beforeSend = () -> {};
            CoremodRegression.check(PacketCustom.RECEIVED.equals(Set.of(-1)), "New update leaked into detached batch");
            invoke(tick);
            CoremodRegression.check(PacketCustom.RECEIVED.equals(Set.of(-1, -2)), "Update lost during send/clear");

            // A failed send must restore the detached batch and merge an update
            // that arrived while packet I/O was in progress.
            PacketCustom.RECEIVED.clear();
            PacketCustom.SENT_ORDER.clear();
            CountDownLatch failingSend = new CountDownLatch(1), failRelease = new CountDownLatch(1);
            PacketCustom.beforeSend = () -> { failingSend.countDown(); await(failRelease); };
            PacketCustom.FAIL_NEXT.set(true);
            invoke(enqueue, new CrystalUpdateBatcher$BatchedCrystalUpdate(-3), player);
            Future<?> failed = executor.submit(() -> invoke(tick));
            await(failingSend);
            invoke(enqueue, new CrystalUpdateBatcher$BatchedCrystalUpdate(-4), player);
            failRelease.countDown();
            try {
                failed.get(10, TimeUnit.SECONDS);
                throw new AssertionError("Injected send failure did not propagate");
            } catch (ExecutionException expected) {
                // The transformed method restores then rethrows the original failure.
            }
            PacketCustom.beforeSend = () -> {};
            invoke(tick);
            CoremodRegression.check(PacketCustom.RECEIVED.equals(Set.of(-3, -4)),
                    "Failed detached batch was not restored in order");
            CoremodRegression.check(PacketCustom.SENT_ORDER.equals(List.of(-3, -4)),
                    "Restored batch order changed");

            PacketCustom.RECEIVED.clear();
            PacketCustom.SENT_ORDER.clear();
            List<Future<?>> producers = new ArrayList<>();
            for (int p = 0; p < 4; p++) {
                int base = p * 5000;
                producers.add(executor.submit(() -> {
                    for (int i = 0; i < 5000; i++) invoke(enqueue, new CrystalUpdateBatcher$BatchedCrystalUpdate(base + i), player);
                }));
            }
            Future<?> consumer = executor.submit(() -> {
                do { invoke(tick); } while (producers.stream().anyMatch(f -> !f.isDone()));
                invoke(tick);
            });
            for (Future<?> producer : producers) producer.get(20, TimeUnit.SECONDS);
            consumer.get(20, TimeUnit.SECONDS);
            CoremodRegression.check(PacketCustom.RECEIVED.size() == 20000, "Concurrent updates missing");
        } finally {
            PacketCustom.beforeSend = () -> {};
            executor.shutdownNow();
        }
        System.out.println("PASS Draconic: detached send + 20,000 concurrent updates, no loss/duplicates/count mismatch");
    }
    @SuppressWarnings("unchecked")
    static void explosions(Path root, Path mods) throws Exception {
        var original = CoremodRegression.load(mods, "industrial-foregoing-1.20.1-3.5.22.jar",
                "com/buuz135/industrial/utils/explosion/ExplosionTickHandler");
        Class<?> target = define(CoremodRegression.transform(CoremodRegression.transformer(root,
                "static_collection_race_transformer.js"), original));
        List<ProcessExplosion> processes = (List<ProcessExplosion>) target.getField("processExplosionList").get(null);
        List<ExplosionHelper.RemovalProcess> removals = (List<ExplosionHelper.RemovalProcess>) target.getField("removalProcessList").get(null);
        Method tick = target.getMethod("serverTick", TickEvent.ServerTickEvent.class);
        var start = new TickEvent.ServerTickEvent(TickEvent.Phase.START);
        ProcessExplosion active = new ProcessExplosion();
        var removal = new ExplosionHelper.RemovalProcess();
        processes.add(active); removals.add(removal);
        invoke(tick, start); invoke(tick, start);
        CoremodRegression.check(active.ticks == 2 && removal.ticks == 2, "Active processes were drained prematurely");
        active.dead = true;
        invoke(tick, start);
        CoremodRegression.check(processes.isEmpty() && removals.contains(removal), "Dead process removal changed active list");
        int before = removal.ticks;
        invoke(tick, new TickEvent.ServerTickEvent(TickEvent.Phase.END));
        CoremodRegression.check(before == removal.ticks, "Processes ticked in END phase");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> producer = executor.submit(() -> {
                for (int i = 0; i < 2000; i++) processes.add(new ProcessExplosion());
            });
            do { invoke(tick, start); } while (!producer.isDone());
            producer.get(10, TimeUnit.SECONDS);
            invoke(tick, start);
            CoremodRegression.check(processes.size() == 2000 && processes.stream().allMatch(p -> p.ticks > 0),
                    "Concurrent process registration lost or skipped");
        } finally { executor.shutdownNow(); }
        System.out.println("PASS Industrial Foregoing: persistent active processes, dead removal, concurrent registration");
    }
    static void recipes(Path root, Path mods) throws Exception {
        var original = CoremodRegression.load(mods, "sophisticatedcore-1.20.1-1.3.40.1925.jar",
                "net/p3pp3rf1y/sophisticatedcore/util/RecipeHelper");
        var patched = CoremodRegression.transform(CoremodRegression.transformer(root, "mod_compound_races.js"), original);
        // Keep the actual cache access/invalidation paths; recipe evaluation is a
        // caller-supplied function, so the test needs no Minecraft recipe registry.
        Set<String> keep = Set.of("getFromCache", "getCache", "setLevel", "lambda$onDataPackSync$3",
                "lambda$onRecipesUpdated$2", "nestworld$clearCache");
        patched.methods.removeIf(m -> !keep.contains(m.name));
        Class<?> target = define(patched);
        Method get = target.getDeclaredMethod("getFromCache", Function.class, Object.class);
        Method clear = target.getDeclaredMethod("lambda$onDataPackSync$3", RecipeHelper$RecipeCache.class);
        get.setAccessible(true); clear.setAccessible(true);
        RecipeHelper$RecipeCache cache = new RecipeHelper$RecipeCache();
        for (String field : List.of("clientCache", "serverCache")) {
            Field f = target.getDeclaredField(field); f.setAccessible(true); f.set(null, cache);
        }
        cache.recipeChangeListeners.listener = () -> CoremodRegression.check(!Thread.holdsLock(target), "Listener runs under cache lock");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch computing = new CountDownLatch(1), finish = new CountDownLatch(1), clearing = new CountDownLatch(1);
        try {
            Future<?> lookup = executor.submit(() -> invoke(get, (Function<Object,Object>) c -> {
                CoremodRegression.check(Thread.holdsLock(target), "Cache operation has no monitor");
                computing.countDown(); await(finish); cache.values.put(1, 1); return 1;
            }, null));
            await(computing);
            Future<?> reload = executor.submit(() -> { clearing.countDown(); invoke(clear, cache); });
            await(clearing);
            // Explicitly verify the invalidation helper uses the same JVM monitor.
            Method helper = target.getDeclaredMethod("nestworld$clearCache", RecipeHelper$RecipeCache.class);
            CoremodRegression.check(Modifier.isSynchronized(helper.getModifiers()), "Invalidation is not synchronized");
            finish.countDown();
            lookup.get(10, TimeUnit.SECONDS); reload.get(10, TimeUnit.SECONDS);
            CoremodRegression.check(cache.values.isEmpty(), "Pre-reload computation was published after invalidation");
        } finally { finish.countDown(); executor.shutdownNow(); }
        System.out.println("PASS Sophisticated Core: cache computation/reload exclusion; listeners outside monitor");
    }
    public static void run(Path root, Path mods) throws Exception {
        batch(root, mods);
        explosions(root, mods);
        recipes(root, mods);
        FluxRegression.run(root, mods);
        MekanismPathfinderRegression.run(root, mods);
        CrystalRegistryRegression.run(root, mods);
    }
}
