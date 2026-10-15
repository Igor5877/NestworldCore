package net.nestworld.compat.ae2.mixin;

import appeng.api.networking.IGridNode;
import appeng.me.service.P2PService;
import appeng.parts.p2p.P2PTunnelPart;
import com.google.common.collect.Multimap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.nestworld.compat.ae2.glue.Ae2Compat;
import net.nestworld.compat.ae2.glue.NwP2PService;
import net.nestworld.compat.ae2.glue.P2PState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * P2P-MAP-STATE-001 (H1): every reader / writer of P2PService's maps goes through one per-grid
 * {@link P2PState}. Readers never see a live collection view; writers are linearized; AE2's tunnel
 * callbacks run after the guard is released (leaf lock). Does NOT address H2 (accessDepth) or H3
 * (cross-owner forwarding).
 */
@Mixin(value = P2PService.class, remap = false)
public abstract class P2PServiceMixin implements NwP2PService {
    @Shadow @Final private HashMap<Short, P2PTunnelPart> inputs;
    @Shadow @Final private Multimap<Short, P2PTunnelPart> outputs;

    @Shadow public abstract short newFrequency();

    @Shadow public abstract void updateFreq(P2PTunnelPart t, short newFrequency);

    @Unique private final P2PState nw$state = new P2PState();

    @Override
    public P2PState nw$state() {
        return nw$state;
    }

    // ---------------------------------------------------------------- writers

    @WrapMethod(method = "addNode")
    private void nw$addNode(IGridNode node, CompoundTag savedData, Operation<Void> op) {
        nw$state.write(() -> op.call(node, savedData));
    }

    @WrapMethod(method = "removeNode")
    private void nw$removeNode(IGridNode node, Operation<Void> op) {
        nw$state.write(() -> op.call(node));
    }

    @WrapMethod(method = "updateFreq")
    private void nw$updateFreq(P2PTunnelPart t, short newFrequency, Operation<Void> op) {
        nw$state.write(() -> op.call(t, newFrequency));
    }

    /** The field write is part of the transaction; the host side effects of setFrequency run after release. */
    @WrapOperation(method = "updateFreq", at = @At(value = "INVOKE", target = "Lappeng/parts/p2p/P2PTunnelPart;setFrequency(S)V"))
    private void nw$setFrequency(P2PTunnelPart t, short freq, Operation<Void> op) {
        Runnable hook = P2PState.hookAtSetFrequency;
        if (hook != null) hook.run();
        if (!P2PState.PROTECT) {
            op.call(t, freq);
            return;
        }
        short old = t.getFrequency();
        ((P2PTunnelPartAccessor) t).nw$setFreqField(freq);
        if (old != freq && !P2PState.defer(() -> {
            t.getHost().markForSave();
            t.getHost().markForUpdate();
        })) {
            t.getHost().markForSave();
            t.getHost().markForUpdate();
        }
    }

    /** Capture updateTunnel's targets under the guard (AE2's view at that point); run the callbacks after release. */
    @WrapOperation(method = {"addNode", "removeNode", "updateFreq"},
            at = @At(value = "INVOKE", target = "Lappeng/me/service/P2PService;updateTunnel(SZZ)V"))
    private void nw$updateTunnel(P2PService self, short freq, boolean updateOutputs, boolean configChange, Operation<Void> op) {
        if (!P2PState.PROTECT) {
            op.call(self, freq, updateOutputs, configChange);
            return;
        }
        List<P2PTunnelPart> targets = new ArrayList<>();
        if (updateOutputs) {
            targets.addAll(outputs.get(freq));
        } else {
            P2PTunnelPart in = inputs.get(freq);
            if (in != null) targets.add(in);
        }
        Runnable callbacks = () -> {
            for (P2PTunnelPart p : targets) {
                if (configChange) p.onTunnelConfigChange();
                p.onTunnelNetworkChange();
            }
        };
        if (!P2PState.defer(callbacks)) callbacks.run();
    }

