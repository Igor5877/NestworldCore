import mekanism.common.content.network.InventoryNetwork;
import mekanism.common.content.network.transmitter.LogisticalTransporterBase;
import mekanism.common.content.transporter.PathfinderCache$CachedPath;
import mekanism.common.content.transporter.PathfinderCache$PathData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs the real transformed cache methods with only their network/value dependencies stubbed. */
public final class MekanismPathfinderRegression {
    @SuppressWarnings("unchecked")
    static void run(Path root, Path mods) throws Exception {
        var original = CoremodRegression.load(mods, "Mekanism-1.20.1-10.4.16.80.jar",
                "mekanism/common/content/transporter/PathfinderCache");
        var transformed = CoremodRegression.transform(
                CoremodRegression.transformer(root, "mod_compound_races.js"), original);
        for (String name : List.of("onChanged", "addCachedPath", "getCache", "reset")) {
            CoremodRegression.check((CoremodRegression.method(transformed, name).access
                    & Opcodes.ACC_SYNCHRONIZED) != 0, name + " does not use the shared class monitor");
        }

        Class<?> target = ConcurrencyRegression.define(transformed);
        Method add = target.getMethod("addCachedPath", LogisticalTransporterBase.class,
                PathfinderCache$PathData.class, List.class, double.class);
        Method get = target.getMethod("getCache", LogisticalTransporterBase.class,
                BlockPos.class, java.util.Set.class);
        Method changed = target.getMethod("onChanged", InventoryNetwork[].class);
        Method reset = target.getMethod("reset");
        Field cacheField = target.getDeclaredField("cachedPaths");
        cacheField.setAccessible(true);

        InventoryNetwork network = new InventoryNetwork(UUID.randomUUID());
        BlockPos start = new BlockPos(0, 64, 0);
        LogisticalTransporterBase transporter = new LogisticalTransporterBase(network, start);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> writers = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                int offset = worker * 5_000;
                writers.add(executor.submit(() -> {
                    for (int i = offset; i < offset + 5_000; i++) {
                        BlockPos end = new BlockPos(i, 70, i);
                        add.invoke(null, transporter,
                                new PathfinderCache$PathData(start, end, Direction.NORTH),
                                List.of(start, end), (double) i);
                    }
                    return null;
                }));
            }
            Future<?> invalidator = executor.submit(() -> {
                for (int i = 0; i < 1_000; i++) {
                    changed.invoke(null, (Object) new InventoryNetwork[]{network});
                    get.invoke(null, transporter, new BlockPos(i, 70, i), EnumSet.of(Direction.NORTH));
                }
                return null;
            });
            for (Future<?> writer : writers) writer.get(30, TimeUnit.SECONDS);
            invalidator.get(30, TimeUnit.SECONDS);

            // Establish a deterministic final generation after concurrent invalidation.
            changed.invoke(null, (Object) new InventoryNetwork[]{network});
            for (int i = 0; i < 1_000; i++) {
                BlockPos end = new BlockPos(i, 80, i);
                add.invoke(null, transporter,
                        new PathfinderCache$PathData(start, end, Direction.SOUTH),
                        List.of(start, end), (double) i);
            }
            for (int i = 0; i < 1_000; i++) {
                PathfinderCache$CachedPath hit = (PathfinderCache$CachedPath) get.invoke(null,
                        transporter, new BlockPos(i, 80, i), EnumSet.of(Direction.SOUTH));
                CoremodRegression.check(hit != null && hit.cost() == i, "Wrong cached path after invalidation");
            }
            Map<UUID, Map<?, ?>> cache = (Map<UUID, Map<?, ?>>) cacheField.get(null);
            CoremodRegression.check(cache.size() == 1 && cache.get(network.getUUID()).size() == 1_000,
                    "Nested cache was corrupted");
            reset.invoke(null);
            CoremodRegression.check(cache.isEmpty(), "Cache reset did not clear the shared map");
        } finally {
            executor.shutdownNow();
        }
        System.out.println("PASS Mekanism PathfinderCache: concurrent writes/invalidation and 1,000 verified hits");
    }
}
