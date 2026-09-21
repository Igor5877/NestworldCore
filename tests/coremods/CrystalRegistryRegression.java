import com.brandon3055.draconicevolution.network.CrystalUpdateBatcher;
import net.minecraft.core.BlockPos;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class CrystalRegistryRegression {
    private static final String TILE =
            "com/brandon3055/draconicevolution/blocks/energynet/tileentity/TileCrystalBase";

    private static ClassNode fixture(String name) throws Exception {
        try (InputStream in = CrystalRegistryRegression.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            if (in == null) throw new AssertionError("Missing fixture " + name);
            return CoremodRegression.read(in.readAllBytes());
        }
    }

    private static void call(Method method, Object target) {
        try {
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void run(Path root, Path mods) throws Exception {
        var transformer = CoremodRegression.transformer(root, "mod_compound_races.js");

        // Verify the real supported batcher replaces only the ID registry's
        // allocation. The detached batch queue intentionally remains a HashMap.
        ClassNode realBatch = CoremodRegression.transform(transformer, CoremodRegression.load(mods,
                "Draconic-Evolution-1.20.1-3.1.2.621-universal.jar",
                "com/brandon3055/draconicevolution/network/CrystalUpdateBatcher"));
        int concurrentAllocations = 0;
        for (var instruction : CoremodRegression.method(realBatch, "<clinit>").instructions) {
            if (instruction instanceof TypeInsnNode type && instruction.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/util/concurrent/ConcurrentHashMap")) {
                concurrentAllocations++;
            }
        }
        CoremodRegression.check(concurrentAllocations == 1,
                "Draconic ID registry allocation was not replaced exactly once");

        ClassNode patched = CoremodRegression.transform(transformer, fixture(TILE));
        MethodInsnNode put = null, remove = null;
        for (var method : patched.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals("java/util/Map") && call.name.equals("putIfAbsent")) put = call;
                if (call.owner.equals("java/util/Map") && call.name.equals("remove")
                        && call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Z")) remove = call;
            }
        }
        CoremodRegression.check(put != null && remove != null,
                "Crystal lifecycle did not use atomic conditional map operations");

        Class<?> target = ConcurrencyRegression.define(patched);
        Constructor<?> constructor = target.getConstructor(int.class, BlockPos.class);
        Method load = target.getMethod("onLoad"), unload = target.getMethod("onChunkUnloaded");
        Map<Integer, BlockPos> registry = CrystalUpdateBatcher.ID_CRYSTAL_MAP;
        registry.clear();

        // A stale colliding tile must never remove the position that actually
        // won registration for the shared integer hash.
        Object winner = constructor.newInstance(7, new BlockPos(1, 64, 1));
        Object stale = constructor.newInstance(7, new BlockPos(2, 64, 2));
        call(load, winner);
        call(load, stale);
        call(unload, stale);
        CoremodRegression.check(registry.get(7).equals(new BlockPos(1, 64, 1)),
                "Stale crystal unload removed the registered collision winner");
        call(unload, winner);
        CoremodRegression.check(registry.isEmpty(), "Winning crystal did not unregister");

        int count = 10_000;
        List<Object> crystals = new ArrayList<>(count);
        for (int i = 0; i < count; i++) crystals.add(constructor.newInstance(i, new BlockPos(i, 70, i)));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (Object crystal : crystals) work.add(executor.submit(() -> call(load, crystal)));
            for (Future<?> future : work) future.get(20, TimeUnit.SECONDS);
            CoremodRegression.check(registry.size() == count, "Concurrent crystal registrations were lost");
            work.clear();
            for (Object crystal : crystals) work.add(executor.submit(() -> call(unload, crystal)));
            for (Future<?> future : work) future.get(20, TimeUnit.SECONDS);
            CoremodRegression.check(registry.isEmpty(), "Concurrent crystal unloads left stale entries");
        } finally {
            executor.shutdownNow();
            registry.clear();
        }
        System.out.println("PASS Draconic ID registry: collision-safe lifecycle + 10,000 concurrent loads/unloads");
    }
}