    /** Diagnostic only (vanilla behaviour kept): an input displaced by another tunnel under the same key. */
    @WrapOperation(method = {"addNode", "updateFreq"},
            at = @At(value = "INVOKE", target = "Ljava/util/HashMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object nw$inputPut(HashMap<Object, Object> map, Object key, Object value, Operation<Object> op) {
        Object prev = op.call(map, key, value);
        if (prev != null && prev != value && !Short.valueOf((short) 0).equals(key)) {   // freq 0 = unbound (vanilla)
            Ae2Compat.COUNTERS.p2pInputDisplaced.increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] P2P input displaced: freq={} {} replaced {}", key, value, prev);
        }
        return prev;
    }

    // ---------------------------------------------------------------- readers

    @WrapMethod(method = "getInput")
    private P2PTunnelPart nw$getInput(short freq, Operation<P2PTunnelPart> op) {
        Ae2Compat.COUNTERS.p2pReads.increment();
        return nw$state.read(() -> op.call(freq));
    }

    /** Never a live view: the stream is materialized under the guard. */
    @WrapMethod(method = "getOutputs")
    private <T extends P2PTunnelPart<T>> Stream<T> nw$getOutputs(short freq, Class<T> c, Operation<Stream<T>> op) {
        Ae2Compat.COUNTERS.p2pReads.increment();
        if (!P2PState.PROTECT) return op.call(freq, c);
        return nw$state.read(() -> op.call(freq, c).toList()).stream();
    }

    @WrapMethod(method = "newFrequency")
    private short nw$newFrequency(Operation<Short> op) {
        Short forced = P2PState.FORCED_CANDIDATE.get();
        if (forced != null) {
            P2PState.FORCED_CANDIDATE.remove();
            return forced;
        }
        return nw$state.read(() -> op.call());
    }

    @WrapOperation(method = "wakeInputTunnels", at = @At(value = "INVOKE", target = "Ljava/util/HashMap;values()Ljava/util/Collection;"))
    private Collection<Object> nw$wakeSnapshot(HashMap<Object, Object> map, Operation<Collection<Object>> op) {
        if (!P2PState.PROTECT) return op.call(map);
        return nw$state.read(() -> new ArrayList<>(op.call(map)));
    }

    // ---------------------------------------------------------------- allocate + bind

    @Override
    public short nw$allocateAndBind(P2PTunnelPart<?> tunnel, short candidate) {
        return nw$state.write(() -> {
            short f = candidate;
            P2PTunnelPart current = f == 0 ? null : inputs.get(f);
            if (f == 0 || (current != null && current != tunnel)) {
                f = newFrequency();
                Ae2Compat.COUNTERS.p2pAllocRebound.increment();
            }
            updateFreq(tunnel, f);                 // nested writer: reentrant, callbacks join the outer list
            return f;
        });
    }

    @Override
    public String nw$check(P2PTunnelPart<?> tunnel) {
        return nw$state.read(() -> {
            List<Short> keys = new ArrayList<>();
            for (Map.Entry<Short, P2PTunnelPart> e : inputs.entrySet()) if (e.getValue() == tunnel) keys.add(e.getKey());
            for (Map.Entry<Short, P2PTunnelPart> e : outputs.entries()) if (e.getValue() == tunnel) keys.add(e.getKey());
            if (keys.isEmpty() || (keys.size() == 1 && keys.get(0) == tunnel.getFrequency())) return null;
            return "freq=" + tunnel.getFrequency() + " member under " + keys;
        });
    }

    @Override
    public List<Short> nw$membership(P2PTunnelPart<?> tunnel) {
        return nw$state.read(() -> {
            List<Short> keys = new ArrayList<>();
            for (Map.Entry<Short, P2PTunnelPart> e : inputs.entrySet()) if (e.getValue() == tunnel) keys.add(e.getKey());
            for (Map.Entry<Short, P2PTunnelPart> e : outputs.entries()) if (e.getValue() == tunnel) keys.add(e.getKey());
            return keys;
        });
    }
}
