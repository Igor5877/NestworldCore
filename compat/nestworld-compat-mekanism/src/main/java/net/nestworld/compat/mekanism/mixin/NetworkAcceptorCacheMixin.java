package net.nestworld.compat.mekanism.mixin;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import mekanism.common.content.network.transmitter.Transmitter;
import mekanism.common.lib.transmitter.TransmitterNetworkRegistry;
import mekanism.common.lib.transmitter.acceptor.NetworkAcceptorCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.nestworld.compat.mekanism.glue.MekCompat;
import net.nestworld.compat.mekanism.glue.PendingAcceptors;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * MEKANISM-REGISTRY-RACE-001 M-A for NetworkAcceptorCache (Mekanism 10.4.16.80, bytecode-fingerprinted by MekGate).
 * Region threads mark acceptor changes; the main thread commits them. A pending generation is mutated and
 * published only under {@link #nestworld$lock}; commit() takes the whole generation in one critical section and
 * processes it with no lock held. No capability lookup, world access or callback runs under the lock, and no
 * code holds this lock together with the registry lock.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(value = NetworkAcceptorCache.class, remap = false)
public abstract class NetworkAcceptorCacheMixin implements PendingAcceptors {
    @Shadow @Final private Map cachedAcceptors;
    @Shadow @Final @Mutable private Map changedAcceptors;

    @Unique
    private final Object nestworld$lock = new Object();

    @Shadow
    public abstract void updateTransmitterOnSide(Transmitter transmitter, Direction side);

    /**
     * @author NestWorld
     * @reason M-A: the mutation of the pending generation is the only critical section; registerChangedNetwork
     * runs after the lock is released (original order preserved: mark, then register).
     */
    @Overwrite
    public void acceptorChanged(Transmitter transmitter, Direction side) {
        boolean added;
        synchronized (nestworld$lock) {
            added = ((Set) changedAcceptors.computeIfAbsent(transmitter, t -> EnumSet.noneOf(Direction.class))).add(side);
        }
        if (MekCompat.AUDIT && added) MekCompat.PRODUCED.increment();
        TransmitterNetworkRegistry.registerChangedNetwork(transmitter.getTransmitterNetwork());
    }

    @Override
    public Map nestworld$takePending() {
        synchronized (nestworld$lock) {
            Map pending = changedAcceptors;
            if (pending.isEmpty()) return Collections.emptyMap();
            changedAcceptors = new Object2ObjectOpenHashMap();
            if (MekCompat.AUDIT) MekCompat.GENERATIONS.increment();
            return pending;
        }
    }

    @Override
    public Map nestworld$cachedAcceptors() {
        return cachedAcceptors;
    }

    /**
     * @author NestWorld
     * @reason M-A: take the generation atomically, then the original loop (isValid → updateTransmitterOnSide per
     * side) on the detached map, unlocked. The original trailing clear() is implied by the take.
     */
    @Overwrite
    public void commit() {
        Map detached = nestworld$takePending();
        if (detached.isEmpty()) return;
        if (MekCompat.AUDIT) MekCompat.PUBLISHED.add(nestworld$size(detached));
        for (Object raw : detached.entrySet()) {
            Map.Entry entry = (Map.Entry) raw;
            Transmitter transmitter = (Transmitter) entry.getKey();
            if (transmitter.isValid()) {
                for (Object side : (Set) entry.getValue()) updateTransmitterOnSide(transmitter, (Direction) side);
            }
        }
    }

    /**
     * @author NestWorld
     * @reason M-A: the cachedAcceptors merge is unchanged; the other cache's pending generation is taken under ITS
     * lock and merged under OURS (never both at once). The adopted cache does not commit it again.
     */
    @Overwrite
    public void adoptAcceptors(NetworkAcceptorCache other) {
        PendingAcceptors source = (PendingAcceptors) other;
        for (Object raw : source.nestworld$cachedAcceptors().entrySet()) {
            Map.Entry entry = (Map.Entry) raw;
            BlockPos pos = (BlockPos) entry.getKey();
            if (cachedAcceptors.containsKey(pos)) {
                ((Map) cachedAcceptors.get(pos)).putAll((Map) entry.getValue());
            } else {
                cachedAcceptors.put(pos, entry.getValue());
            }
        }
        Map moved = source.nestworld$takePending();
        if (moved.isEmpty()) return;
        synchronized (nestworld$lock) {
            for (Object raw : moved.entrySet()) {
                Map.Entry entry = (Map.Entry) raw;
                Set sides = (Set) entry.getValue();
                Set existing = (Set) changedAcceptors.get(entry.getKey());
                if (existing == null) {
                    changedAcceptors.put(entry.getKey(), sides);
                } else if (MekCompat.AUDIT) {
                    for (Object side : sides) if (!existing.add(side)) MekCompat.MERGED_DUPLICATE.increment();
                } else {
                    existing.addAll(sides);
                }
            }
        }
    }

    /**
     * @author NestWorld
     * @reason M-A: changedAcceptors.clear() becomes a take under the lock (same drop semantics).
     */
    @Overwrite
    public void deregister() {
        cachedAcceptors.clear();
        Map dropped = nestworld$takePending();
        if (MekCompat.AUDIT && !dropped.isEmpty()) MekCompat.DROPPED.add(nestworld$size(dropped));
    }

    @Unique
    private static long nestworld$size(Map generation) {
        long n = 0;
        for (Object v : generation.values()) n += ((Set) v).size();
        return n;
    }
}
