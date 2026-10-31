package net.nestworld.compat.ae2.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.cluster.implementations.QuantumCluster;
import appeng.me.cluster.implementations.SpatialPylonCluster;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nestworld.compat.ae2.glue.NwCluster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** AE2-PIN-LEAK-001 D4a: per-cluster-instance teardown claim (a re-formed cluster is a new object → a new claim). */
@Mixin(value = {CraftingCPUCluster.class, QuantumCluster.class, SpatialPylonCluster.class}, remap = false)
public abstract class ClusterTeardownClaimMixin implements NwCluster {
    @Unique private final AtomicBoolean nw$teardown = new AtomicBoolean();

    @Override
    public boolean nw$claimTeardown() {
        return nw$teardown.compareAndSet(false, true);
    }

    @Override
    public boolean nw$teardownClaimed() {
        return nw$teardown.get();
    }
}
