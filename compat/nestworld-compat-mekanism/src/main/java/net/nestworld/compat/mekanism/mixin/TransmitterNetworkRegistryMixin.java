package net.nestworld.compat.mekanism.mixin;

import com.google.common.collect.Multimap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import mekanism.common.lib.transmitter.DynamicNetwork;
import mekanism.common.lib.transmitter.TransmitterNetworkRegistry;
import net.nestworld.compat.mekanism.glue.ChangedNetworks;
import net.nestworld.compat.mekanism.glue.MekCompat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MEKANISM-REGISTRY-RACE-001 M-A for TransmitterNetworkRegistry.networksToChange (Mekanism 10.4.16.80). Only the
 * set the detector showed written off-main is covered (registerChangedNetwork from region threads). The
 * main-only collections (invalid/orphan/transmitters/ticket chunks, networks iteration) stay untouched.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(value = TransmitterNetworkRegistry.class, remap = false)
public abstract class TransmitterNetworkRegistryMixin implements ChangedNetworks {
    @Shadow @Final private Set networks;
    @Shadow private Set networksToChange;
    @Shadow @Final private Set invalidTransmitters;
    @Shadow @Final private Map newOrphanTransmitters;
    @Shadow @Final private Multimap transmitters;
    @Shadow @Final private Object2BooleanMap changedTicketChunks;

    @Unique
    private final Object nestworld$lock = new Object();

    @Shadow
    public static TransmitterNetworkRegistry getInstance() {
        throw new AssertionError();
    }

    @Override
    public boolean nestworld$addChanged(Object network) {
        synchronized (nestworld$lock) {
            return networksToChange.add(network);
        }
    }

    @Unique
    private Set nestworld$takeChanged() {
        synchronized (nestworld$lock) {
            Set pending = networksToChange;
            if (pending.isEmpty()) return Collections.emptySet();
            networksToChange = new ObjectOpenHashSet();
            if (MekCompat.AUDIT) MekCompat.GENERATIONS.increment();
            return pending;
        }
    }

    /**
     * @author NestWorld
     * @reason M-A: region threads call this; the add runs under the registry lock.
     */
    @Overwrite
    public static void registerChangedNetwork(DynamicNetwork network) {
        ((ChangedNetworks) getInstance()).nestworld$addChanged(network);
    }

    /** assignOrphans (main thread) adds discovered networks to networksToChange: same lock. */
    @Redirect(method = "assignOrphans", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private boolean nestworld$addOrphanNetwork(Set receiver, Object network) {
        return nestworld$addChanged(network);
    }

    /**
     * @author NestWorld
     * @reason M-A: take the generation atomically (the original isEmpty / read / replace sequence), then commit
     * each network outside the lock.
     */
    @Overwrite
    private void commitChanges() {
        for (Object network : nestworld$takeChanged()) ((DynamicNetwork) network).commit();
    }

    /**
     * @author NestWorld
     * @reason M-A: networks + networksToChange are removed as one pair under the registry lock.
     */
    @Overwrite
    public void removeNetwork(DynamicNetwork network) {
        synchronized (nestworld$lock) {
            networks.remove(network);
            networksToChange.remove(network);
        }
    }

    /**
     * @author NestWorld
     * @reason M-A: original clear order; networksToChange cleared under the registry lock.
     */
    @Overwrite
    public static void reset() {
        ((ChangedNetworks) getInstance()).nestworld$reset();
    }

    @Override
    public void nestworld$reset() {
        networks.clear();
        synchronized (nestworld$lock) {
            networksToChange.clear();
        }
        invalidTransmitters.clear();
        newOrphanTransmitters.clear();
        transmitters.clear();
        changedTicketChunks.clear();
    }
}
